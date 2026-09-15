package net.ramdos.keyboard_prototype.engine

import org.junit.Assert.*
import org.junit.Test

class GlideCandidateEngineTest {
    @Test
    fun dictionaryUnknownReadingStaysLiteralInsteadOfBeingConvertedToKanjiFragments() {
        val lm = object : KanaLanguageModel {
            override fun nextLogProbabilities(context: String, prefixes: List<String>) =
                prefixes.map { mapOf('あ' to 0.0) }
        }
        val converter = object : KanaKanjiConverter {
            override val readingLexicon = object : ReadingLexicon {
                override fun newSession() = object : ReadingLexiconSession {
                    override fun evaluate(reading: String, complete: Boolean) = LexiconScore(4.0, reading.length)
                }
            }
            override fun readingOf(text: String) = text
            override fun convertCandidates(reading: String, limit: Int): List<ConversionCandidate> = error("Unknown kana should stay literal")
        }
        val trace = GlideTrace(listOf(TracePoint(0.5f, 0.5f, 0)), listOf(KanaKey("あ", 0f, 0f, 1f, 1f)))
        GlideCandidateEngine(lm, converter).use { assertEquals(listOf("あ"), it.generateCandidates(trace)) }
    }

    @Test
    fun passesReadingContextToLmThenConvertsAndRetainsLiteralKana() {
        var observedContext = ""
        var modelClosed = false
        var converterClosed = false
        val lm = object : KanaLanguageModel {
            override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> {
                observedContext = context
                return prefixes.map { mapOf('あ' to 0.0) }
            }
            override fun close() { modelClosed = true }
        }
        val converter = object : KanaKanjiConverter {
            override fun readingOf(text: String): String {
                assertEquals("私は", text)
                return "わたしは"
            }
            override fun convertCandidates(reading: String, limit: Int): List<ConversionCandidate> {
                assertEquals("あ", reading)
                return listOf(ConversionCandidate("亜", 1.0), ConversionCandidate("阿", 2.0), ConversionCandidate("亜", 3.0))
            }
            override fun close() { converterClosed = true }
        }
        val trace = GlideTrace(listOf(TracePoint(0.5f, 0.5f, 0)), listOf(KanaKey("あ", 0f, 0f, 1f, 1f)))
        GlideCandidateEngine(lm, converter).use {
            assertEquals(listOf("亜", "あ", "阿"), it.generateCandidates(trace, "私は"))
        }
        assertEquals("わたしは", observedContext)
        assertTrue(modelClosed)
        assertTrue(converterClosed)
    }

    @Test
    fun secondAndThirdConversionsOfLikelyReadingBeatWeakReadings() {
        val readings = (0..7).map { reading("よみ$it", -10.0 * it) }
        val variants = readings.associate { candidate -> candidate.reading to
            (0..2).map { ConversionCandidate("${candidate.reading}候補$it", 2.0 + it * 0.1) } }
        rankingEngine(variants).use { engine ->
            val result = engine.rankCandidates(readings)
            assertEquals(listOf("よみ0候補0", "よみ0", "よみ0候補1", "よみ0候補2"), result.take(4))
            assertEquals(12, result.size)
            assertEquals(result.distinct(), result)
        }
    }

    @Test
    fun largeConversionGapLetsAnotherReadingWinAndAbsoluteCostsAreNotCountedTwice() {
        // First reading remains best despite its much higher absolute dictionary cost.
        // Its second surface should rank below the second reading because of the cost gap.
        val readings = listOf(reading("はし", 0.0), reading("ほし", -0.5))
        fun variants(offset: Double) = mapOf(
            "はし" to listOf(ConversionCandidate("橋", 100.0 + offset), ConversionCandidate("箸", 102.0 + offset)),
            "ほし" to listOf(ConversionCandidate("星", -20.0)))
        rankingEngine(variants(0.0)).use { engine ->
            val result = engine.rankCandidates(readings)
            assertEquals(listOf("橋", "星", "はし", "ほし", "箸"), result)
            rankingEngine(variants(-200.0)).use { shifted ->
                assertEquals(result, shifted.rankCandidates(readings))
            }
        }
    }

    @Test
    fun duplicateSurfaceKeepsItsBestScoreAcrossReadings() {
        val readings = listOf(reading("あ", 0.0), reading("い", -1.0), reading("う", -2.0))
        val variants = mapOf(
            "あ" to listOf(ConversionCandidate("亜", 0.0), ConversionCandidate("共通", 10.0)),
            "い" to listOf(ConversionCandidate("共通", 0.0)),
            "う" to listOf(ConversionCandidate("宇", 0.0)))
        rankingEngine(variants).use { engine ->
            val result = engine.rankCandidates(readings)
            assertEquals(1, result.count { it == "共通" })
            assertTrue(result.indexOf("共通") < result.indexOf("宇"))
        }
    }

