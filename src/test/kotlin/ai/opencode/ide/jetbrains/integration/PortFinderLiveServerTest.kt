package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.util.PortFinder
import org.junit.Assert.assertNull
import org.junit.Test

class PortFinderLiveServerTest {
    @Test
    fun doesNotTreatAnAuthenticationChallengeAsAHealthyEndpointForPortScanning() {
        val server = FakeOpenCodeServer(0, "discovery-password")
        server.start()

        try {
            assertNull(PortFinder.findRunningOpenCodeServer(server.activePort..server.activePort))
        } finally {
            server.stop()
        }
    }
}
