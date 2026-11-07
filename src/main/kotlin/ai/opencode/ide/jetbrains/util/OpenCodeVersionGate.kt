package ai.opencode.ide.jetbrains.util

internal object OpenCodeVersionGate {
    fun shouldWarn(version: String?, warningAlreadyShown: Boolean): Boolean {
        if (warningAlreadyShown) return false
        val majorVersion = OpenCodeServerInfo.parse("{\"version\":\"$version\"}")?.majorVersion ?: return false
        return majorVersion != 2
    }
}
