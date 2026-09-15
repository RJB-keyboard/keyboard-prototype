package net.ramdos.keyboard_prototype.engine.conversion

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Arrays
import java.util.zip.GZIPInputStream
import kotlin.math.ln

/** Character 1..4-gram model of kanji/kana surfaces, trained offline on public sentences. */
internal class SurfaceLanguageModel private constructor(
    private val total: Long,
    private val keys: List<LongArray>,
    private val counts: List<IntArray>,
) {
    /** Mean negative log probability: compare spellings without rewarding shorter surfaces. */
    fun cost(text: String): Double {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        if (normalized.isEmpty()) return 0.0
        var result = 0.0
        for (end in normalized.indices) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Surface scoring interrupted")
            var key = normalized[end].code.toLong()
            var probability = (count(1, key) + 0.5) / (total + 0.5 * (keys[0].size + 1))
            for (order in 2..minOf(4, end + 1)) {
                key = key or (normalized[end - order + 1].code.toLong() shl (16 * (order - 1)))
                val contextCount = count(order - 1, key ushr 16)
                if (contextCount > 0) {
                    // Back off smoothly to the shorter context when observations are sparse.
                    probability = (count(order, key) + BACKOFF_STRENGTH * probability) /
                        (contextCount + BACKOFF_STRENGTH)
                }
            }
            result -= ln(probability)
        }
        return result / normalized.length
    }

    private fun count(order: Int, key: Long): Int {
        val index = Arrays.binarySearch(keys[order - 1], key)
        return if (index < 0) 0 else counts[order - 1][index]
    }

    companion object {
        private const val BACKOFF_STRENGTH = 20.0

        fun read(input: InputStream): SurfaceLanguageModel =
            DataInputStream(BufferedInputStream(GZIPInputStream(input))).use { data ->
                require(data.readInt() == 0x53554D31 && data.readInt() == 1) { "Invalid surface model header" }
                val total = data.readLong()
                require(total > 0)
                val keys = mutableListOf<LongArray>()
                val counts = mutableListOf<IntArray>()
                repeat(4) {
                    val size = data.readInt()
                    require(size in 1..1_000_000) { "Invalid surface model size" }
                    val orderKeys = LongArray(size)
                    val orderCounts = IntArray(size)
                    repeat(size) { index ->
                        orderKeys[index] = data.readLong()
                        orderCounts[index] = data.readInt()
                        require(orderCounts[index] > 0 && orderCounts[index] <= total)
                        require(index == 0 || orderKeys[index - 1] < orderKeys[index]) { "Unsorted surface model" }
                    }
                    keys += orderKeys
                    counts += orderCounts
                }
                require(data.read() == -1) { "Trailing surface model data" }
                SurfaceLanguageModel(total, keys, counts)
            }
    }
}

/** Conservative reranking of a bounded dictionary pool; zero weight gives the dictionary baseline. */
data class SurfaceRerankingConfig(
    val weight: Double = 0.5,
    val maxAdjustment: Double = 0.75,
    val maxDictionaryGap: Double = 1.0,
) {
    init {
        require(weight.isFinite() && weight in 0.0..10.0)
        require(maxAdjustment.isFinite() && maxAdjustment in 0.0..5.0)
        require(maxDictionaryGap.isFinite() && maxDictionaryGap in 0.0..5.0)
    }
}

/** Preserve contextual differences when limiting their influence on dictionary scores.
 * Individual clipping makes distinct good alternatives tie against a poor baseline.
 * Scale boosts and penalties separately so bad outliers cannot dilute good evidence.
 */
internal fun SurfaceRerankingConfig.boundAdjustments(differences: List<Double>): List<Double> {
    if (differences.isEmpty()) return emptyList()
    val largestBoost = -differences.minOrNull()!!.coerceAtMost(0.0)
    val largestPenalty = differences.maxOrNull()!!.coerceAtLeast(0.0)
    fun scale(largest: Double) = if (largest > maxAdjustment) maxAdjustment / largest else 1.0
    val boostScale = scale(largestBoost)
    val penaltyScale = scale(largestPenalty)
    return differences.map { it * if (it < 0.0) boostScale else penaltyScale }
}
