package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.util.PortFinder
import org.junit.Assert.assertEquals
import org.junit.Test

class PortFinderLiveServerTest {
    @Test
    fun discoversAnAuthenticationProtectedOpenCodeEndpoint() {
        val server = FakeOpenCodeServer(0, "discovery-password")
        server.start()

        try {
            assertEquals(
                server.activePort,
                PortFinder.findRunningOpenCodeServer(server.activePort..server.activePort)
            )
        } finally {
            server.stop()
        }
    }
}
