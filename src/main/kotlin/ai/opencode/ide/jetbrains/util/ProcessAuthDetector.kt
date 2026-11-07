package ai.opencode.ide.jetbrains.util

/** Authentication data for a server connection. */
object ProcessAuthDetector {
    data class ServerAuth(
        val username: String = "opencode",
        val password: String? = null
    )

    /**
     * Process environment scanning is intentionally disabled. Shared-server credentials must come
     * from service.json; other credentials must be supplied by the user or generated for an owned server.
     */
    fun detectAuthForPort(port: Int): ServerAuth = ServerAuth()
}
