package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.util.PortFinder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortFinderAuthTest {
    @Test
    fun detectsAuthenticationRequiredByServer() {
        val server = FakeOpenCodeServer(0, "port-finder-password")
        server.start()

        try {
            assertTrue(PortFinder.isOpenCodeRunningOnPort(server.activePort))
            assertTrue(PortFinder.isAuthRequiredOnPort(server.activePort))
        } finally {
            server.stop()
        }
    }

    @Test
    fun reportsNoAuthenticationForPlainServer() {
        val server = FakeOpenCodeServer(0)
        server.start()

        try {
            assertTrue(PortFinder.isOpenCodeRunningOnPort(server.activePort))
            assertFalse(PortFinder.isAuthRequiredOnPort(server.activePort))
        } finally {
            server.stop()
        }
    }
}
