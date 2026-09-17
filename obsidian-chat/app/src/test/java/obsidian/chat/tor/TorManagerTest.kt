package obsidian.chat.tor

import org.junit.Assert.assertEquals
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
}
