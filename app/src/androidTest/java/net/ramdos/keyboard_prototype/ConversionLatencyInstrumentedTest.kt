package net.ramdos.keyboard_prototype

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ramdos.keyboard_prototype.engine.*
import net.ramdos.keyboard_prototype.engine.conversion.SumireKanaKanjiConverter
import net.ramdos.keyboard_prototype.engine.lm.OnnxHiraganaLanguageModel
import net.ramdos.keyboard_prototype.engine.lm.HiraganaTokenizer
import net.ramdos.keyboard_prototype.engine.lm.repeatedKanaInput
import net.ramdos.keyboard_prototype.ui.GojuonLayout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in wall-time diagnostics using synthetic input only; no production logging or timing gates. */
@RunWith(AndroidJUnit4::class)
class ConversionLatencyInstrumentedTest {
    @Test
    fun profileSyntheticPipeline() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("profileConversion") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = File(context.filesDir, "conversion-latency.jsonl")
        report.writeText("")
        fun emit(row: JSONObject) {
            report.appendText("$row\n")
            Log.i("ConversionLatency", row.toString())
        }
        emit(JSONObject().put("kind", "device").put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
        var start = System.nanoTime()
        OnnxHiraganaLanguageModel.open(context).use { realModel ->
            emit(JSONObject().put("kind", "initialization").put("stage", "onnxOpen")
                .put("ms", ms(System.nanoTime() - start)))
            start = System.nanoTime()
            SumireKanaKanjiConverter.open(context).use { realConverter ->
                emit(JSONObject().put("kind", "initialization").put("stage", "dictionaryOpen")
                    .put("ms", ms(System.nanoTime() - start)))
                val meter = Meter()
                val tokenizerConfig = context.assets.open("${OnnxHiraganaLanguageModel.ASSET_DIRECTORY}/tokenizer_config.json")
                    .bufferedReader().use { JSONObject(it.readText()) }
                val ords = tokenizerConfig.getJSONArray("char_ords")
                val tokenizer = HiraganaTokenizer(List(ords.length()) { ords.getInt(it) })
                val model = object : KanaLanguageModel {
                    override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> {
                        meter.lmCalls++
                        meter.prefixes += prefixes.size
                        // Fixtures normalize to BMP hiragana: one character is one token, capped at 96.
                        val length = prefixes.maxOf { (context.length + it.length).coerceIn(1, 96) }
                        meter.paddedTokens += prefixes.size * length
                        return meter.lm.measure { realModel.nextLogProbabilities(context, prefixes) }
                    }
                    override fun nextRepeatedLogProbabilities(context: String, prefixes: List<String>, maxPredictions: Int): List<List<Map<Char, Double>>> {
                        meter.lmCalls++
                        meter.prefixes += prefixes.size
                        val length = prefixes.maxOf { tokenizer.repeatedKanaInput(context, it, 96, maxPredictions).tokens.size }
                        meter.paddedTokens += prefixes.size * length
                        return meter.lm.measure { realModel.nextRepeatedLogProbabilities(context, prefixes, maxPredictions) }
                    }
                }
                val converter = object : KanaKanjiConverter {
                    override val readingLexicon = object : ReadingLexicon {
                        override fun newSession(): ReadingLexiconSession {
                            val delegate = meter.lexicon.measure { realConverter.readingLexicon.newSession() }
                            return object : ReadingLexiconSession {
                                override fun evaluate(reading: String, complete: Boolean): LexiconScore {
                                    meter.lexiconCalls++
                                    return meter.lexicon.measure { delegate.evaluate(reading, complete) }
                                }
                            }
                        }
                    }
                    override fun readingOf(text: String): String =
                        meter.context.measure { realConverter.readingOf(text) }
                    override fun convertCandidates(reading: String, limit: Int): List<ConversionCandidate> {
                        meter.conversionCalls++
                        return meter.conversion.measure { realConverter.convertCandidates(reading, limit) }
                    }
                }
                // Raise only the diagnostic timeout so long-context costs remain observable.
                val config = GlideDecoderConfig(maxDecodeMillis = 120_000)
                val engine = GlideCandidateEngine(model, converter, config)
                val noRepeatEngine = GlideCandidateEngine(model, converter,
                    config.copy(maxRepeatedCharactersPerEvent = 1))
                val fixtures = listOf(
                    Fixture("tap", "あ"),
                    Fixture("greeting", "こんにちは"),
                    Fixture("japanese", "にほんこ", "私は"),
                    Fixture("long_context", "にほんこ", "きょうはとてもいいてんきです。".repeat(8)),
                    Fixture("greeting_no_repeat", "こんにちは", repeat = false),
                )
                fun run(fixture: Fixture, phase: String, iteration: Int) {
                    val trace = traceFor(fixture.keys)
                    val events = StrokeModel().lattice(trace).events.size
                    meter.reset()
                    val started = System.nanoTime()
                    val candidates = (if (fixture.repeat) engine else noRepeatEngine)
                        .generateCandidates(trace, fixture.context)
                    val elapsed = System.nanoTime() - started
                    assertTrue("Synthetic fixture must produce candidates", candidates.isNotEmpty())
                    emit(JSONObject().put("kind", "sample").put("fixture", fixture.name)
                        .put("phase", phase).put("iteration", iteration).put("events", events)
                        .put("totalMs", ms(elapsed)).put("lmMs", ms(meter.lm.nanos))
                        .put("lexiconMs", ms(meter.lexicon.nanos)).put("contextMs", ms(meter.context.nanos))
                        .put("conversionMs", ms(meter.conversion.nanos))
                        .put("otherMs", ms(elapsed - meter.lm.nanos - meter.lexicon.nanos - meter.context.nanos - meter.conversion.nanos))
                        .put("lmCalls", meter.lmCalls).put("prefixes", meter.prefixes)
                        .put("paddedTokens", meter.paddedTokens).put("lexiconCalls", meter.lexiconCalls)
                        .put("conversionCalls", meter.conversionCalls).put("candidateCount", candidates.size)
                        .put("overDefaultBudget", elapsed >= 5_000_000_000L))
                }
                // Preserve first-use costs, then interleave fixtures to reduce ordering bias.
                fixtures.forEach { run(it, "first", 0) }
                repeat(2) { iteration -> fixtures.forEach { run(it, "warmup", iteration) } }
                repeat(5) { iteration -> fixtures.forEach { run(it, "measured", iteration) } }
            }
        }
    }

