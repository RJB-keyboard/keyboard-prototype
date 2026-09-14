package net.ramdos.keyboard_prototype.engine.lm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class HiraganaTokenizerTest {
    private val tokenizer = HiraganaTokenizer(listOf('ん', 'あ', 'ゔ', 'ー').map { it.code })

    @Test
    fun preservesModelOrderAndSevenSpecialTokenOffset() {
        assertArrayEquals(longArrayOf(8, 7, 9, 10), tokenizer.encode("", "あんゔー", 100))
    }

    @Test
    fun nonemptyInputUsesNoExtraSpecialTokensAndEmptyUsesCls() {
        assertArrayEquals(longArrayOf(8), tokenizer.encode("", "あ", 100))
        assertArrayEquals(longArrayOf(0), tokenizer.encode("", "", 100))
    }

    @Test
    fun keepsMostRecentTokensIncludingPrefix() {
        assertArrayEquals(longArrayOf(7, 8, 9), tokenizer.encode("あああん", "あゔ", 3))
    }

    @Test
    fun normalizesKatakanaAndDecomposedVoicing() {
        assertArrayEquals(longArrayOf(8, 9, 9, 7, 10), tokenizer.encode("アヴ", "ｳﾞﾝｰ", 100))
        assertEquals("がゔー", HiraganaTokenizer.normalize("カ\u3099ヴｰ"))
    }

    @Test
    fun unsupportedCodePointUsesSingleUnknownToken() {
        assertArrayEquals(longArrayOf(6, 6, 8), tokenizer.encode("漢😀", "あ", 100))
    }

    @Test
    fun softmaxIsStableAndConditionsOnKanaOnly() {
        val logits = FloatArray(11) { 100_000f }
        logits[7] = 1001f
        logits[8] = 1000f
        logits[9] = 999f
        logits[10] = 998f
        val result = tokenizer.logProbabilities(logits)
        assertEquals(1.0, result.values.sumOf(::exp), 1e-12)
        assertEquals(1.0, result.getValue('ん') - result.getValue('あ'), 1e-12)
        assertTrue(result.values.all { it.isFinite() && it <= 0.0 })
        assertFalse(result.containsKey('漢'))
        assertTrue(result.containsKey('ー'))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongVocabularySize() {
        tokenizer.logProbabilities(FloatArray(94))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteOutput() {
        tokenizer.logProbabilities(FloatArray(11) { Float.NaN })
    }
}
