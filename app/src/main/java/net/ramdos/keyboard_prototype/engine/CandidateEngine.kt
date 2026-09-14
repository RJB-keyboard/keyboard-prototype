package net.ramdos.keyboard_prototype.engine

/** Coordinates relative to the board: (0, 0) is top-left, (1, 1) bottom-right.
 * Outside points are preserved. elapsedMillis is measured from gesture start.
 */
data class TracePoint(val x: Float, val y: Float, val elapsedMillis: Long)

data class KanaKey(
    val kana: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

/** One completed gesture, including the key geometry used when it was recorded. */
data class GlideTrace(val points: List<TracePoint>, val keys: List<KanaKey>)

/** Android-independent boundary. Production inference runs on a background worker. */
fun interface CandidateEngine : AutoCloseable {
    fun generateCandidates(trace: GlideTrace): List<String>

    fun generateCandidates(trace: GlideTrace, precedingText: String): List<String> =
        generateCandidates(trace)

    /** May be invoked by the UI while generation is running. Must be thread-safe. */
    fun cancelPendingInference() {}

    override fun close() {}
}

/** Small deterministic fixture for frontend tests; never used by the IME. */
class StubCandidateEngine : CandidateEngine {
    override fun generateCandidates(trace: GlideTrace): List<String> =
        if (trace.points.any { point -> trace.keys.any { it.contains(point.x, point.y) } }) {
            listOf("あ", "い")
        } else {
            emptyList()
        }
}
