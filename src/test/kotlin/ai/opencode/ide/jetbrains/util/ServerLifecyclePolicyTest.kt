package ai.opencode.ide.jetbrains.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLifecyclePolicyTest {
    @Test
    fun stopTerminatesOnlyAnOwnedServer() {
        assertStopEffects(ServerOwnership.OWNED, expectedTerminations = 1)
        assertStopEffects(ServerOwnership.SHARED, expectedTerminations = 0)
        assertStopEffects(ServerOwnership.REMOTE, expectedTerminations = 0)
    }

    @Test
    fun changedSharedServerIdentityBlocksAutomaticRevive() {
        val original = ServiceDiscovery.SharedServer(
            hostname = "127.0.0.1",
            port = 4096,
            password = "secret",
            pid = "100",
            url = "http://127.0.0.1:4096"
        )
        val replacements = listOf(
            original.copy(pid = "200"),
            original.copy(password = "rotated-secret"),
            original.copy(url = "http://127.0.0.1:4097")
        )

        replacements.forEach { replaced ->
            val discovery = ServiceDiscovery.DiscoveryResult.Registered(
                replaced,
                OpenCodeHealthResult.Healthy(OpenCodeServerInfo("2.0.0"))
            )
            assertTrue(ServerLifecyclePolicy.reviveAction(ServerOwnership.SHARED, original, discovery) is ReviveAction.Abort)
        }
    }

    @Test
    fun unreadableSharedRegistrationBlocksAutomaticRevive() {
        val original = ServiceDiscovery.SharedServer("127.0.0.1", 4096, "secret", "100", "http://127.0.0.1:4096")
        val unavailable = ServiceDiscovery.DiscoveryResult.Unavailable(File("service.json").path, "File is unreadable")

        assertTrue(ServerLifecyclePolicy.reviveAction(ServerOwnership.SHARED, original, unavailable) is ReviveAction.Abort)
    }

    private fun assertStopEffects(ownership: ServerOwnership, expectedTerminations: Int) {
        var terminations = 0
        var disconnections = 0

        ServerLifecyclePolicy.performStop(
            ownership,
            terminateOwnedServer = { terminations++ },
            disconnectClients = { disconnections++ }
        )

        assertEquals(expectedTerminations, terminations)
        assertEquals(1, disconnections)
    }
}