    private data class Fixture(val name: String, val keys: String, val context: String = "", val repeat: Boolean = true)

    /** Key centers, 180 ms stationary at each key followed by a 120 ms transition. */
    private fun traceFor(keys: String): GlideTrace {
        val points = keys.flatMapIndexed { index, kana ->
            val key = GojuonLayout.keys.single { it.kana == kana.toString() }
            val x = (key.left + key.right) / 2
            val y = (key.top + key.bottom) / 2
            listOf(TracePoint(x, y, index * 300L), TracePoint(x, y, index * 300L + 180))
        }
        return GlideTrace(points, GojuonLayout.keys)
    }

    private class Timer {
        var nanos = 0L
        inline fun <T> measure(block: () -> T): T {
            val started = System.nanoTime()
            try { return block() } finally { nanos += System.nanoTime() - started }
        }
    }

    private class Meter {
        val lm = Timer()
        val lexicon = Timer()
        val context = Timer()
        val conversion = Timer()
        var lmCalls = 0
        var prefixes = 0
        var paddedTokens = 0
        var lexiconCalls = 0
        var conversionCalls = 0
        fun reset() {
            listOf(lm, lexicon, context, conversion).forEach { it.nanos = 0 }
            lmCalls = 0
            prefixes = 0
            paddedTokens = 0
            lexiconCalls = 0
            conversionCalls = 0
        }
    }

    private fun ms(nanos: Long): Double = nanos / 1_000_000.0
}
