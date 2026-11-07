package ai.opencode.ide.jetbrains.util

import java.util.concurrent.ConcurrentHashMap

/** Serializes health-check-to-readiness decisions for a local port across project services. */
internal object LocalServerSpawnCoordinator {
    private class PortState {
        val lock = Any()
        var owner: Any? = null
        var processToken: Any? = null
    }

    private val states = ConcurrentHashMap<Int, PortState>()

    fun <T> withPortLock(port: Int, action: () -> T): T {
        val state = states.computeIfAbsent(port) { PortState() }
        return synchronized(state.lock) { action() }
    }

    fun isManaged(port: Int): Boolean {
        val state = states[port] ?: return false
        return synchronized(state.lock) { state.owner != null }
    }

    fun isManagedByAnother(port: Int, owner: Any): Boolean {
        val state = states[port] ?: return false
        return synchronized(state.lock) { state.owner != null && state.owner !== owner }
    }

    fun markStarted(port: Int, owner: Any, processToken: Any) {
        val state = states.computeIfAbsent(port) { PortState() }
        synchronized(state.lock) {
            state.owner = owner
            state.processToken = processToken
        }
    }

    fun release(port: Int, owner: Any) {
        val state = states[port] ?: return
        synchronized(state.lock) {
            if (state.owner === owner) {
                state.owner = null
                state.processToken = null
            }
        }
    }

    fun release(port: Int, owner: Any, processToken: Any) {
        val state = states[port] ?: return
        synchronized(state.lock) {
            if (state.owner === owner && state.processToken === processToken) {
                state.owner = null
                state.processToken = null
            }
        }
    }
}
