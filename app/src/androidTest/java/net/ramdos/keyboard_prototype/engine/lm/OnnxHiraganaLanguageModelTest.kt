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
    fun actualModelReturnsNormalizedContextSensitiveKanaDistribution() {
        val first = model.nextLogProbabilities("きょうのてんきは", listOf("")).single()
        val second = model.nextLogProbabilities("にほんのしゅとは", listOf("")).single()
        assertEquals(86, first.size)
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
        } finally {
            Thread.interrupted()
        }
        model.cancelPendingInference()
        assertTrue(model.nextLogProbabilities("", listOf("")).single().isNotEmpty())
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
