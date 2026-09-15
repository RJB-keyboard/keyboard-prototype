package net.ramdos.keyboard_prototype.engine

import net.ramdos.keyboard_prototype.ui.GojuonLayout
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class RepeatedLanguageLookaheadTest {
    @Test
    fun preservesAllCandidatesScoresAndRepetitionWithFewerCalls() {
        for (keys in listOf("ま", "は", "こまよ", "こんにちは")) {
            for (repeatLimit in listOf(1, 3, 8)) {
                for (readingLimit in listOf(2, 32)) {
                    val config = GlideDecoderConfig(maxRepeatedCharactersPerEvent = repeatLimit, maxReadingLength = readingLimit)
                    val baseline = Model(false)
                    val optimized = Model(true)
                    val before = GlideDecoder(languageModel = baseline, config = config).decode(trace(keys), "文脈")
                    val after = GlideDecoder(languageModel = optimized, config = config).decode(trace(keys), "文脈")
                    assertEquals(before, after)
                    assertTrue(optimized.calls <= baseline.calls)
                    if (keys == "ま" && repeatLimit == 3 && readingLimit == 32) {
                        assertEquals(3, baseline.calls)
                        assertEquals(2, optimized.calls)
                        assertEquals("ま", after.first().reading)
                        assertTrue(after.any { it.reading == "ままま" })
                    }
                }
            }
        }
    }

    @Test
    fun lookaheadNeverReusesAnotherRequestsContext() {
        val optimized = Model(true)
        val decoder = GlideDecoder(languageModel = optimized)
        for (context in listOf("one", "two", "one")) {
            val expected = GlideDecoder(languageModel = Model(false)).decode(trace("こまよ"), context)
            assertEquals(expected, decoder.decode(trace("こまよ"), context))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingLookaheadDistributions() {
        val model = object : KanaLanguageModel {
            override fun nextLogProbabilities(context: String, prefixes: List<String>) = prefixes.map { mapOf('ま' to 0.0) }
            override fun nextRepeatedLogProbabilities(context: String, prefixes: List<String>, maxPredictions: Int) =
                prefixes.map { emptyList<Map<Char, Double>>() }
        }
        GlideDecoder(languageModel = model).decode(trace("ま"))
    }

    private class Model(private val lookahead: Boolean) : KanaLanguageModel {
        var calls = 0
        override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> {
            calls++
            return prefixes.map { distribution(context, it) }
        }
        override fun nextRepeatedLogProbabilities(context: String, prefixes: List<String>, maxPredictions: Int): List<List<Map<Char, Double>>> {
            if (!lookahead) return super.nextRepeatedLogProbabilities(context, prefixes, maxPredictions)
            calls++
            return prefixes.map { prefix ->
                List(maxPredictions) { distribution(context, prefix + prefix.last().toString().repeat(it)) }
            }
        }
        private fun distribution(context: String, prefix: String): Map<Char, Double> {
            val weights = ('ぁ'..'ゖ').associateWith { 1.0 + Math.floorMod(context.hashCode() + prefix.hashCode() + it.code, 7) }
            val total = weights.values.sum()
            return weights.mapValues { ln(it.value / total) }
        }
    }

    private fun trace(keys: String) = GlideTrace(keys.flatMapIndexed { index, kana ->
        val key = GojuonLayout.keys.single { it.kana == kana.toString() }
        val x = (key.left + key.right) / 2
        val y = (key.top + key.bottom) / 2
        listOf(TracePoint(x, y, index * 300L), TracePoint(x, y, index * 300L + 180))
    }, GojuonLayout.keys)
}
