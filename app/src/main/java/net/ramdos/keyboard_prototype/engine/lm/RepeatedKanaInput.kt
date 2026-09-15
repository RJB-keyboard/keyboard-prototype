package net.ramdos.keyboard_prototype.engine.lm

/** Positions whose causal logits can share one forward pass with unchanged token/position IDs. */
internal data class RepeatedKanaInput(val tokens: LongArray, val predictionPositions: List<Int>)

internal fun HiraganaTokenizer.repeatedKanaInput(
    context: String, prefix: String, maxTokens: Int, maxPredictions: Int,
): RepeatedKanaInput {
    require(maxPredictions in 1..8)
    require(maxPredictions == 1 || prefix.isNotEmpty())
    var tokens = encode(context, prefix, maxTokens)
    val positions = mutableListOf(tokens.lastIndex)
    for (index in 1 until maxPredictions) {
        val extended = encode(context, prefix + prefix.last().toString().repeat(index), maxTokens)
        // Truncation shifts both context and absolute positions. Never reuse those logits.
        // Checking actual token IDs also handles normalization and unsupported characters.
        if (extended.size <= tokens.size || !tokens.indices.all { tokens[it] == extended[it] }) break
        tokens = extended
        positions += tokens.lastIndex
    }
    return RepeatedKanaInput(tokens, positions)
}
