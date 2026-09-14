package net.ramdos.keyboard_prototype.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

data class GlideDecoderConfig(
    val maxBeamWidth: Int = 16,
    val maxCandidates: Int = 8,
    val maxReadingLength: Int = 32,
    val strokeWeight: Double = 1.0,
    val languageWeight: Double = 0.35,
    val characterInsertionBonus: Double = 0.0,
    val maxDecodeMillis: Long = 5000,
    val dictionaryWeight: Double = 1.0,
    val unknownBeamSlots: Int = 2,
) {
    init {
        require(maxBeamWidth in 1..128)
        require(maxCandidates in 1..maxBeamWidth)
        require(maxReadingLength in 1..128)
        require(strokeWeight.isFinite() && strokeWeight > 0.0 && strokeWeight <= 100.0)
        require(languageWeight.isFinite() && languageWeight in 0.0..100.0)
        require(characterInsertionBonus.isFinite() && characterInsertionBonus in -10.0..10.0)
        require(maxDecodeMillis in 1..120_000)
        require(dictionaryWeight.isFinite() && dictionaryWeight in 0.0..100.0)
        require(unknownBeamSlots in 0..128)
    }
}

/**
 * Logs use base e. A is marginalized over surviving alignments and B is the
 * product of next-character probabilities (no EOS score). [posterior] is a
 * softmax of [score] over returned candidates only: a truncated beam estimate,
 * not calibrated confidence or the full probability mass of all readings.
 * When languageWeight is zero, B is not evaluated and its log score is zero.
 * Optional characterInsertionBonus adds a per-character ranking reward; it is
 * zero by default. Tune it with languageWeight on held-out gestures because
 * multiplying next-character probabilities intrinsically favors shorter text.
 * dictionaryCost is an additional ranking feature, not a probability. When a
 * lexicon is enabled, posterior includes its weighted cost and remains a beam estimate.
 */
data class ReadingCandidate(
    val reading: String,
    val strokeLogProbability: Double,
    val languageLogProbability: Double,
    val score: Double,
    val posterior: Double,
    val dictionaryCost: Double = 0.0,
    val unknownCharacters: Int = 0,
)

class GlideDecodeTimeoutException : RuntimeException("Glide decoding exceeded its time budget")

