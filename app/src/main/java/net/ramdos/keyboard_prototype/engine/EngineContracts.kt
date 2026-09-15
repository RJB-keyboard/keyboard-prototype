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
    fun convertCandidates(reading: String, limit: Int): List<ConversionCandidate>
    fun convert(reading: String, limit: Int): List<String> =
        convertCandidates(reading, limit).map { it.text }
    fun readingOf(text: String): String
    override fun close() {}
}

/** Lower is better; combined cost in the same scale as [LexiconScore.cost].
 * Dictionary cost includes word and BOS/EOS connections; contextAdjustment reranks surfaces.
 * Costs may be negative and are not probabilities. Component costs support ranking diagnostics.
 */
data class ConversionCandidate(
    val text: String,
    val cost: Double,
    val dictionaryCost: Double = cost,
    val contextAdjustment: Double = 0.0,
    val wordCost: Double = dictionaryCost,
    val connectionCost: Double = 0.0,
    /** Absolute mean surface-model loss, comparable across readings; null when unavailable. */
    val surfaceCost: Double? = null,
) {
    init {
        require(listOf(cost, dictionaryCost, contextAdjustment, wordCost, connectionCost).all { it.isFinite() })
        require(surfaceCost == null || surfaceCost.isFinite() && surfaceCost >= 0.0)
    }
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
internal fun Char.isKanaReadingCharacter(): Boolean = this in '\u3041'..'\u3096' || this == 'ー'
