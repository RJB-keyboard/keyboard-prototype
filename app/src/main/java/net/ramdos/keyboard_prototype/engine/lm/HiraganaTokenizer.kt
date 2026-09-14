package net.ramdos.keyboard_prototype.engine.lm

import java.text.Normalizer
import net.ramdos.keyboard_prototype.engine.isKanaReadingCharacter
import kotlin.math.exp
import kotlin.math.ln

/** CharacterTokenizer from the model card. char_ords order is part of the model, not Unicode order. */
class HiraganaTokenizer(charOrds: List<Int>, val modelMaxLength: Int = 1024) {
    private val tokenIds: Map<Char, Int>
    private val kanaTokenIds: Map<Char, Int>
    val vocabularySize: Int = charOrds.size + SPECIAL_TOKEN_COUNT

    init {
        require(modelMaxLength > 0)
        require(charOrds.isNotEmpty() && charOrds.distinct().size == charOrds.size)
        require(charOrds.all { it in 0..0xffff && it !in 0xd800..0xdfff })
        tokenIds = charOrds.mapIndexed { index, code -> code.toChar() to index + SPECIAL_TOKEN_COUNT }.toMap()
        kanaTokenIds = tokenIds.filterKeys { it.isKanaReadingCharacter() }
        require(kanaTokenIds.isNotEmpty())
    }

    /** Never prepend/append special tokens to nonempty text, matching upstream generation. */
    fun encode(context: String, prefix: String, maxTokens: Int): LongArray {
        require(maxTokens in 1..modelMaxLength)
        val text = normalize(context) + normalize(prefix)
        if (text.isEmpty()) return longArrayOf(CLS_TOKEN_ID.toLong())
        // Iterate code points so an emoji is one UNK, not two UTF-16 surrogate UNKs.
        val tokens = ArrayList<Long>(text.length)
        var index = 0
        while (index < text.length) {
            val point = Character.codePointAt(text, index)
            tokens += if (point <= 0xffff) (tokenIds[point.toChar()] ?: UNK_TOKEN_ID).toLong()
                else UNK_TOKEN_ID.toLong()
            index += Character.charCount(point)
        }
        return tokens.takeLast(maxTokens).toLongArray()
    }

    /** P(next character | context, prefix, next is kana), in natural-log space. */
    fun logProbabilities(logits: FloatArray): Map<Char, Double> {
        require(logits.size == vocabularySize) { "Model/tokenizer vocabulary mismatch: ${logits.size} != $vocabularySize" }
        val maximum = kanaTokenIds.values.maxOf { logits[it].toDouble() }
        require(maximum.isFinite() && kanaTokenIds.values.all { logits[it].isFinite() }) {
            "Language model returned non-finite kana logits"
        }
        val normalizer = maximum + ln(kanaTokenIds.values.sumOf { exp(logits[it] - maximum) })
        return kanaTokenIds.mapValues { (_, id) -> logits[id] - normalizer }
    }

    companion object {
        const val SPECIAL_TOKEN_COUNT = 7
        const val CLS_TOKEN_ID = 0
        const val PAD_TOKEN_ID = 4
        const val UNK_TOKEN_ID = 6

        /** Width/voicing normalization followed by katakana-to-hiragana conversion. */
        fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).map { char ->
            if (char in '\u30a1'..'\u30f6') (char.code - 0x60).toChar() else char
        }.joinToString("")
    }
}
