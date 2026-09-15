package net.ramdos.keyboard_prototype.engine.lm

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.exp

/** Requires the actual pinned assets; deliberately fails rather than skipping a missing model. */
class OnnxHiraganaLanguageModelTest {
    @Test
    fun repeatedLookaheadMatchesIndependentInferenceIncludingWindowBoundaries() {
        for (context in listOf("", "わたしは", "あ".repeat(29), "あ".repeat(31), "あ".repeat(40))) {
            val prefixes = listOf("ま", "ぱ", "ゃ", "こんにちは", "ゔ")
            val batch = model.nextRepeatedLogProbabilities(context, prefixes, 3)
            prefixes.forEachIndexed { row, prefix ->
                assertTrue(batch[row].size in 1..3)
                batch[row].forEachIndexed { step, distribution ->
                    val independent = model.nextLogProbabilities(context,
                        listOf(prefix + prefix.last().toString().repeat(step))).single()
                    independent.forEach { (kana, value) ->
                        assertEquals("contextLength=${context.length}, prefix=$prefix, step=$step, kana=$kana",
                            value, distribution.getValue(kana), 2e-4)
                    }
                }
                if (context.length >= 31) assertEquals(1, batch[row].size)
            }
        }
    }

    @Test
    fun actualModelReturnsNormalizedContextSensitiveKanaDistribution() {
        val first = model.nextLogProbabilities("きょうのてんきは", listOf("")).single()
        val second = model.nextLogProbabilities("にほんのしゅとは", listOf("")).single()
        assertEquals(87, first.size)
        assertEquals(1.0, first.values.sumOf(::exp), 1e-8)
        assertTrue(first.values.all { it.isFinite() && it <= 0.0 })
        assertTrue(first.keys.any { abs(first.getValue(it) - second.getValue(it)) > 1e-3 })
    }

    @Test
    fun paddedBatchMatchesIndependentInferenceAndPreservesOrder() {
        val prefixes = listOf("", "あ", "あした", "ん", "こんにちは")
        val batch = model.nextLogProbabilities("わたしは", prefixes)
        prefixes.forEachIndexed { index, prefix ->
            val single = model.nextLogProbabilities("わたしは", listOf(prefix)).single()
            single.forEach { (char, value) -> assertEquals("prefix=$prefix char=$char", value, batch[index].getValue(char), 2e-4) }
        }
    }

    @Test
    fun boundedContextMatchesExplicitSuffix() {
        val longContext = "あいうえお".repeat(20)
        val bounded = model.nextLogProbabilities(longContext, listOf("かき")).single()
        val explicit = model.nextLogProbabilities((longContext + "かき").takeLast(32), listOf("")).single()
        bounded.forEach { (char, value) -> assertEquals(value, explicit.getValue(char), 2e-4) }
    }

    @Test
    fun interruptionCancelsAndNextRequestStillWorks() {
        try {
            Thread.currentThread().interrupt()
            val error = runCatching { model.nextLogProbabilities("", listOf("あ")) }.exceptionOrNull()
            assertTrue(error is CancellationException)
            val lookaheadError = runCatching { model.nextRepeatedLogProbabilities("", listOf("ま"), 3) }.exceptionOrNull()
            assertTrue(lookaheadError is CancellationException)
        } finally {
            Thread.interrupted()
        }
        model.cancelPendingInference()
        assertTrue(model.nextLogProbabilities("", listOf("")).single().isNotEmpty())
        assertEquals(2, model.nextRepeatedLogProbabilities("", listOf("ま"), 2).single().size)
    }

    companion object {
        private lateinit var model: OnnxHiraganaLanguageModel

        @JvmStatic
        @BeforeClass
        fun openModel() {
            model = OnnxHiraganaLanguageModel.open(
                InstrumentationRegistry.getInstrumentation().targetContext,
                maxContextTokens = 32,
                maxBatchSize = 4,
            )
        }

        @JvmStatic
        @AfterClass
        fun closeModel() {
            if (::model.isInitialized) model.close()
        }
    }
}
