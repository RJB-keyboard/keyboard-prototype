package net.ramdos.keyboard_prototype.engine

import kotlin.math.exp
import kotlin.math.ln
import net.ramdos.keyboard_prototype.ui.GojuonLayout
import org.junit.Assert.*
import org.junit.Test

class RepeatedKanaTest {
    private val geometry = StrokeModel(StrokeModelConfig(nearbyKeyCount = 1))
    private val singleKey = listOf(KanaKey("ま", 0f, 0f, 1f, 1f))
    private val tap = GlideTrace(listOf(TracePoint(0.5f, 0.5f, 0)), singleKey)
    private val noLanguage = object : KanaLanguageModel {
        override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> =
            error("Language model must not be called with zero language weight")
    }

    @Test
    fun oneVisitCanEmitOneTwoOrThreeCopiesWithNormalizedStrokeMass() {
        val result = GlideDecoder(geometry, noLanguage, GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0)).decode(tap)
        assertEquals(listOf("ま", "まま", "ままま"), result.map { it.reading })
        assertEquals(listOf(false, true, true), result.map { it.hasImplicitRepetition })
        listOf(0.85, 0.15 * 0.85, 0.15 * 0.15).zip(result).forEach { (expected, candidate) ->
            assertEquals(expected, exp(candidate.strokeLogProbability), 1e-10)
        }
        assertEquals(1.0, result.sumOf { exp(it.strokeLogProbability) }, 1e-10)
        assertEquals(1.0, result.sumOf { it.posterior }, 1e-10)
    }

    @Test
    fun explicitLoopAndImplicitRepeatsMergeTheirAlignmentProbabilities() {
        val loop = tap.copy(points = listOf(
            TracePoint(0.5f, 0.5f, 0), TracePoint(0.95f, 0.5f, 50), TracePoint(0.5f, 0.5f, 100),
        ))
        assertEquals(2, geometry.lattice(loop).events.size)
        val result = GlideDecoder(geometry, noLanguage, GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0)).decode(loop)
        val probabilities = listOf(0.85, 0.15 * 0.85, 0.15 * 0.15)
        val expected = mutableMapOf<String, Double>()
        for (first in 1..3) for (second in 1..3) {
            val reading = "ま".repeat(first + second)
            expected[reading] = (expected[reading] ?: 0.0) + probabilities[first - 1] * probabilities[second - 1]
        }
        assertEquals(expected.keys, result.map { it.reading }.toSet())
        assertEquals(expected.size, result.size)
        result.forEach { assertEquals(expected.getValue(it.reading), exp(it.strokeLogProbability), 1e-10) }
        assertEquals(1.0, result.sumOf { exp(it.strokeLogProbability) }, 1e-10)
    }

    @Test
    fun repeatedVoicedKanaKeepsTheChosenVariantAndPaysItsPriorOnce() {
        val trace = tap.copy(keys = listOf(KanaKey("は", 0f, 0f, 1f, 1f)))
        val result = GlideDecoder(geometry, noLanguage,
            GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0, maxBeamWidth = 32, maxCandidates = 32)).decode(trace)
        assertTrue(result.any { it.reading == "ばば" })
        assertTrue(result.any { it.reading == "ぱぱぱ" })
        assertTrue(result.all { it.reading.toSet().size == 1 })
        assertEquals(1.0, result.sumOf { exp(it.strokeLogProbability) }, 1e-10)
        val voicedPrior = exp(geometry.lattice(trace).events.single().characterLogProbabilities.getValue('ば'))
        assertEquals(voicedPrior * 0.15 * 0.85,
            exp(result.single { it.reading == "ばば" }.strokeLogProbability), 1e-10)
    }

    @Test
    fun repetitionCanBeDisabledAndRespectsTheReadingLengthCap() {
        for (config in listOf(
            GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0, maxRepeatedCharactersPerEvent = 1),
            GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0, repetitionProbability = 0.0),
            GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0, maxReadingLength = 1),
        )) {
            val result = GlideDecoder(geometry, noLanguage, config).decode(tap)
            assertEquals(listOf("ま"), result.map { it.reading })
            assertEquals(0.0, result.single().strokeLogProbability, 0.0)
        }
        val limited = GlideDecoder(geometry, noLanguage,
            GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0, maxReadingLength = 2)).decode(tap)
        assertEquals(listOf("ま", "まま"), limited.map { it.reading })
        assertEquals(1.0, limited.sumOf { exp(it.strokeLogProbability) }, 1e-10)
    }

    @Test
    fun repeatedAlternativesSurviveAFullBeamWithoutDisplacingTheBestReading() {
        val trace = tap.copy(keys = listOf(KanaKey("は", 0f, 0f, 1f, 1f)))
        val config = GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0, maxBeamWidth = 4, maxCandidates = 4)
        val result = GlideDecoder(geometry, noLanguage, config).decode(trace)
        assertEquals("は", result.first().reading)
        assertEquals(4, result.size)
        assertTrue(result.count { it.hasImplicitRepetition } >= 2)
        assertTrue(result.zipWithNext().all { (first, second) -> first.score >= second.score })
        assertEquals(1.0, result.sumOf { it.posterior }, 1e-10)
        val noReservation = GlideDecoder(geometry, noLanguage, config.copy(repeatedBeamSlots = 0)).decode(trace)
        assertTrue(noReservation.count { it.hasImplicitRepetition } < 2)
    }

    @Test
    fun literalRepeatedReadingsSurviveManyConversionVariantsAndSmallDisplayLimits() {
        val converter = object : KanaKanjiConverter {
            override fun readingOf(text: String) = text
            override fun convertCandidates(reading: String, limit: Int) =
                listOf("変換:$reading", "別:$reading", "他:$reading")
                    .mapIndexed { index, text -> ConversionCandidate(text, index.toDouble()) }.take(limit)
        }
        for (limit in listOf(1, 2, 3, 4, 12)) {
            GlideCandidateEngine(noLanguage, converter, GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0), limit).use {
                val result = it.generateCandidates(tap)
                assertEquals("変換:ま", result.first())
                assertTrue(result.size <= limit)
                assertEquals(result.size, result.distinct().size)
                if (limit >= 2) assertTrue("ま" in result)
                if (limit >= 3) assertTrue("まま" in result)
                if (limit >= 4) assertTrue("ままま" in result)
            }
        }
    }

    @Test
    fun dictionaryCanCompleteARepeatedEndingBeforeFinalPruning() {
        val lexicon = object : ReadingLexicon {
            override fun newSession() = object : ReadingLexiconSession {
                override fun evaluate(reading: String, complete: Boolean) =
                    LexiconScore(if (!complete || reading == "ままま") 0.0 else 10.0)
            }
        }
        val result = GlideDecoder(geometry, noLanguage,
            GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0, maxBeamWidth = 1, maxCandidates = 1), lexicon).decode(tap)
        assertEquals("ままま", result.single().reading)
        assertEquals(0.0, result.single().dictionaryCost, 0.0)
    }

    @Test
    fun omittedRepeatsAtTheStartMiddleAndEndSurviveOnTheActualLayout() {
        for ((input, expected) in listOf("こまよ" to "ごままよ", "まよ" to "ままよ",
            "こま" to "こまま", "まよ" to "まままよ", "まよ" to "ままよよ")) {
            val result = GlideDecoder(languageModel = oracle(expected)).decode(trace(input))
            assertTrue("Input: $input; candidates: ${result.map { it.reading }}", result.any { it.reading == expected })
            assertEquals(expected.length * ln(0.99), result.single { it.reading == expected }.languageLogProbability, 1e-10)
            assertEquals(result.size, result.map { it.reading }.toSet().size)
        }
    }

    @Test
    fun gomamayoIsExposedByTheCandidateEngineFromKomayo() {
        val converter = object : KanaKanjiConverter {
            override fun readingOf(text: String) = text
            override fun convertCandidates(reading: String, limit: Int) = listOf(ConversionCandidate(reading, 0.0))
        }
        GlideCandidateEngine(oracle("ごままよ"), converter).use {
            assertTrue(it.generateCandidates(trace("こまよ")).contains("ごままよ"))
        }
    }

    @Test
    fun impossibleRepeatedCharacterDoesNotBypassTheLanguageModel() {
        val model = object : KanaLanguageModel {
            override fun nextLogProbabilities(context: String, prefixes: List<String>) =
                prefixes.map { if (it.isEmpty()) mapOf('ま' to 0.0) else emptyMap() }
        }
        assertEquals(listOf("ま"), GlideDecoder(geometry, model).decode(tap).map { it.reading })
    }

    @Test
    fun invalidRepetitionSettingsAreRejected() {
        for (count in listOf(0, 9)) {
            assertThrows(IllegalArgumentException::class.java) { GlideDecoderConfig(maxRepeatedCharactersPerEvent = count) }
        }
        for (slots in listOf(-1, 129)) {
            assertThrows(IllegalArgumentException::class.java) { GlideDecoderConfig(repeatedBeamSlots = slots) }
        }
        for (probability in listOf(-0.1, 0.51, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { GlideDecoderConfig(repetitionProbability = probability) }
        }
    }

    // An oracle verifies search coverage and scoring, not real-model accuracy.
    private fun oracle(expected: String) = object : KanaLanguageModel {
        override fun nextLogProbabilities(context: String, prefixes: List<String>) = prefixes.map { prefix ->
            val favorite = if (expected.startsWith(prefix) && prefix.length < expected.length) expected[prefix.length] else 'ゖ'
            ('\u3041'..'\u3096').associateWith { if (it == favorite) ln(0.99) else ln(0.01 / 85) }
        }
    }

    private fun trace(input: String) = GlideTrace(input.mapIndexed { index, kana ->
        val key = GojuonLayout.keys.first { it.kana == kana.toString() }
        TracePoint((key.left + key.right) / 2, (key.top + key.bottom) / 2, index * 120L)
    }, GojuonLayout.keys)
}
