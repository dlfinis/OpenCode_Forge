package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeOpenCodeServerAuthTest {
    @Test
    fun apiClientAuthenticatesAgainstPasswordProtectedServer() {
        val password = "integration-auth-password"
        val server = FakeOpenCodeServer(0, password)
        server.start()

        try {
            val directory = System.getProperty("java.io.tmpdir")
            val unauthenticatedClient = OpenCodeApiClient("127.0.0.1", server.activePort)
            val incorrectPasswordClient = OpenCodeApiClient(
                "127.0.0.1",
                server.activePort,
                "opencode",
                "wrong-password"
            )
            val authenticatedClient = OpenCodeApiClient("127.0.0.1", server.activePort, "opencode", password)

            assertFalse(unauthenticatedClient.checkHealth(directory))
            assertNull(unauthenticatedClient.getActiveSessionStatuses(directory))
            assertFalse(incorrectPasswordClient.checkHealth(directory))
            assertTrue(authenticatedClient.checkHealth(directory))
            val sessionStatuses = authenticatedClient.getActiveSessionStatuses(directory)
            assertNotNull(sessionStatuses)
            assertTrue(requireNotNull(sessionStatuses).isEmpty())
        } finally {
            server.stop()
        }
    }
}
