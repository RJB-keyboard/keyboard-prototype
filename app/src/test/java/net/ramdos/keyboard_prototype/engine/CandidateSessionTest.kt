package net.ramdos.keyboard_prototype.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CandidateSessionTest {
    private val trace = GlideTrace(emptyList(), emptyList())

    @Test
    fun newRequestSuppressesAnUninterruptibleOldInferenceAndKeepsLatestContext() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        val received = mutableListOf<String>()
        val engine = object : CandidateEngine {
            override fun generateCandidates(trace: GlideTrace) = emptyList<String>()
            override fun generateCandidates(trace: GlideTrace, precedingText: String): List<String> {
                if (precedingText == "古い") {
                    entered.countDown()
                    // Simulate a native call that cannot finish until its kernel returns.
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { /* finish the kernel */ }
                    }
                }
                return listOf(precedingText)
            }
            override fun close() { disposed.countDown() }
        }
        val session = CandidateSession({ engine }, { deliveries.add(it) })
        try {
            session.request(trace, "古い") { received.addAll(it.getOrThrow()) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            session.request(trace, "新しい") { received.addAll(it.getOrThrow()) }
            release.countDown()
            repeat(2) { requireNotNull(deliveries.poll(3, TimeUnit.SECONDS)).invoke() }
            assertEquals(listOf("新しい"), received)
        } finally {
            release.countDown()
            session.close()
        }
        assertTrue(disposed.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun startingAGestureInvalidatesAlreadyQueuedUiResults() {
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        var shown = false
        CandidateSession({ CandidateEngine { listOf("候補") } }, { deliveries.add(it) }).use { session ->
            session.request(trace, "") { shown = true }
            val callback = requireNotNull(deliveries.poll(3, TimeUnit.SECONDS))
            session.invalidate()
            callback()
            assertFalse(shown)
        }
    }

    @Test
    fun modelInitializationFailureIsVisibleAndCanBeRetried() {
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        var attempts = 0
        val received = mutableListOf<Result<List<String>>>()
        CandidateSession({
            if (++attempts == 1) throw IllegalStateException("Model unavailable")
            CandidateEngine { listOf("復旧") }
        }, { deliveries.add(it) }).use { session ->
            session.request(trace, "", received::add)
            requireNotNull(deliveries.poll(3, TimeUnit.SECONDS)).invoke()
            assertTrue(received.single().isFailure)
            session.request(trace, "", received::add)
            requireNotNull(deliveries.poll(3, TimeUnit.SECONDS)).invoke()
            assertEquals(listOf("復旧"), received.last().getOrThrow())
        }
    }
}