/** Incremental product-of-experts beam search, without Android dependencies. */
class GlideDecoder(
    private val strokeModel: StrokeModel = StrokeModel(),
    private val languageModel: KanaLanguageModel,
    private val config: GlideDecoderConfig = GlideDecoderConfig(),
    private val readingLexicon: ReadingLexicon? = null,
) {
    private data class Hypothesis(
        val reading: String,
        val stroke: Double,
        val language: Double,
        val dictionary: LexiconScore = LexiconScore(0.0),
    )

    fun decode(trace: GlideTrace, context: String = "", limit: Int = config.maxCandidates): List<ReadingCandidate> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val started = System.nanoTime()
        fun checkBudget() {
            checkInterrupted()
            if ((System.nanoTime() - started) / 1_000_000 >= config.maxDecodeMillis) throw GlideDecodeTimeoutException()
        }
        checkBudget()
        val lattice = strokeModel.lattice(trace)
        if (lattice.events.isEmpty()) return emptyList()
        // Local to one call: no stale context, unbounded lifetime cache, or cross-thread state.
        val languageCache = HashMap<String, Map<Char, Double>>()
        val lexicon = if (config.dictionaryWeight > 0.0) readingLexicon?.newSession() else null
        var beam = listOf(Hypothesis("", 0.0, 0.0))
        lattice.events.forEachIndexed { eventIndex, event ->
            checkBudget()
            if (config.languageWeight > 0.0) {
                val missing = beam.asSequence().map { it.reading }.filter { it.length < config.maxReadingLength && it !in languageCache }.distinct().toList()
                if (missing.isNotEmpty()) {
                    val probabilities = languageModel.nextLogProbabilities(context, missing)
                    checkBudget()
                    require(probabilities.size == missing.size) { "Language model returned the wrong batch size" }
                    missing.zip(probabilities).forEach { (prefix, distribution) ->
                        require(distribution.all { (kana, value) -> kana.isHiraganaLetter() && !value.isNaN() && value <= 0.000001 }) {
                            "Language model must return hiragana natural log probabilities"
                        }
                        languageCache[prefix] = distribution.mapValues { minOf(0.0, it.value) }
                    }
                }
            }
            val next = HashMap<String, Hypothesis>()
            fun offer(hypothesis: Hypothesis) {
                val previous = next[hypothesis.reading]
                next[hypothesis.reading] = if (previous == null) hypothesis else
                    hypothesis.copy(stroke = logAdd(previous.stroke, hypothesis.stroke))
            }
            beam.forEach { hypothesis ->
                if (event.skipLogProbability.isFinite()) offer(hypothesis.copy(stroke = hypothesis.stroke + event.skipLogProbability))
                if (hypothesis.reading.length < config.maxReadingLength) {
                    event.characterLogProbabilities.forEach { (kana, strokeLogProbability) ->
                        val languageProbability = if (config.languageWeight == 0.0) 0.0 else languageCache.getValue(hypothesis.reading)[kana] ?: Double.NEGATIVE_INFINITY
                        if (languageProbability.isFinite() && strokeLogProbability.isFinite()) {
                            offer(Hypothesis(hypothesis.reading + kana, hypothesis.stroke + strokeLogProbability, hypothesis.language + languageProbability))
                        }
                    }
                }
            }
            // Evaluate total prefix cost once, after alignment paths have been merged.
            // At release, incomplete dictionary prefixes must pay their completion/unknown cost
            // BEFORE pruning, otherwise a promising complete word can be lost to a fragment.
            val evaluated = if (lexicon == null) next.values.toList() else next.values.map {
                checkBudget()
                val dictionary = lexicon.evaluate(it.reading, complete = eventIndex == lattice.events.lastIndex)
                require(dictionary.cost.isFinite() && dictionary.unknownCharacters in 0..it.reading.length) {
                    "Dictionary must return finite costs and valid unknown-character counts"
                }
                it.copy(dictionary = dictionary)
            }
            beam = selectBeam(evaluated, config.maxBeamWidth, if (lexicon == null) 0 else config.unknownBeamSlots)
            if (beam.isEmpty()) return emptyList()
        }
        checkBudget()
        val finalLimit = minOf(limit, config.maxCandidates)
        // Reserve one alternative for names/new words even when the lexicon disfavors it.
        val final = selectBeam(beam.filter { it.reading.isNotEmpty() }, finalLimit,
            if (lexicon != null && config.unknownBeamSlots > 0 && finalLimit > 1) 1 else 0)
        if (final.isEmpty()) return emptyList()
        val maxScore = score(final.first())
        val total = final.sumOf { exp(score(it) - maxScore) }
        return final.map {
            val score = score(it)
            ReadingCandidate(it.reading, it.stroke, it.language, score, exp(score - maxScore) / total,
                it.dictionary.cost, it.dictionary.unknownCharacters)
        }
    }

    private fun baseScore(hypothesis: Hypothesis): Double = config.strokeWeight * hypothesis.stroke + config.languageWeight * hypothesis.language + config.characterInsertionBonus * hypothesis.reading.length

    private fun score(hypothesis: Hypothesis): Double = baseScore(hypothesis) - config.dictionaryWeight * hypothesis.dictionary.cost

    private fun selectBeam(candidates: List<Hypothesis>, width: Int, reserved: Int): List<Hypothesis> {
        val ranked = candidates.sortedWith(compareByDescending<Hypothesis> { score(it) }.thenBy { it.reading })
        if (reserved == 0 || ranked.size <= width) return ranked.take(width)
        val selected = ranked.take((width - reserved).coerceAtLeast(1)).associateByTo(linkedMapOf()) { it.reading }
        candidates.sortedWith(compareByDescending<Hypothesis> { baseScore(it) }.thenBy { it.reading }).forEach {
            if (selected.size < width) selected.putIfAbsent(it.reading, it)
        }
        return selected.values.sortedWith(compareByDescending<Hypothesis> { score(it) }.thenBy { it.reading })
    }

    private fun logAdd(a: Double, b: Double): Double {
        val higher = max(a, b)
        return higher + ln(exp(a - higher) + exp(b - higher))
    }
}
