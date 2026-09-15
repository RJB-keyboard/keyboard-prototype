package net.ramdos.keyboard_prototype.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun inferenceFailureIsDeliveredAndTheEngineCanHandleTheNextRequest() {
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        val failure = IllegalStateException("Inference failed")
        val received = mutableListOf<Result<List<String>>>()
        var creations = 0
        var requests = 0
        CandidateSession({
            creations++
            CandidateEngine {
                if (++requests == 1) throw failure
                listOf("復旧")
            }
        }, { deliveries.add(it) }).use { session ->
            session.request(trace, "", received::add)
            requireNotNull(deliveries.poll(3, TimeUnit.SECONDS)).invoke()
            assertSame(failure, received.single().exceptionOrNull())

            session.request(trace, "", received::add)
            requireNotNull(deliveries.poll(3, TimeUnit.SECONDS)).invoke()
            assertEquals(listOf("復旧"), received.last().getOrThrow())
            assertEquals(1, creations)
        }
    }

    @Test
    fun missingNativeRuntimeIsDeliveredAsAnInitializationFailure() {
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        val failure = UnsatisfiedLinkError("Native runtime unavailable")
        val received = mutableListOf<Result<List<String>>>()
        CandidateSession({ throw failure }, { deliveries.add(it) }).use { session ->
            session.request(trace, "", received::add)
            requireNotNull(deliveries.poll(3, TimeUnit.SECONDS)).invoke()
            assertSame(failure, received.single().exceptionOrNull())
        }
    }

    @Test
    fun closeSuppressesQueuedResultsAndDisposesTheEngineExactlyOnce() {
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        val disposed = CountDownLatch(1)
        val closes = AtomicInteger()
        var shown = false
        val engine = object : CandidateEngine {
            override fun generateCandidates(trace: GlideTrace) = listOf("候補")
            override fun close() {
                closes.incrementAndGet()
                disposed.countDown()
            }
        }
        val session = CandidateSession({ engine }, { deliveries.add(it) })
        try {
            session.request(trace, "") { shown = true }
            val callback = requireNotNull(deliveries.poll(3, TimeUnit.SECONDS))
            session.close()
            session.close()
            callback()
            assertFalse(shown)
            assertTrue(disposed.await(3, TimeUnit.SECONDS))
            assertEquals(1, closes.get())

            session.request(trace, "") { shown = true }
            assertTrue(deliveries.isEmpty())
            assertFalse(shown)
        } finally {
            session.close()
        }
    }

    @Test
    fun closingAnUnusedSessionDoesNotInitializeTheEngine() {
        val creations = AtomicInteger()
        val session = CandidateSession({
            creations.incrementAndGet()
            CandidateEngine { emptyList() }
        }, { it() })
        session.close()
        session.request(trace, "") { fail("Closed sessions must not deliver results") }
        session.close()
        assertEquals(0, creations.get())
    }
}
