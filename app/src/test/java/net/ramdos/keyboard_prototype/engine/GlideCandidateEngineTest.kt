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
            override fun convert(reading: String, limit: Int): List<String> {
                assertEquals(expectedReading, reading)
                assertEquals(3, limit)
                conversions++
                return converted
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
