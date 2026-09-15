package net.ramdos.keyboard_prototype.engine.lm

import org.junit.Assert.*
import org.junit.Test

class RepeatedKanaInputTest {
    private val tokenizer = HiraganaTokenizer(('ぁ'..'ゖ').map { it.code })

    @Test
    fun everyRequestedPositionMatchesItsIndependentTokenInput() {
        for (context in listOf("", "私は😀", "あ".repeat(29), "あ".repeat(31), "あ".repeat(40))) {
            for (prefix in listOf("ま", "ぱ", "ゃ", "ｳﾞ", "か\u3099", "あいう")) {
                val input = tokenizer.repeatedKanaInput(context, prefix, 32, 7)
                input.predictionPositions.forEachIndexed { index, position ->
                    assertArrayEquals(tokenizer.encode(context, prefix + prefix.last().toString().repeat(index), 32),
                        input.tokens.copyOfRange(0, position + 1))
                }
                assertTrue(input.tokens.size <= 32)
                assertTrue(input.predictionPositions.size in 1..7)
            }
        }
    }

    @Test
    fun stopsExactlyBeforeContextTruncationAndPositionReset() {
        assertEquals(3, tokenizer.repeatedKanaInput("あ".repeat(29), "ま", 32, 7).predictionPositions.size)
        assertEquals(1, tokenizer.repeatedKanaInput("あ".repeat(31), "ま", 32, 7).predictionPositions.size)
        assertEquals(1, tokenizer.repeatedKanaInput("あ".repeat(40), "ま", 32, 7).predictionPositions.size)
    }

    @Test
    fun emptyPrefixKeepsOriginalClsForOrdinaryInference() {
        val input = tokenizer.repeatedKanaInput("", "", 32, 1)
        assertArrayEquals(longArrayOf(HiraganaTokenizer.CLS_TOKEN_ID.toLong()), input.tokens)
        assertEquals(listOf(0), input.predictionPositions)
    }
}
