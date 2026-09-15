package net.ramdos.keyboard_prototype.engine

import org.junit.Assert.*
import org.junit.Test

class GlideCandidateEngineTest {
    @Test
    fun partiallyUnknownReadingIsConvertedAndRetainsLiteralKana() {
        assertEquals(listOf("亜い", "あい", "阿い"),
            unknownReadingCandidates("あい", listOf("亜い", "阿い", "亜い")))
    }

    @Test
    fun entirelyUnknownReadingRetainsLiteralKanaWithoutDuplicates() {
        assertEquals(listOf("あ"), unknownReadingCandidates("あ", listOf("あ")))
    }

    @Test
    fun unknownReadingRetainsLiteralKanaWhenConverterReturnsNoCandidates() {
        assertEquals(listOf("あい"), unknownReadingCandidates("あい", emptyList()))
    }

    @Test
    fun partialConversionRespectsCandidateLimit() {
        assertEquals(listOf("亜い", "あい"),
            unknownReadingCandidates("あい", listOf("亜い", "阿い"), maxCandidates = 2))
        assertEquals(listOf("亜い"),
            unknownReadingCandidates("あい", listOf("亜い", "阿い"), maxCandidates = 1))
    }

    private fun unknownReadingCandidates(
        reading: String,
        converted: List<String>,
        maxCandidates: Int = 12,
    ): List<String> {
        val expectedReading = reading
        var conversions = 0
        val lm = object : KanaLanguageModel {
            override fun nextLogProbabilities(context: String, prefixes: List<String>) =
                prefixes.map { prefix ->
                    reading.getOrNull(prefix.length)?.let { mapOf(it to 0.0) } ?: emptyMap()
                }
        }
        val converter = object : KanaKanjiConverter {
            override val readingLexicon = object : ReadingLexicon {
                override fun newSession() = object : ReadingLexiconSession {
                    override fun evaluate(reading: String, complete: Boolean) =
                        LexiconScore(4.0, reading.count { it == expectedReading.last() })
                }
            }
            override fun readingOf(text: String) = text
            override fun convertCandidates(reading: String, limit: Int): List<ConversionCandidate> {
                assertEquals(expectedReading, reading)
                assertEquals(3, limit)
                conversions++
                return converted.mapIndexed { index, text -> ConversionCandidate(text, index.toDouble()) }
            }
        }
        val keys = reading.mapIndexed { i, kana ->
            KanaKey(kana.toString(), i.toFloat(), 0f, i + 1f, 1f)
        }
        val trace = GlideTrace(reading.indices.map { i ->
            TracePoint(i + 0.5f, 0.5f, i * 100L)
        }, keys)
        val candidates = GlideCandidateEngine(lm, converter, maxCandidates = maxCandidates).use {
            it.generateCandidates(trace)
        }
        assertEquals(1, conversions)
        return candidates
    }

    @Test
    fun passesReadingContextToLmThenConvertsAndRetainsLiteralKana() {
        var observedContext = ""
        var modelClosed = false
        var converterClosed = false
        val lm = object : KanaLanguageModel {
            override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> {
                observedContext = context
                return prefixes.map { if (it.isEmpty()) mapOf('あ' to 0.0) else emptyMap() }
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

    @Test
    fun confidenceUsesScoresDespitePreviewOrderAndSurvivesDisplayLimit() {
        val readings = listOf(reading("あ", -10000.0), reading("い", -10001.0))
        val variants = mapOf("あ" to listOf(ConversionCandidate("亜", 0.0)))
        rankingEngine(variants).use { engine ->
            val candidates = engine.rankScoredCandidates(readings)
            assertEquals(listOf("亜", "い", "あ"), candidates.map { it.text })
            val byText = candidates.associateBy { it.text }
            assertEquals(1.0, candidates.sumOf { requireNotNull(it.confidence) }, 1e-12)
            assertEquals(byText.getValue("亜").confidence, byText.getValue("あ").confidence)
            assertTrue(byText.getValue("い").confidence!! < byText.getValue("あ").confidence!!)
            rankingEngine(variants, limit = 1).use { limited ->
                assertEquals(listOf(candidates.first()), limited.rankScoredCandidates(readings))
            }
        }
    }

    @Test
    fun confidenceDeduplicatesSurfacesAndHandlesSingleEmptyAndExtremeScores() {
        rankingEngine(mapOf("あ" to listOf(ConversionCandidate("あ", 0.0)))).use { engine ->
            assertTrue(engine.rankScoredCandidates(emptyList()).isEmpty())
            assertEquals(listOf(DisplayCandidate("あ", 1.0)), engine.rankScoredCandidates(listOf(reading("あ", 0.0))))
            val candidates = engine.rankScoredCandidates(listOf(reading("あ", 0.0), reading("い", -10000.0)))
            assertEquals(listOf(DisplayCandidate("あ", 1.0), DisplayCandidate("い", 0.0)), candidates)
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
                return variants[reading].orEmpty().take(limit)
            }
        }
        return GlideCandidateEngine(model, converter, maxCandidates = limit, conversionCostWeight = weight)
    }
}
