package obsidian.chat.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TorManagerTest {
    @Test
    fun readsProgressAndSummary() {
        val phase = "NOTICE BOOTSTRAP PROGRESS=45 TAG=loading_descriptors SUMMARY=\"Loading relay descriptors\""
        assertEquals(TorManager.State.Starting(45, "Loading relay descriptors"), TorManager.parseBootstrapPhase(phase))
    }

    @Test
    fun includesTheProblemWhenTorWarns() {
        val phase = "WARN BOOTSTRAP PROGRESS=5 TAG=conn SUMMARY=\"Connecting to a relay\" " +
            "WARNING=\"Connection refused\" REASON=CONNECTREFUSED COUNT=3 RECOMMENDATION=warn"
        assertEquals(
            TorManager.State.Starting(5, "Connecting to a relay (problem: Connection refused)"),
            TorManager.parseBootstrapPhase(phase),
        )
    }

    @Test
    fun ignoresRepliesWithoutProgress() {
        assertNull(TorManager.parseBootstrapPhase(null))
        assertNull(TorManager.parseBootstrapPhase("250 OK"))
    }

    private val settled = TorManager.STALL_MS
    private val waited = TorManager.RETRY_BACKOFF_MS

    // Tor that starts before the phone has a network stays stuck until something prods it.
    @Test
    fun `prods tor when a network appears and bootstrap has stalled`() {
        assertTrue(TorManager.shouldRetryForNetwork(TorManager.State.Starting(0), waited, settled))
        assertTrue(TorManager.shouldRetryForNetwork(TorManager.State.Starting(30, "loading"), waited, settled))
        assertTrue(TorManager.shouldRetryForNetwork(TorManager.State.Failed("no route"), waited, settled))
    }

    @Test
    fun `leaves a working tor alone`() {
        assertFalse(TorManager.shouldRetryForNetwork(TorManager.State.Ready(9050), waited, settled))
    }

    @Test
    fun `ignores the burst of callbacks android sends for one network change`() {
        assertFalse(TorManager.shouldRetryForNetwork(TorManager.State.Starting(0), 0, settled))
        assertFalse(
            TorManager.shouldRetryForNetwork(TorManager.State.Starting(0), TorManager.RETRY_BACKOFF_MS - 1, settled),
        )
        assertTrue(TorManager.shouldRetryForNetwork(TorManager.State.Starting(0), waited, settled))
    }

    /**
     * Android delivers a network callback the instant the callback is registered, while Tor is
     * still starting for the first time. Prodding it there used to restart the service, which runs
     * Tor's native init a second time in one process and aborts it, so the app died on every
     * launch that had wi-fi already on.
     */
    @Test
    fun `does not prod tor during its first bootstrap`() {
        assertFalse(TorManager.shouldRetryForNetwork(TorManager.State.Starting(0), 0, 0))
    }

    @Test
    fun `leaves a slow but advancing bootstrap alone`() {
        assertFalse(
            TorManager.shouldRetryForNetwork(TorManager.State.Starting(25, "loading"), waited, TorManager.STALL_MS - 1),
        )
    }
}
