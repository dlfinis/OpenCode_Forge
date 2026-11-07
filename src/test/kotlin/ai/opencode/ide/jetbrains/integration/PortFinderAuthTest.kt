package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.util.PortFinder
import ai.opencode.ide.jetbrains.util.OpenCodeHealthResult
import org.junit.Assert.assertTrue
import org.junit.Test

class PortFinderAuthTest {
    @Test
    fun detectsAuthenticationRequiredByServer() {
        val server = FakeOpenCodeServer(0, "port-finder-password")
        server.start()

        try {
            assertTrue(PortFinder.isOpenCodeRunningOnPort(server.activePort) is OpenCodeHealthResult.AuthRequired)
            assertTrue(
                PortFinder.isOpenCodeRunningOnPort(server.activePort, username = "opencode", password = "port-finder-password") is
                    OpenCodeHealthResult.Healthy
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun reportsNoAuthenticationForPlainServer() {
        val server = FakeOpenCodeServer(0)
        server.start()

        try {
            assertTrue(PortFinder.isOpenCodeRunningOnPort(server.activePort) is OpenCodeHealthResult.Healthy)
        } finally {
            server.stop()
        }
    }
}
