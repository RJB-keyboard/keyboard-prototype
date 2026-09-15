package net.ramdos.keyboard_prototype.engine.conversion

import java.io.File
import java.io.ByteArrayInputStream
import net.ramdos.keyboard_prototype.engine.GlideCandidateEngine
import net.ramdos.keyboard_prototype.engine.KanaLanguageModel
import net.ramdos.keyboard_prototype.engine.ReadingCandidate
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual shipped dictionaries and morphological analyzer, without network access. */
class SumireKanaKanjiConverterTest {
    @Test
    fun ambiguousReadingsKeepPainAndShoppingSelectableWithRealSurfaceEvidence() {
        val model = object : KanaLanguageModel {
            override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> =
                error("This test isolates post-decoding ranking")
        }
        val engine = GlideCandidateEngine(model, converter)
        fun reading(text: String, score: Double) = ReadingCandidate(text, score, 0.0, score, 0.0)
        val shopping = converter.convertCandidates("いかかいたい", 3).first()
        val pain = converter.convertCandidates("いがいたい", 3).first()
        assertTrue(requireNotNull(pain.surfaceCost) < requireNotNull(shopping.surfaceCost))
        // Controlled reading scores, not an assertion about unrecorded user gestures.
        for (gap in listOf(0.1, 1.3)) {
            val result = engine.rankCandidates(listOf(reading("いかかいたい", 0.0), reading("いがいたい", -gap)))
            assertTrue(result.toString(), "胃が痛い" in result.take(3))
            assertTrue(result.toString(), shopping.text in result.take(3))
            assertTrue("いかかいたい" in result)
            if (gap == 0.1) assertEquals("胃が痛い", result.first())
            else assertEquals(shopping.text, result.first())
        }
    }

    @Test
    fun stomachPainKeepsItsContextAdvantageAndIsReturnedFirst() {
        val candidates = converter.convertCandidates("いがいたい", 20)
        val stomach = candidates.single { it.text == "胃が痛い" }
        val fragment = candidates.single { it.text == "以外たい" }
        // Both previously received -0.75: the cheaper fragment hid the natural phrase.
        assertTrue(stomach.dictionaryCost > fragment.dictionaryCost)
        assertTrue(stomach.contextAdjustment < fragment.contextAdjustment)
        assertEquals("胃が痛い", candidates.first().text)
        assertEquals(candidates.take(3), converter.convertCandidates("いがいたい", 3))
        assertEquals("胃が痛い", converter.convert("いがいたい", 1).single())
        assertEquals(0, converter.readingLexicon.newSession().evaluate("いがいたい", true).unknownCharacters)
        assertEquals("頭が痛い", converter.convert("あたまがいたい", 1).single())
        assertEquals("歯が痛い", converter.convert("はがいたい", 1).single())
    }

    @Test
    fun meetingAFriendPrefersAuMeaningMeet() {
        val candidates = converter.convertCandidates("ともだちにあう", 20)
        assertEquals(candidates.toString(), "友達に会う", candidates.first().text)
    }

    @Test
    fun comparesGeneralHomophoneEvaluationAgainstDictionaryBaseline() {
        val root = listOf(File("src/main/assets/conversion"), File("app/src/main/assets/conversion"))
            .first { it.isDirectory }
        val evaluation = listOf(File("../tools/conversion/evaluation.tsv"), File("tools/conversion/evaluation.tsv"))
            .first { it.isFile }.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val rows = mutableListOf("reading\texpected\tbaselineFirst\trerankedFirst\tbaselineRank\trerankedRank\tbaselineMs\trerankedMs")
        var before = 0
        var after = 0
        val regressions = mutableListOf<String>()
        SumireKanaKanjiConverter.fromAssets(surfaceConfig = SurfaceRerankingConfig(weight = 0.0)) {
            name -> File(root, name).inputStream()
        }.use { baseline ->
            for (line in evaluation) {
                val (reading, expected) = line.split('\t')
                val accepted = expected.split('|')
                val start = System.nanoTime()
                val old = baseline.convertCandidates(reading, 20)
                val middle = System.nanoTime()
                val new = converter.convertCandidates(reading, 20)
                val finish = System.nanoTime()
                val oldRank = old.indexOfFirst { it.text in accepted }.let { if (it < 0) 0 else it + 1 }
                val newRank = new.indexOfFirst { it.text in accepted }.let { if (it < 0) 0 else it + 1 }
                if (oldRank == 1) before++
                if (newRank == 1) after++
                if (oldRank == 1 && newRank != 1) regressions += reading
                rows += listOf(reading, expected, old.first().text, new.first().text, oldRank, newRank,
                    (middle - start) / 1_000_000.0, (finish - middle) / 1_000_000.0).joinToString("\t")
            }
        }
        File("build/reports/conversion-ranking.tsv").apply { parentFile?.mkdirs(); writeText(rows.joinToString("\n") + "\n") }
        println("First-candidate matches: dictionary=$before context=$after total=${evaluation.size}")
        assertTrue("Context model should improve the evaluation: $before -> $after", after > before)
        assertTrue("Previously correct first candidates regressed: $regressions", regressions.isEmpty())
    }

