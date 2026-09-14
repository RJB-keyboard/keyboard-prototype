package net.ramdos.keyboard_prototype.engine

import kotlin.math.exp
import kotlin.math.ln
import net.ramdos.keyboard_prototype.ui.GojuonLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideDecoderTest {
    private val keys = listOf(key('な', 0, 0), key('に', 1, 0), key('ぬ', 2, 0), key('ね', 1, 1))
    private val geometry = StrokeModel(StrokeModelConfig(nearbyKeyCount = 1))

    @Test
    fun straightCrossingsAreOptionalAndStrokeOnlyPosteriorIsNormalized() {
        val model = RecordingModel()
        val trace = trace(point(0, 0, 0), point(2, 0, 100))
        val decoder = GlideDecoder(geometry, model, GlideDecoderConfig(languageWeight = 0.0))
        val candidates = decoder.decode(trace)
        assertEquals("なぬ", candidates.first().reading)
        assertTrue(candidates.any { it.reading == "なにぬ" })
        assertEquals(1.0, candidates.sumOf { it.posterior }, 1e-10)
        assertTrue(model.calls.isEmpty())
        geometry.lattice(trace).events.forEach { event ->
            assertEquals(1.0, event.characterLogProbabilities.values.sumOf(::exp) + exp(event.skipLogProbability), 1e-10)
        }
    }

    @Test
    fun cornerAndCenterDwellFavorEmission() {
        val decoder = GlideDecoder(geometry, RecordingModel(), GlideDecoderConfig(languageWeight = 0.0))
        val corner = decoder.decode(trace(point(0, 0, 0), point(1, 0, 50), point(1, 1, 100)))
        assertEquals("なにね", corner.first().reading)
        val dwell = decoder.decode(trace(point(0, 0, 0), point(1, 0, 50), point(1, 0, 250), point(2, 0, 300)))
        assertEquals("なにぬ", dwell.first().reading)
    }

    @Test
    fun returnToTheSameKeyCanRepeatWithoutEmittingTheTurnaroundKey() {
        val decoder = GlideDecoder(geometry, RecordingModel(), GlideDecoderConfig(languageWeight = 0.0))
        val candidates = decoder.decode(trace(point(0, 0, 0), point(1, 0, 60), point(0, 0, 120)))
        assertTrue(candidates.any { it.reading == "なな" })
        assertEquals("なにな", candidates.first().reading)
    }

    @Test
    fun loopInsideOneKeyCanRepeatButStationaryHoldDoesNot() {
        val singleKey = listOf(key('な', 0, 0))
        val decoder = GlideDecoder(geometry, RecordingModel(), GlideDecoderConfig(languageWeight = 0.0))
        val loop = GlideTrace(listOf(
            point(0, 0, 0), TracePoint(0.2375f, 0.125f, 50), point(0, 0, 100),
        ), singleKey)
        assertEquals("なな", decoder.decode(loop).first().reading)
        val hold = GlideTrace(listOf(point(0, 0, 0), point(0, 0, 1000)), singleKey)
        assertEquals(listOf("な"), decoder.decode(hold).map { it.reading })
    }

    @Test
    fun languageModelCanSelectVoicedAndSmallKanaFromBaseKeys() {
        val baseKeys = listOf(key('か', 0, 0), key('や', 1, 0))
        val model = RecordingModel { _, prefix ->
            distribution(if (prefix.isEmpty()) 'が' else 'ゃ')
        }
        val decoder = GlideDecoder(geometry, model, GlideDecoderConfig(languageWeight = 1.0))
        val candidates = decoder.decode(GlideTrace(listOf(point(0, 0, 0), point(1, 0, 60)), baseKeys))
        assertEquals("がゃ", candidates.first().reading)
        assertTrue(candidates.any { it.reading == "かや" })
        assertTrue(candidates.first().strokeLogProbability < candidates.first { it.reading == "かや" }.strokeLogProbability)
    }

    @Test
    fun contextChangesLanguageRankingWithoutChangingStrokeDistribution() {
        val baseKeys = listOf(key('か', 0, 0))
        val trace = GlideTrace(listOf(point(0, 0, 0)), baseKeys)
        val model = RecordingModel { context, _ -> distribution(if (context == "voiced") 'が' else 'か') }
        val decoder = GlideDecoder(geometry, model, GlideDecoderConfig(languageWeight = 1.0))
        val voiced = decoder.decode(trace, "voiced")
        val base = decoder.decode(trace, "base")
        assertEquals("が", voiced.first().reading)
        assertEquals("か", base.first().reading)
        assertEquals(voiced.associate { it.reading to it.strokeLogProbability }, base.associate { it.reading to it.strokeLogProbability })
        assertEquals(setOf("voiced", "base"), model.calls.map { it.first }.toSet())
    }

    @Test
    fun prefixBatchesAreCachedAndCombinedScoresRetainBothComponents() {
        val model = RecordingModel()
        val config = GlideDecoderConfig(strokeWeight = 1.3, languageWeight = 0.2)
        val decoder = GlideDecoder(geometry, model, config)
        val result = decoder.decode(trace(point(0, 0, 0), point(1, 0, 60), point(0, 0, 120), point(2, 0, 220)))
        assertFalse(result.isEmpty())
        val requested = model.calls.flatMap { it.second }
        assertEquals(requested.size, requested.toSet().size)
        assertTrue(model.calls.all { it.second.size <= config.maxBeamWidth })
        result.forEach {
            assertEquals(config.strokeWeight * it.strokeLogProbability + config.languageWeight * it.languageLogProbability, it.score, 1e-10)
            assertEquals(it.reading.length * ln(1.0 / 86), it.languageLogProbability, 1e-10)
        }
    }

    @Test
    fun repeatedAlignmentPathsAreMarginalizedInsteadOfReturnedAsDuplicates() {
        val model = RecordingModel()
        val decoder = GlideDecoder(geometry, model, GlideDecoderConfig(languageWeight = 0.0))
        val trace = trace(point(0, 0, 0), point(1, 0, 50), point(0, 0, 100), point(1, 0, 150), point(2, 0, 200))
        val result = decoder.decode(trace)
        assertEquals(result.size, result.map { it.reading }.toSet().size)
        assertEquals(1.0, result.sumOf { it.posterior }, 1e-10)
        assertTrue(result.all { it.strokeLogProbability <= 0.000001 })
        // なにぬ has exactly two alignments: emit the first に or the second に.
        val events = geometry.lattice(trace).events
        assertEquals(5, events.size)
        val expected = exp(events[2].skipLogProbability) * (
            exp(events[1].characterLogProbabilities.getValue('に') + events[3].skipLogProbability) +
                exp(events[1].skipLogProbability + events[3].characterLogProbabilities.getValue('に')))
        assertEquals(expected, exp(result.first { it.reading == "なにぬ" }.strokeLogProbability), 1e-10)
    }

    @Test
    fun optionalInsertionBonusCanCompensateLanguageLengthBias() {
        val trace = trace(point(0, 0, 0), point(2, 0, 100))
        val noBonus = GlideDecoder(geometry, RecordingModel(), GlideDecoderConfig(languageWeight = 1.0)).decode(trace)
        val withBonus = GlideDecoder(geometry, RecordingModel(), GlideDecoderConfig(languageWeight = 1.0, characterInsertionBonus = 6.0)).decode(trace)
        assertEquals("なぬ", noBonus.first().reading)
        assertEquals("なにぬ", withBonus.first().reading)
        withBonus.forEach {
            assertEquals(it.strokeLogProbability + it.languageLogProbability + 6.0 * it.reading.length, it.score, 1e-10)
        }
    }

    @Test
    fun multiColumnGreetingSurvivesPassThroughKeysOnTheActualKeyboardLayout() {
        val expected = "こんにちは"
        val trace = GlideTrace(expected.mapIndexed { index, kana ->
            val key = GojuonLayout.keys.first { it.kana == kana.toString() }
            TracePoint((key.left + key.right) / 2, (key.top + key.bottom) / 2, index * 120L)
        }, GojuonLayout.keys)
        // An oracle tests geometry/search coverage; this is not an LM accuracy benchmark.
        val model = RecordingModel { _, prefix ->
            distribution(if (expected.startsWith(prefix) && prefix.length < expected.length) expected[prefix.length] else 'ゖ')
        }
        val result = GlideDecoder(languageModel = model).decode(trace)
        assertEquals(expected, result.first().reading)
    }

    @Test
    fun rejectsNonfiniteNonmonotonicAndOutsideInputWithoutCallingLanguageModel() {
        val model = RecordingModel()
        val decoder = GlideDecoder(geometry, model)
        listOf(
            trace(TracePoint(Float.NaN, 0f, 0)),
            trace(TracePoint(Float.POSITIVE_INFINITY, 0f, 0)),
            trace(point(0, 0, 10), point(1, 0, 0)),
            trace(TracePoint(-1f, -1f, 0)),
            GlideTrace(emptyList(), keys),
        ).forEach { assertTrue(decoder.decode(it).isEmpty()) }
        assertTrue(model.calls.isEmpty())
    }

    @Test
    fun cancellationIsPreserved() {
        Thread.currentThread().interrupt()
        try {
            GlideDecoder(geometry, RecordingModel()).decode(trace(point(0, 0, 0)))
            throw AssertionError("Expected interruption")
        } catch (_: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedLanguageModelBatchIsRejected() {
        val model = object : KanaLanguageModel {
            override fun nextLogProbabilities(context: String, prefixes: List<String>) = emptyList<Map<Char, Double>>()
        }
        GlideDecoder(geometry, model).decode(trace(point(0, 0, 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nanLanguageProbabilityIsRejected() {
        val model = RecordingModel { _, _ -> mapOf('な' to Double.NaN) }
        GlideDecoder(geometry, model).decode(trace(point(0, 0, 0)))
    }

    @Test
    fun emptyLimitAndReadingLengthLimitsAreRespected() {
        val model = RecordingModel()
        val decoder = GlideDecoder(geometry, model, GlideDecoderConfig(maxReadingLength = 1))
        assertTrue(decoder.decode(trace(point(0, 0, 0)), limit = 0).isEmpty())
        assertTrue(model.calls.isEmpty())
        assertTrue(decoder.decode(trace(point(0, 0, 0), point(2, 0, 100))).isEmpty())
    }

    private fun trace(vararg points: TracePoint) = GlideTrace(points.toList(), keys)

    private class RecordingModel(
        private val prediction: (String, String) -> Map<Char, Double> = { _, _ -> ('\u3041'..'\u3096').associateWith { ln(1.0 / 86) } },
    ) : KanaLanguageModel {
        val calls = mutableListOf<Pair<String, List<String>>>()
        override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> {
            calls.add(context to prefixes.toList())
            return prefixes.map { prediction(context, it) }
        }
    }

    companion object {
        private fun key(kana: Char, x: Int, y: Int) = KanaKey(kana.toString(), x / 4f, y / 4f, (x + 1) / 4f, (y + 1) / 4f)
        private fun point(x: Int, y: Int, time: Long) = TracePoint((x + 0.5f) / 4f, (y + 0.5f) / 4f, time)
        private fun distribution(favorite: Char): Map<Char, Double> = ('\u3041'..'\u3096').associateWith { if (it == favorite) ln(0.99) else ln(0.01 / 85) }
    }
}
