package net.ramdos.keyboard_prototype

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ramdos.keyboard_prototype.engine.GlideCandidateEngine
import net.ramdos.keyboard_prototype.engine.GlideDecoder
import net.ramdos.keyboard_prototype.engine.GlideDecoderConfig
import net.ramdos.keyboard_prototype.engine.GlideTrace
import net.ramdos.keyboard_prototype.engine.ReadingCandidate
import net.ramdos.keyboard_prototype.engine.ReadingLexiconSession
import net.ramdos.keyboard_prototype.engine.TracePoint
import net.ramdos.keyboard_prototype.engine.conversion.SumireKanaKanjiConverter
import net.ramdos.keyboard_prototype.engine.lm.OnnxHiraganaLanguageModel
import net.ramdos.keyboard_prototype.ui.GojuonLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.hypot

/** Actual geometry + GPT-2 + dictionary, with no fake language or conversion provider. */
@RunWith(AndroidJUnit4::class)
class GlideEngineInstrumentedTest {
    @Test
    fun comparesAmbiguousPainAndShoppingReadings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OnnxHiraganaLanguageModel.open(context).use { model ->
            SumireKanaKanjiConverter.open(context).use { converter ->
                val decoder = GlideDecoder(languageModel = model, readingLexicon = converter.readingLexicon,
                    config = GlideDecoderConfig(maxDecodeMillis = 15_000))
                model.nextLogProbabilities("", listOf(""))
                val engine = GlideCandidateEngine(model, converter, GlideDecoderConfig(maxDecodeMillis = 15_000))
                for (baseKeys in listOf("いかいたい")) {
                    for (trace in listOf(traceFor(baseKeys), continuousTraceFor(baseKeys, 0))) {
                        val readings = decoder.decode(trace)
                        Log.i("GlideEngineTest", "Ambiguous keys=$baseKeys points=${trace.points.size} readings=$readings")
                        assertValidCandidates(readings)
                        val candidates = engine.rankCandidates(readings)
                        Log.i("GlideEngineTest", "Ambiguous candidates=$candidates")
                        assertTrue(candidates.toString(), "胃が痛い" in candidates.take(3))
                        assertTrue(candidates.toString(), "いかいたい" in candidates)
                    }
                }
            }
        }
    }

    @Test
    fun realPipelineOffersGomamayoWithoutReturningToMa() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = OnnxHiraganaLanguageModel.open(context)
        val converter = try { SumireKanaKanjiConverter.open(context) } catch (error: Throwable) {
            model.close()
            throw error
        }
        GlideCandidateEngine(model, converter).use { engine ->
            val started = System.nanoTime()
            val candidates = engine.generateCandidates(traceFor("こまよ"))
            Log.i("GlideEngineTest", "Repeated kana millis=${(System.nanoTime() - started) / 1_000_000}, candidates=$candidates")
            assertTrue("Expected ごままよ among $candidates", "ごままよ" in candidates)
        }
    }

    @Test
    fun comparesDictionaryOnTheSameNoisyContinuousGlidesUsingTheRealModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OnnxHiraganaLanguageModel.open(context).use { model ->
            SumireKanaKanjiConverter.open(context).use { converter ->
                // Keep the historical dictionary comparison independent of length correction.
                val config = GlideDecoderConfig(maxDecodeMillis = 15_000, characterInsertionBonus = 0.0)
                val lexicon = requireNotNull(converter.readingLexicon)
                val withoutDictionary = GlideDecoder(
                    languageModel = model,
                    readingLexicon = lexicon,
                    config = config.copy(dictionaryWeight = 0.0),
                )
                val withDictionary = GlideDecoder(
                    languageModel = model,
                    readingLexicon = lexicon,
                    config = config.copy(dictionaryWeight = 1.0),
                )
                // Exclude initial native inference setup from the first A/B pair.
                model.nextLogProbabilities("", listOf(""))
                // These are reproducible geometry fixtures, never captured user input.
                // Keep A, B, beam width, context and the trace identical in each pair.
                val fixtures = listOf(
                    NoisyFixture("こんにちは", "こんにちは", ""),
                    NoisyFixture("ありがとう", "ありかとう", ""),
                    NoisyFixture("にほんご", "にほんこ", "わたしは"),
                    NoisyFixture("とうきょう", "とうきよう", ""),
                )
                fixtures.forEachIndexed { index, fixture ->
                    val trace = continuousTraceFor(fixture.baseKeys, index)
                    val diagnosticLexicon = lexicon.newSession()
                    val baseline = measuredDecode(withoutDictionary, trace, fixture, "dictionary=0", diagnosticLexicon)
                    val mixed = measuredDecode(withDictionary, trace, fixture, "dictionary=1", diagnosticLexicon)
                    assertValidCandidates(baseline)
                    assertValidCandidates(mixed)
                    when (fixture.reading) {
                        "にほんご" -> {
                            // The updated wa-column layout changes this synthetic path.
                            // Dictionary scoring improves 日本語 from third to second;
                            // the shorter にこ still leads (first place is not claimed).
                            assertEquals("にこ", baseline.first().reading)
                            assertEquals(2, baseline.indexOfFirst { it.reading == fixture.reading })
                            assertEquals(1, mixed.indexOfFirst { it.reading == fixture.reading })
                            assertTrue(mixed.single { it.reading == fixture.reading }.dictionaryCost <
                                diagnosticLexicon.evaluate(baseline.first().reading, complete = true).cost)
                        }
                        "こんにちは" -> {
                            val baselineRank = baseline.indexOfFirst { it.reading == fixture.reading }
                            val mixedRank = mixed.indexOfFirst { it.reading == fixture.reading }
                            assertTrue("Greeting must remain in both candidate lists", baselineRank >= 0 && mixedRank >= 0)
                            assertTrue("Dictionary should improve the greeting's rank", mixedRank < baselineRank)
                        }
                        "ありがとう" -> {
                            // Splitting emission mass across repeat counts adds a
                            // small length penalty; without dictionary scoring the
                            // shorter ありとう can now lead, but ありがとう survives.
                            assertTrue(baseline.indexOfFirst { it.reading == fixture.reading } in 0..1)
                            assertEquals(fixture.reading, mixed.first().reading)
                        }
                        // とうきょう currently misses the returned beam in both
                        // modes. Keep it in diagnostics without claiming a fix.
                    }
                }
            }
        }
    }

    @Test
    fun lengthCorrectionKeepsTracedReadingsAheadOfShortFragments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OnnxHiraganaLanguageModel.open(context).use { model ->
            SumireKanaKanjiConverter.open(context).use { converter ->
                val config = GlideDecoderConfig(maxDecodeMillis = 15_000)
                val previous = GlideDecoder(languageModel = model, readingLexicon = converter.readingLexicon,
                    config = config.copy(characterInsertionBonus = 0.0))
                val corrected = GlideDecoder(languageModel = model, readingLexicon = converter.readingLexicon,
                    config = config)
                val engine = GlideCandidateEngine(model, converter, config)
                model.nextLogProbabilities("", listOf(""))
                val fixtures = listOf(
                    NoisyFixture("こんにちは", "こんにちは", ""),
                    NoisyFixture("ありがとう", "ありかとう", ""),
                    NoisyFixture("にほんご", "にほんこ", "わたしは"),
                )
                fixtures.forEachIndexed { index, fixture ->
                    val trace = continuousTraceFor(fixture.baseKeys, index)
                    val baseline = previous.decode(trace, fixture.context)
                    val readings = corrected.decode(trace, fixture.context)
                    val candidates = engine.rankCandidates(readings)
                    Log.i("GlideEngineTest", "Length correction ${fixture.reading}: before=$baseline after=$readings candidates=$candidates")
                    assertValidCandidates(readings)
                    assertEquals(fixture.reading, readings.first().reading)
                    if (fixture.reading == "にほんご") {
                        assertEquals("にこ", baseline.first().reading)
                        assertTrue(candidates.toString(), "日本語" in candidates.take(3))
                    }
                }
            }
        }
    }

    @Test
    fun realPipelineConvertsADeliberateGlideAndKeepsTheLiteralReading() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = OnnxHiraganaLanguageModel.open(context)
        val converter = try { SumireKanaKanjiConverter.open(context) } catch (error: Throwable) {
            model.close()
            throw error
        }
        GlideCandidateEngine(model, converter).use { engine ->
            val started = System.nanoTime()
            val candidates = engine.generateCandidates(traceFor("にほんこ"), "私は")
            Log.i("GlideEngineTest", "Fixture decode millis=${(System.nanoTime() - started) / 1_000_000}, candidates=$candidates")
            assertTrue("Expected 日本語 among $candidates", "日本語" in candidates)
            assertTrue(candidates.any { candidate -> candidate.all { it in '\u3041'..'\u3096' || it == 'ー' } })
            assertEquals(candidates.size, candidates.distinct().size)
            assertTrue(candidates.size <= 12)
        }
    }

    private fun traceFor(baseKeys: String): GlideTrace {
        val points = mutableListOf<TracePoint>()
        var time = 0L
        baseKeys.forEach { kana ->
            val key = GojuonLayout.keys.single { it.kana == kana.toString() }
            val x = (key.left + key.right) / 2
            val y = (key.top + key.bottom) / 2
            points += TracePoint(x, y, time)
            time += 180
            points += TracePoint(x, y, time)
            time += 120
        }
        return GlideTrace(points, GojuonLayout.keys)
    }

    private data class NoisyFixture(val reading: String, val baseKeys: String, val context: String)

    private fun measuredDecode(
        decoder: GlideDecoder,
        trace: GlideTrace,
        fixture: NoisyFixture,
        label: String,
        diagnosticLexicon: ReadingLexiconSession,
    ): List<ReadingCandidate> {
        val started = System.nanoTime()
        val result = decoder.decode(trace, fixture.context)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        val rank = result.indexOfFirst { it.reading == fixture.reading }.let { if (it < 0) "absent" else "${it + 1}" }
        Log.i("GlideEngineTest", "Noisy fixture=${fixture.reading}, $label, expectedRank=$rank, decodeMillis=$elapsedMillis, points=${trace.points.size}, gestureMillis=${trace.points.last().elapsedMillis}")
        result.forEachIndexed { index, candidate ->
            // dictionaryWeight=0 deliberately skips scoring during decoding. Score
            // both output lists afterward so diagnostic costs remain comparable.
            val dictionaryScore = diagnosticLexicon.evaluate(candidate.reading, complete = true)
            Log.i("GlideEngineTest", "Noisy fixture=${fixture.reading}, $label, rank=${index + 1}, candidate=$candidate, diagnosticDictionary=$dictionaryScore")
        }
        return result
    }

    private fun assertValidCandidates(candidates: List<ReadingCandidate>) {
        assertFalse("A valid in-key gesture must have candidates", candidates.isEmpty())
        assertTrue(candidates.size <= 8)
        assertEquals(candidates.size, candidates.map { it.reading }.distinct().size)
        assertEquals(1.0, candidates.sumOf { it.posterior }, 1e-9)
        assertTrue(candidates.all { candidate ->
            candidate.reading.isNotEmpty() && candidate.reading.all { it in '\u3041'..'\u3096' || it == 'ー' } &&
                candidate.score.isFinite() && candidate.posterior.isFinite()
        })
        assertTrue(candidates.zipWithNext().all { (first, second) -> first.score >= second.score })
    }

    /**
     * A smooth, uninterrupted path with deterministic offsets up to 0.23 key
     * widths/heights. Cubic Hermite segments round turns without stationary
     * samples. Unlike traceFor(), this does not hold each intended key for 180 ms.
     * Synthetic fixtures exercise robustness; they do not establish user accuracy.
     */
    private fun continuousTraceFor(baseKeys: String, offsetPhase: Int): GlideTrace {
        val offsetsX = floatArrayOf(0.18f, -0.20f, 0.12f, -0.16f, 0.22f, -0.10f)
        val offsetsY = floatArrayOf(-0.14f, 0.18f, 0.23f, -0.20f, 0.12f, -0.18f)
        val anchors = baseKeys.mapIndexed { index, kana ->
            val key = GojuonLayout.keys.single { it.kana == kana.toString() }
            val offset = (index + offsetPhase) % offsetsX.size
            TracePoint(
                (key.left + key.right) / 2 + (key.right - key.left) * offsetsX[offset],
                (key.top + key.bottom) / 2 + (key.bottom - key.top) * offsetsY[offset],
                0,
            )
        }
        require(anchors.size >= 2)
        val points = mutableListOf(anchors.first())
        var elapsedMillis = 0L
        for (index in 0 until anchors.lastIndex) {
            val before = anchors[maxOf(0, index - 1)]
            val start = anchors[index]
            val end = anchors[index + 1]
            val after = anchors[minOf(anchors.lastIndex, index + 2)]
            val distanceInKeys = hypot((end.x - start.x) / 0.1, (end.y - start.y) / 0.2)
            val duration = (65 + distanceInKeys * 16).toLong()
            val steps = maxOf(4, ceil(duration / 12.0).toInt())
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                val t2 = t * t
                val t3 = t2 * t
                val h00 = 2 * t3 - 3 * t2 + 1
                val h10 = t3 - 2 * t2 + t
                val h01 = -2 * t3 + 3 * t2
                val h11 = t3 - t2
                fun coordinate(previous: Float, from: Float, to: Float, following: Float): Float =
                    (h00 * from + h10 * 0.175f * (to - previous) +
                        h01 * to + h11 * 0.175f * (following - from)).coerceIn(0.01f, 0.99f)
                points += TracePoint(
                    coordinate(before.x, start.x, end.x, after.x),
                    coordinate(before.y, start.y, end.y, after.y),
                    elapsedMillis + duration * step / steps,
                )
            }
            elapsedMillis += duration
        }
        return GlideTrace(points, GojuonLayout.keys)
    }
}
