package net.ramdos.keyboard_prototype

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ramdos.keyboard_prototype.engine.*
import net.ramdos.keyboard_prototype.engine.conversion.SumireKanaKanjiConverter
import net.ramdos.keyboard_prototype.engine.lm.OnnxHiraganaLanguageModel
import net.ramdos.keyboard_prototype.ui.GojuonLayout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Paired synthetic comparison. Baseline adapter uses the original one-distribution API. */
@RunWith(AndroidJUnit4::class)
class RepeatedInferenceInstrumentedTest {
    @Test
    fun compareCandidatesAndLatency() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("profileRepeatedInference") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = File(context.filesDir, "repeated-inference.jsonl")
        report.writeText("")
        fun emit(row: JSONObject) {
            report.appendText("$row\n")
            Log.i("RepeatedInference", row.toString())
        }
        emit(JSONObject().put("kind", "device").put("model", Build.MODEL).put("android", Build.VERSION.RELEASE))
        OnnxHiraganaLanguageModel.open(context).use { model ->
            SumireKanaKanjiConverter.open(context).use { converter ->
                val before = CountingModel(model, false)
                val after = CountingModel(model, true)
                val config = GlideDecoderConfig(maxDecodeMillis = 120_000)
                val ranker = GlideCandidateEngine(model, converter, config)
                val fixtures = listOf(
                    Fixture("tap", "あ"), Fixture("greeting", "こんにちは"),
                    Fixture("japanese", "にほんこ", "私は"),
                    Fixture("repeated", "こまよ"), Fixture("pain", "いかいたい"),
                    Fixture("long_context", "にほんこ", "きょうはとてもいいてんきです。".repeat(8)),
                )
                fun run(provider: CountingModel, fixture: Fixture, iteration: Int): Pair<List<ReadingCandidate>, List<String>> {
                    val kanaContext = converter.readingOf(fixture.context)
                    val trace = traceFor(fixture.keys)
                    provider.reset()
                    val start = System.nanoTime()
                    val readings = GlideDecoder(languageModel = provider, config = config,
                        readingLexicon = converter.readingLexicon).decode(trace, kanaContext)
                    val decoded = System.nanoTime()
                    val candidates = ranker.rankCandidates(readings)
                    val end = System.nanoTime()
                    emit(JSONObject().put("kind", "sample").put("fixture", fixture.name)
                        .put("iteration", iteration).put("lookahead", provider.lookahead)
                        .put("decodeMs", (decoded - start) / 1e6).put("rankMs", (end - decoded) / 1e6)
                        .put("totalMs", (end - start) / 1e6).put("lmMs", provider.nanos / 1e6)
                        .put("lmCalls", provider.calls).put("rows", provider.rows).put("predictions", provider.predictions))
                    return readings to candidates
                }
                // Short cases: one warmup pair, three measured pairs in alternating order.
                // Long window: one pair verifies fallback; it is not a speedup benchmark.
                fixtures.forEach { fixture ->
                    val iterations = if (fixture.name == "long_context") 1 else 4
                    repeat(iterations) { iteration ->
                        val results = if (iteration % 2 == 0) {
                            run(before, fixture, iteration) to run(after, fixture, iteration)
                        } else {
                            val optimized = run(after, fixture, iteration)
                            run(before, fixture, iteration) to optimized
                        }
                        val (baseline, optimized) = results
                        assertEquals(fixture.name, baseline.first.map { it.reading }, optimized.first.map { it.reading })
                        assertEquals(fixture.name, baseline.second, optimized.second)
                        baseline.first.zip(optimized.first).forEach { (a, b) ->
                            assertEquals(a.strokeLogProbability, b.strokeLogProbability, 1e-10)
                            assertEquals(a.dictionaryCost, b.dictionaryCost, 0.0)
                            assertEquals(a.languageLogProbability, b.languageLogProbability, 0.003)
                            assertEquals(a.score, b.score, 0.0011)
                            assertEquals(a.posterior, b.posterior, 0.0002)
                            assertEquals(a.hasImplicitRepetition, b.hasImplicitRepetition)
                        }
                        assertTrue(before.calls >= after.calls)
                        emit(JSONObject().put("kind", "equivalence").put("fixture", fixture.name)
                            .put("iteration", iteration).put("candidateOrderMatches", true))
                    }
                }
            }
        }
    }

    private class CountingModel(val delegate: KanaLanguageModel, val lookahead: Boolean) : KanaLanguageModel {
        var nanos = 0L
        var calls = 0
        var rows = 0
        var predictions = 0
        fun reset() { nanos = 0; calls = 0; rows = 0; predictions = 0 }
        override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> {
            calls++
            rows += prefixes.size
            val start = System.nanoTime()
            return try { delegate.nextLogProbabilities(context, prefixes).also { predictions += it.size } }
                finally { nanos += System.nanoTime() - start }
        }
        override fun nextRepeatedLogProbabilities(context: String, prefixes: List<String>, maxPredictions: Int): List<List<Map<Char, Double>>> {
            if (!lookahead) return super.nextRepeatedLogProbabilities(context, prefixes, maxPredictions)
            calls++
            rows += prefixes.size
            val start = System.nanoTime()
            return try { delegate.nextRepeatedLogProbabilities(context, prefixes, maxPredictions)
                .also { predictions += it.sumOf { row -> row.size } } }
                finally { nanos += System.nanoTime() - start }
        }
    }

    private data class Fixture(val name: String, val keys: String, val context: String = "")
    private fun traceFor(keys: String) = GlideTrace(keys.flatMapIndexed { index, kana ->
        val key = GojuonLayout.keys.single { it.kana == kana.toString() }
        val x = (key.left + key.right) / 2
        val y = (key.top + key.bottom) / 2
        listOf(TracePoint(x, y, index * 300L), TracePoint(x, y, index * 300L + 180))
    }, GojuonLayout.keys)
}
