package net.ramdos.keyboard_prototype.engine

import kotlin.math.exp

/** Pipeline: geometry + next-kana LM -> readings -> dictionary-based conversion. */
class GlideCandidateEngine(
    private val languageModel: KanaLanguageModel,
    private val converter: KanaKanjiConverter,
    config: GlideDecoderConfig = GlideDecoderConfig(),
    private val maxCandidates: Int = 12,
    private val conversionCostWeight: Double = 1.0,
    private val readingSurfaceWeight: Double = 0.5,
    private val maxReadingAdjustment: Double = 0.75,
    private val readingDiversityWindow: Double = 2.0,
) : CandidateEngine {
    private val decoder = GlideDecoder(languageModel = languageModel, config = config,
        readingLexicon = converter.readingLexicon)

    init {
        require(maxCandidates > 0)
        require(conversionCostWeight.isFinite() && conversionCostWeight in 0.0..100.0)
        require(readingSurfaceWeight.isFinite() && readingSurfaceWeight in 0.0..10.0)
        require(maxReadingAdjustment.isFinite() && maxReadingAdjustment in 0.0..5.0)
        require(readingDiversityWindow.isFinite() && readingDiversityWindow in 0.0..10.0)
    }

    override fun generateCandidates(trace: GlideTrace): List<String> = generateCandidates(trace, "")

    override fun generateCandidates(trace: GlideTrace, precedingText: String): List<String> =
        generateScoredCandidates(trace, precedingText).map { it.text }

    override fun generateScoredCandidates(trace: GlideTrace, precedingText: String): List<DisplayCandidate> {
        checkCancellation()
        val context = if (precedingText.isEmpty()) "" else converter.readingOf(precedingText.takeLast(256))
        return rankScoredCandidates(decoder.decode(trace, context))
    }

    private data class RankedCandidate(val text: String, val score: Double, val readingIndex: Int, val variantIndex: Int)

    /** Rank already decoded readings without rerunning the gesture or language model. */
    internal fun rankCandidates(readings: List<ReadingCandidate>): List<String> =
        rankScoredCandidates(readings).map { it.text }

    internal fun rankScoredCandidates(readings: List<ReadingCandidate>): List<DisplayCandidate> {
        checkCancellation()
        if (readings.isEmpty()) return emptyList()
        val conversions = readings.map { candidate ->
            checkCancellation()
            // Convert known spans even when the reading contains unknown kana; retain the literal below.
            converter.convertCandidates(candidate.reading, 3).filter { it.text.isNotBlank() }
        }
        // Compare absolute surface evidence, never the per-reading anchored contextAdjustment.
        // Penalize relative to the best observed loss, bounded so strong gesture evidence wins.
        val surfaceCosts = conversions.map { it.firstOrNull()?.surfaceCost }
        val bestSurfaceCost = surfaceCosts.filterNotNull().minOrNull()
        val choices = readings.flatMapIndexed { readingIndex, candidate ->
            val converted = conversions[readingIndex]
            val surfaceCost = surfaceCosts[readingIndex]
            val readingPenalty = if (surfaceCost == null || bestSurfaceCost == null) 0.0 else
                (readingSurfaceWeight * (surfaceCost - bestSurfaceCost)).coerceAtMost(maxReadingAdjustment)
            val readingScore = candidate.score - readingPenalty
            val bestCost = converted.minOfOrNull { it.cost } ?: 0.0
            // The reading score already includes dictionary evidence. Only charge the gap
            // from this reading's best surface, not the whole dictionary cost a second time.
            converted.mapIndexed { variantIndex, variant ->
                RankedCandidate(variant.text,
                    readingScore - conversionCostWeight * (variant.cost - bestCost),
                    readingIndex, variantIndex)
            } + RankedCandidate(candidate.reading, readingScore, readingIndex, converted.size)
        }
        val ordered = choices.sortedWith(compareByDescending<RankedCandidate> { it.score }
            .thenBy { it.readingIndex }.thenBy { it.variantIndex }.thenBy { it.text })
        // Deduplicate AFTER ranking: a shared surface keeps its strongest reading's score.
        val ranked = ordered.distinctBy { it.text }
        // Normalize before the display limit or diversity ordering so neither changes confidence.
        // These heuristic scores provide relative support, not measured correctness probabilities.
        val weights = ranked.associate { it.text to exp(it.score - ranked.first().score) }
        val totalWeight = weights.values.sum()
        val selected = linkedSetOf(ranked.first().text)
        fun reserve(text: String?) {
            if (text != null && selected.size < maxCandidates) selected += text
        }
        reserve(readings.first().reading)
        // Omitted repeats must remain selectable as kana even if their conversion is poor.
        readings.filter { it.hasImplicitRepetition }.forEach { reserve(it.reading) }
        // Preserve one distinct alternative reading when there is room, even outside
        // the competitive preview window. The preview pass below determines display order.
        reserve(ordered.firstOrNull { it.readingIndex != ranked.first().readingIndex && it.text !in selected }?.text)
        // Use up to three leading slots for competitive interpretations, before showing
        // several spellings of one reading. Weak readings cannot displace strong variants.
        val previews = ranked.filter { ranked.first().score - it.score <= readingDiversityWindow }
            .distinctBy { it.readingIndex }.take(3)
        previews.forEach { reserve(it.text) }
        ranked.forEach { reserve(it.text) }
        val leading = previews.filter { it.text in selected }
        return (leading + ranked.filter { it.text in selected && it !in leading }).map {
            DisplayCandidate(it.text, weights.getValue(it.text) / totalWeight)
        }
    }

    override fun close() {
        try { languageModel.close() } finally { converter.close() }
    }

    override fun cancelPendingInference() = languageModel.cancelPendingInference()

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Candidate generation cancelled")
    }
}
