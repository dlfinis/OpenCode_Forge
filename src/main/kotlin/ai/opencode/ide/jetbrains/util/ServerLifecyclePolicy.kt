package ai.opencode.ide.jetbrains.util

internal enum class ServerOwnership {
    OWNED,
    SHARED,
    ATTACHED,
    REMOTE
}

internal enum class StopAction {
    TERMINATE_OWNED_SERVER,
    DISCONNECT_ONLY
}

internal sealed interface ReviveAction {
    data object StartOwnedServer : ReviveAction
    data class ReattachSharedServer(val server: ServiceDiscovery.SharedServer) : ReviveAction
    data class Abort(val reason: String) : ReviveAction
}

internal object ServerLifecyclePolicy {
    fun stopAction(ownership: ServerOwnership): StopAction =
        if (ownership == ServerOwnership.OWNED) StopAction.TERMINATE_OWNED_SERVER else StopAction.DISCONNECT_ONLY

    fun performStop(
        ownership: ServerOwnership,
        terminateOwnedServer: () -> Unit,
        disconnectClients: () -> Unit
    ): StopAction {
        val action = stopAction(ownership)
        if (action == StopAction.TERMINATE_OWNED_SERVER) terminateOwnedServer()
        disconnectClients()
        return action
    }

    fun reviveAction(
        ownership: ServerOwnership,
        expectedSharedServer: ServiceDiscovery.SharedServer?,
        currentDiscovery: ServiceDiscovery.DiscoveryResult
    ): ReviveAction {
        return when (ownership) {
            ServerOwnership.OWNED -> ReviveAction.StartOwnedServer
            ServerOwnership.SHARED -> {
                val expected = expectedSharedServer
                    ?: return ReviveAction.Abort("The original shared-server identity is unavailable")
                val current = (currentDiscovery as? ServiceDiscovery.DiscoveryResult.Registered)
                    ?: return ReviveAction.Abort("The shared-server registration could not be verified")
                if (current.health !is OpenCodeHealthResult.Healthy) {
                    return ReviveAction.Abort("The registered shared server is not healthy")
                }
                if (!expected.hasCompleteIdentity || !current.server.hasCompleteIdentity) {
                    return ReviveAction.Abort("The shared-server identity is incomplete")
                }
                if (!expected.hasSameIdentity(current.server)) {
                    return ReviveAction.Abort("The shared-server identity changed")
                }
                ReviveAction.ReattachSharedServer(current.server)
            }
            ServerOwnership.ATTACHED -> ReviveAction.Abort("The attached server is not owned by this plugin")
            ServerOwnership.REMOTE -> ReviveAction.Abort("Remote servers are never restarted locally")
        }
    }
}