    @Test
    fun tightLimitsRetainTopLiteralAndAlternativeAndUseScoreOrder() {
        val readings = listOf(reading("はし", 0.0), reading("ほし", -10.0))
        val variants = mapOf("はし" to listOf(ConversionCandidate("橋", 0.0), ConversionCandidate("箸", 0.1)),
            "ほし" to listOf(ConversionCandidate("星", 0.0)))
        for ((limit, expected) in listOf(
            1 to listOf("橋"),
            2 to listOf("橋", "はし"),
            3 to listOf("橋", "はし", "星"),
            4 to listOf("橋", "はし", "箸", "星"),
        )) {
            rankingEngine(variants, limit).use { engine -> assertEquals(expected, engine.rankCandidates(readings)) }
        }
    }

    @Test
    fun unknownAndEmptyConversionsRemainSelectableAndEmptyInputIsEmpty() {
        val readings = listOf(reading("はし", 0.0), reading("ゖ", -10.0).copy(unknownCharacters = 1))
        val variants = mapOf("はし" to listOf(ConversionCandidate("橋", 0.0), ConversionCandidate("箸", 0.1)))
        rankingEngine(variants, 3).use { engine ->
            assertEquals(listOf("橋", "はし", "ゖ"), engine.rankCandidates(readings))
            assertTrue(engine.rankCandidates(emptyList()).isEmpty())
            assertEquals(listOf("から"), engine.rankCandidates(listOf(reading("から", 0.0))))
        }
    }

    @Test
    fun conversionWeightChangesRankingAndEqualScoresAreStable() {
        val readings = listOf(reading("はし", 0.0), reading("ほし", -0.5))
        val variants = mapOf("はし" to listOf(ConversionCandidate("橋", 0.0), ConversionCandidate("箸", 1.0)),
            "ほし" to listOf(ConversionCandidate("星", 0.0)))
        rankingEngine(variants, weight = 0.0).use { engine ->
            val expected = listOf("橋", "星", "箸", "はし", "ほし")
            assertEquals(expected, engine.rankCandidates(readings))
            assertEquals(expected, engine.rankCandidates(readings))
        }
    }

    @Test
    fun nearbyReadingsAppearBeforeSpellingVariantsButDistantReadingsDoNot() {
        val readings = listOf(reading("あ", 0.0), reading("い", -1.0), reading("う", -1.5), reading("え", -8.0))
        val variants = readings.associate { it.reading to listOf(
            ConversionCandidate("${it.reading}変換", 0.0), ConversionCandidate("${it.reading}別字", 0.1)) }
        rankingEngine(variants, 4).use { engine ->
            assertEquals(listOf("あ変換", "い変換", "う変換", "あ"), engine.rankCandidates(readings))
        }
        rankingEngine(variants, 2).use { engine ->
            assertEquals(listOf("あ変換", "あ"), engine.rankCandidates(readings))
        }
    }

    @Test
    fun absoluteSurfaceEvidenceCanChangeReadingOrderButIsBoundedAndOptional() {
        val variants = mapOf(
            "あ" to listOf(ConversionCandidate("亜", 100.0, surfaceCost = 8.0)),
            "い" to listOf(ConversionCandidate("伊", -100.0, surfaceCost = 2.0)))
        rankingEngine(variants).use { engine ->
            assertEquals("伊", engine.rankCandidates(listOf(reading("あ", 0.0), reading("い", -0.5))).first())
            assertEquals("亜", engine.rankCandidates(listOf(reading("あ", 0.0), reading("い", -0.8))).first())
        }
        rankingEngine(variants.mapValues { (_, candidates) -> candidates.map { it.copy(surfaceCost = null) } }).use { engine ->
            assertEquals("亜", engine.rankCandidates(listOf(reading("あ", 0.0), reading("い", -0.5))).first())
        }
    }

    private fun reading(text: String, score: Double) = ReadingCandidate(text, score, 0.0, score, 0.0)

    private fun rankingEngine(
        variants: Map<String, List<ConversionCandidate>>,
        limit: Int = 12,
        weight: Double = 1.0,
    ): GlideCandidateEngine {
        val model = object : KanaLanguageModel {
            override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> =
                error("Ranking must not call the language model")
        }
        val converter = object : KanaKanjiConverter {
            override fun readingOf(text: String) = text
            override fun convertCandidates(reading: String, limit: Int): List<ConversionCandidate> {
                check(reading != "ゖ") { "Unknown readings must not be converted" }
                return variants[reading].orEmpty().take(limit)
            }
        }
        return GlideCandidateEngine(model, converter, maxCandidates = limit, conversionCostWeight = weight)
    }
}
