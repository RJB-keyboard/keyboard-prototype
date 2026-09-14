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
            override fun convert(reading: String, limit: Int): List<String> = error("Unknown kana should stay literal")
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
            override fun convert(reading: String, limit: Int): List<String> {
                assertEquals("あ", reading)
                return listOf("亜", "阿", "亜")
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
}