    @Test
    fun surfaceScoringKeepsCostBreakdownAndCanRescueBeyondTheOldThreeCandidateLimit() {
        for (reading in listOf("ともだちにあう", "きをきる", "かわをむく")) {
            val candidates = converter.convertCandidates(reading, 3)
            for (candidate in candidates) {
                assertEquals(candidate.cost, candidate.dictionaryCost + candidate.contextAdjustment, 1e-9)
                assertEquals(candidate.dictionaryCost, candidate.wordCost + candidate.connectionCost, 1e-9)
                assertTrue(kotlin.math.abs(candidate.contextAdjustment) <= SurfaceRerankingConfig().maxAdjustment)
            }
        }
        assertEquals("木を切る", converter.convert("きをきる", 1).single())
        assertEquals("友達に会う", converter.convert("ともだちにあう", 1).single())
        println("Cost breakdown: ${converter.convertCandidates("ともだちにあう", 20).filter { it.text in setOf("友達に会う", "友達に合う") }}")
    }

    @Test
    fun dictionaryProducesRankedJapaneseConversion() {
        val candidates = converter.convert("にほんご", 8)
        assertEquals("日本語", candidates.first())
        assertEquals(candidates.distinct(), candidates)
        assertTrue(candidates.size <= 8)
    }

    @Test
    fun scoredConversionsPreserveRankingCostsAndCacheResults() {
        val candidates = converter.convertCandidates("にほんご", 8)
        assertEquals("日本語", candidates.first().text)
        assertEquals(converter.convert("にほんご", 8), candidates.map { it.text })
        assertTrue(candidates.all { it.cost.isFinite() })
        assertTrue(candidates.zipWithNext().all { (first, second) -> first.cost <= second.cost })
        assertEquals(candidates, converter.convertCandidates("ニホンゴ", 8))
        assertEquals(candidates.take(3), converter.convertCandidates("にほんご", 3))
    }

    @Test
    fun convertsMultipleWordsAndPreservesKana() {
        val candidates = converter.convert("きょうはいいてんきです", 8)
        assertTrue(candidates.toString(), candidates.any { "今日" in it && "天気" in it })
        assertTrue(candidates.all { !it.contains("BOS") && !it.contains("EOS") })
    }

    @Test
    fun limitsAndUnknownReadingsAreHandled() {
        assertTrue(converter.convert("にほんご", 0).isEmpty())
        assertTrue(converter.convert("", 5).isEmpty())
        assertEquals(1, converter.convert("にほんご", 1).size)
        assertTrue(converter.convert("ゔゔゔ", 2).isNotEmpty())
    }

    @Test
    fun convertsKnownWordsAroundUnknownKana() {
        val unknown = "ゕゖゕ"
        val cases = listOf(
            "${unknown}にほんご" to "${unknown}日本語",
            "にほんご${unknown}" to "日本語${unknown}",
            "にほんご${unknown}てんき" to "日本語${unknown}天気",
        )
        for ((reading, expected) in cases) {
            assertTrue(reading, converter.readingLexicon.newSession()
                .evaluate(reading, complete = true).unknownCharacters > 0)
            val candidates = converter.convert(reading, 3)
            assertTrue("$reading -> $candidates", expected in candidates)
            assertEquals(candidates.distinct(), candidates)
            assertTrue(candidates.size <= 3)
        }
        assertEquals(listOf(unknown), converter.convert(unknown, 3))
    }

    @Test
    fun contextUsesReadingsInsteadOfPronunciation() {
        assertEquals("わたしはとうきょうへいきます。", converter.readingOf("私は東京へ行きます。"))
        assertEquals("こーひー", converter.readingOf("コーヒー"))
        assertEquals("", converter.readingOf(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonKanaConversionInput() {
        converter.convert("東京", 5)
    }

    @Test
    fun corruptDictionaryFailsLoudly() {
        assertThrows(Exception::class.java) {
            SumireKanaKanjiConverter.fromAssets { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        }
    }

    companion object {
        private val converter by lazy {
            val root = listOf(File("src/main/assets/conversion"),
                File("app/src/main/assets/conversion")).first { it.isDirectory }
            SumireKanaKanjiConverter.fromAssets { name -> File(root, name).inputStream() }
        }

        @JvmStatic @AfterClass fun release() = converter.close()
    }
}
