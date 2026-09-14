package net.ramdos.keyboard_prototype.engine

/**
 * A character language model. Each result is log P(next kana | context + prefix),
 * in the same order as [prefixes]. Values are natural logarithms, normalized over
 * the supported hiragana alphabet; an absent character is impossible.
 *
 * Implementations must support interruption and bound context and batch memory.
 * Neither this interface nor its implementations interpret gesture geometry.
 */
interface KanaLanguageModel : AutoCloseable {
    fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>>
    /** May be called concurrently to stop native work after a gesture is superseded. */
    fun cancelPendingInference() {}
    override fun close() {}
}

/** Dictionary/statistical kana-kanji conversion; this stage does not use an LLM. */
interface KanaKanjiConverter : AutoCloseable {
    /** Optional dictionary shared with reading search; no additional model is loaded. */
    val readingLexicon: ReadingLexicon? get() = null
    fun convert(reading: String, limit: Int): List<String>
    fun readingOf(text: String): String
    override fun close() {}
}

/** Creates request-local dictionary scoring state. A remains independent of this lexicon. */
interface ReadingLexicon {
    fun newSession(): ReadingLexiconSession
}

interface ReadingLexiconSession {
    /** Partial prefixes may end inside a word; complete readings must account for the ending. */
    fun evaluate(reading: String, complete: Boolean): LexiconScore
}

/** Lower is better. Dictionary costs are ranking features, not log probabilities. */
data class LexiconScore(val cost: Double, val unknownCharacters: Int = 0)

/** Modern and historical hiragana letters, excluding combining dakuten marks. */
internal fun Char.isHiraganaLetter(): Boolean = this in '\u3041'..'\u3096'
