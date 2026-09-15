package net.ramdos.keyboard_prototype.engine

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One serial engine owner. Methods are called by the UI; delivery is posted back to it. */
class CandidateSession(
    private val createEngine: () -> CandidateEngine,
    private val dispatch: (() -> Unit) -> Unit,
) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) {
        runnable -> Thread(runnable, "glide-inference").apply { isDaemon = true }
    }
    private val generation = AtomicLong()
    private var pending: Future<*>? = null
    @Volatile private var engine: CandidateEngine? = null // only cancellation crosses threads
    private var closed = false

    fun request(trace: GlideTrace, precedingText: String, deliver: (Result<List<DisplayCandidate>>) -> Unit) {
        if (closed) return
        invalidate()
        val requestId = generation.get()
        pending = worker.submit {
            val result = try {
                val activeEngine = engine ?: createEngine().also { engine = it }
                if (generation.get() != requestId) return@submit
                Result.success(activeEngine.generateScoredCandidates(trace, precedingText))
            } catch (_: InterruptedException) {
                return@submit
            } catch (exception: Exception) {
                Result.failure(exception)
            } catch (error: LinkageError) {
                // Missing/incompatible native runtime is a visible initialization failure.
                Result.failure(error)
            }
            dispatch { if (generation.get() == requestId) deliver(result) }
        }
    }

    /** Invalidate both running work and callbacks already queued on the UI thread. */
    fun invalidate() {
        if (closed) return
        generation.incrementAndGet()
        pending?.cancel(true)
        engine?.cancelPendingInference()
        pending = null
        worker.queue.clear()
    }

    override fun close() {
        if (closed) return
        invalidate()
        closed = true
        // Dispose on the same worker after any in-flight native inference has returned.
        worker.execute { engine?.close(); engine = null }
        worker.shutdown()
    }
}
