package net.ramdos.keyboard_prototype.engine

/** Pipeline: geometry + next-kana LM -> readings -> dictionary-based conversion. */
class GlideCandidateEngine(
    private val languageModel: KanaLanguageModel,
    private val converter: KanaKanjiConverter,
    config: GlideDecoderConfig = GlideDecoderConfig(),
    private val maxCandidates: Int = 12,
) : CandidateEngine {
    private val decoder = GlideDecoder(languageModel = languageModel, config = config)

    init { require(maxCandidates > 0) }

    override fun generateCandidates(trace: GlideTrace): List<String> = generateCandidates(trace, "")

    override fun generateCandidates(trace: GlideTrace, precedingText: String): List<String> {
        checkCancellation()
        val context = if (precedingText.isEmpty()) "" else converter.readingOf(precedingText.takeLast(256))
        val readings = decoder.decode(trace, context)
        val choices = readings.map { candidate ->
            checkCancellation()
            (converter.convert(candidate.reading, 3).filter { it.isNotBlank() } + candidate.reading).distinct()
        }
        // Keep several interpretations accessible; preserve the converter's own ranking.
        val result = linkedSetOf<String>()
        choices.take(if (maxCandidates > 1) maxCandidates - 1 else 1).forEach {
            it.firstOrNull()?.let(result::add)
        }
        // Reserve space for the best literal reading even if conversion has many alternatives.
        if (result.size < maxCandidates) readings.firstOrNull()?.reading?.let(result::add)
        if (result.size == maxCandidates) return result.toList()
        for (rank in 1 until (choices.maxOfOrNull { it.size } ?: 0)) {
            for (variants in choices) {
                variants.getOrNull(rank)?.let(result::add)
                if (result.size == maxCandidates) return result.toList()
            }
        }
        return result.toList()
    }

    override fun close() {
        try { languageModel.close() } finally { converter.close() }
    }

    override fun cancelPendingInference() = languageModel.cancelPendingInference()

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Candidate generation cancelled")
    }
}
