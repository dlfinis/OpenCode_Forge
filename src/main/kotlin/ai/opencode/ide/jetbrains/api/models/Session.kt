package ai.opencode.ide.jetbrains.api.models

/**
 * Represents an OpenCode session.
 */
data class Session(
    val id: String,
    val projectID: String,
    val location: SessionLocation,
    val time: SessionTime,
    val title: String? = null,
    val parentID: String? = null,
)

data class SessionLocation(
    val directory: String
)

/**
 * Session time information.
 */
data class SessionTime(
    val created: Double,
    val updated: Double
)

/**
 * Session status returned by the active-session endpoint or status events.
 */
data class SessionStatus(
    val sessionID: String,
    val status: SessionStatusType
)

/**
 * Session status type.
 */
data class SessionStatusType(
    val type: String,  // "idle", "busy", or "retry"
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null
) {
    fun isBusy(): Boolean = type == "busy" || type == "running" || type == "retry"
    fun isIdle(): Boolean = type == "idle"
    fun isRetry(): Boolean = type == "retry"
}
