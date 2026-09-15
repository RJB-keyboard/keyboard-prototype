package net.ramdos.keyboard_prototype.engine.lm

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import net.ramdos.keyboard_prototype.engine.KanaLanguageModel
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Offline inference using the author's unmodified, pinned ONNX export. Open on a worker thread. */
class OnnxHiraganaLanguageModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: HiraganaTokenizer,
    private val maxContextTokens: Int,
    private val maxBatchSize: Int,
) : KanaLanguageModel {
    private var closed = false
    private val runLock = Any()
    private var activeRun: OrtSession.RunOptions? = null
    private val cancellationGeneration = AtomicLong()

    /** Thread-safe. Cancels the current request, including native computation, without closing the model. */
    override fun cancelPendingInference() {
        cancellationGeneration.incrementAndGet()
        synchronized(runLock) { activeRun?.setTerminate(true) }
    }

    @Synchronized
    override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> =
        nextRepeatedLogProbabilities(context, prefixes, 1).map { it.single() }

    @Synchronized
    override fun nextRepeatedLogProbabilities(
        context: String, prefixes: List<String>, maxPredictions: Int,
    ): List<List<Map<Char, Double>>> {
        require(maxPredictions in 1..8)
        check(!closed) { "Language model is closed" }
        val generation = cancellationGeneration.get()
        checkCancellation(generation)
        if (prefixes.isEmpty()) return emptyList()
        val distributions = ArrayList<List<Map<Char, Double>>>(prefixes.size)
        for (batch in prefixes.chunked(maxBatchSize)) {
            checkCancellation(generation)
            val inputs = batch.map { tokenizer.repeatedKanaInput(context, it, maxContextTokens, maxPredictions) }
            val encoded = inputs.map { it.tokens }
            val length = encoded.maxOf { it.size }
            val ids = Array(encoded.size) { row -> LongArray(length) { column ->
                encoded[row].getOrElse(column) { HiraganaTokenizer.PAD_TOKEN_ID.toLong() }
            } }
            val masks = Array(encoded.size) { row -> LongArray(length) { column ->
                if (column < encoded[row].size) 1L else 0L
            } }
            val positions = Array(encoded.size) { row -> LongArray(length) { column ->
                if (column < encoded[row].size) column.toLong() else 0L
            } }
            OnnxTensor.createTensor(environment, ids).use { idTensor ->
                OnnxTensor.createTensor(environment, masks).use { maskTensor ->
                    OnnxTensor.createTensor(environment, positions).use { positionTensor ->
                        val options = OrtSession.RunOptions()
                        try {
                            synchronized(runLock) {
                                checkCancellation(generation)
                                activeRun = options
                            }
                            session.run(mapOf(
                                "input_ids" to idTensor,
                                "attention_mask" to maskTensor,
                                "position_ids" to positionTensor,
                            ), setOf("logits"), options).use { result ->
                                checkCancellation(generation)
                                val tensor = result.get("logits").orElseThrow {
                                    IllegalStateException("ONNX model did not return logits")
                                } as OnnxTensor
                                val shape = tensor.info.shape
                                check(shape.contentEquals(longArrayOf(encoded.size.toLong(), length.toLong(), tokenizer.vocabularySize.toLong()))) {
                                    "Unexpected logits shape: ${shape.contentToString()}"
                                }
                                // The causal mask makes earlier positions independent of the
                                // appended repeats. Copy only the requested positions.
                                val values = tensor.floatBuffer
                                inputs.forEachIndexed { row, input ->
                                    distributions += input.predictionPositions.map { position ->
                                        val start = (row * length + position) * tokenizer.vocabularySize
                                        val logits = FloatArray(tokenizer.vocabularySize) { values.get(start + it) }
                                        tokenizer.logProbabilities(logits)
                                    }
                                }
                            }
                        } catch (error: OrtException) {
                            checkCancellation(generation)
                            throw error
                        } finally {
                            synchronized(runLock) {
                                activeRun = null
                                options.close()
                            }
                        }
                    }
                }
            }
        }
        checkCancellation(generation)
        return distributions
    }

    private fun checkCancellation(generation: Long) {
        if (Thread.currentThread().isInterrupted || cancellationGeneration.get() != generation) {
            throw CancellationException("Hiragana inference cancelled")
        }
    }

    /** Call cancelPendingInference first if another thread may currently be running inference. */
    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            session.close()
        }
        // OrtEnvironment.getEnvironment() is shared with other model sessions; do not close it here.
    }

    companion object {
        const val ASSET_DIRECTORY = "models/hiragana-gpt2-xsmall"
        const val MODEL_REVISION = "b0ef59dcdfd8eaddc3010cb8a2060c6196bba573"
        const val MODEL_SHA256 = "9ded1f55511aeffa49ff2af4e41b35770b2b419011c14aba5423b50e103afa82"
        private const val MODEL_SIZE = 84_652_543L

        /** Missing or invalid assets throw; callers must present model unavailability explicitly. */
        fun open(context: Context, maxContextTokens: Int = 96, maxBatchSize: Int = 16): OnnxHiraganaLanguageModel {
            require(maxBatchSize in 1..64)
            val config = context.assets.open("$ASSET_DIRECTORY/tokenizer_config.json").bufferedReader().use {
                JSONObject(it.readText())
            }
            val ords = config.getJSONArray("char_ords")
            val tokenizer = HiraganaTokenizer(List(ords.length()) { ords.getInt(it) }, config.getInt("model_max_length"))
            require(maxContextTokens in 1..tokenizer.modelMaxLength)
            val model = materializeModel(context.applicationContext)
            val environment = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                environment.createSession(model.absolutePath, options)
            }
            try {
                val expectedInputs = setOf("input_ids", "attention_mask", "position_ids")
                require(session.inputNames == expectedInputs) { "Unsupported ONNX inputs: ${session.inputNames}" }
                for (name in expectedInputs) {
                    val info = session.inputInfo.getValue(name).info as? TensorInfo
                    require(info?.type == OnnxJavaType.INT64 && info.shape.size == 2) { "Invalid input: $name" }
                }
                val output = session.outputInfo["logits"]?.info as? TensorInfo
                require(output?.type == OnnxJavaType.FLOAT && output.shape.size == 3 && output.shape[2] == tokenizer.vocabularySize.toLong()) {
                    "Model/tokenizer logits mismatch"
                }
                return OnnxHiraganaLanguageModel(environment, session, tokenizer, maxContextTokens, maxBatchSize)
            } catch (error: Throwable) {
                session.close()
                throw error
            }
        }

        @Synchronized
        private fun materializeModel(context: Context): File {
            val directory = File(context.noBackupFilesDir, "hiragana-models")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create model directory" }
            val target = File(directory, "$MODEL_SHA256.onnx")
            if (target.isFile && target.length() == MODEL_SIZE) return target
            val staging = File.createTempFile("hiragana-", ".part", directory)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                context.assets.open("$ASSET_DIRECTORY/model.onnx").use { source ->
                    FileOutputStream(staging).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw CancellationException("Model loading cancelled")
                            val read = source.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(hash == MODEL_SHA256 && staging.length() == MODEL_SIZE) { "Model asset SHA-256 mismatch; run tools/prepare_hiragana_model.ps1" }
                check(staging.renameTo(target)) { "Cannot finalize model cache" }
                return target
            } finally {
                staging.delete()
            }
        }
    }
}
