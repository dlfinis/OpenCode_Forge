package ai.opencode.ide.jetbrains.util

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.URI

/**
 * Discovery of the OpenCode v2 shared background service.
 *
 * OpenCode v2 runs at most one shared background server per user account. Every
 * local client (TUI, IDE plugins, scripts) is expected to attach to that server
 * instead of starting its own instance. The service publishes its runtime
 * registration (URL, PID, basic-auth password) in `service.json` inside the
 * OpenCode state directory. The exact location depends on the platform, so this
 * object probes a list of candidates and validates every hit with a health
 * check against `/api/info`; a wrong candidate path is therefore harmless.
 *
 * Known locations:
 *  - Linux / macOS: `$XDG_STATE_HOME/opencode/service.json` (default `~/.local/state/opencode`)
 *  - Windows:       `%LOCALAPPDATA%\opencode\service.json`
 *
 * When discovery fails, callers should fall back to the legacy port scan and
 * only spawn a dedicated server as the last resort.
 */
object ServiceDiscovery {
    private val logger = Logger.getInstance(ServiceDiscovery::class.java)

    /** A shared OpenCode v2 background service discovered on this machine. */
    data class SharedServer(val hostname: String, val port: Int, val password: String?)

    /**
     * Finds the running OpenCode v2 shared background service.
     * Returns null when no state file exists or the registered endpoint is
     * not a healthy OpenCode server.
     */
    fun findSharedServer(): SharedServer? {
        val candidates = serviceStateFileCandidates(System.getProperty("user.home"), isWindows(), System::getenv)
        return findSharedServer(candidates) { host, port, password ->
            PortFinder.isOpenCodeRunningOnPort(
                port = port,
                hostname = if (host == "0.0.0.0") "127.0.0.1" else host,
                username = if (password.isNullOrBlank()) null else "opencode",
                password = password
            )
        }
    }

    /**
     * Testable core: evaluates the given candidate files and returns the first
     * one whose registered endpoint passes the health check.
     */
    internal fun findSharedServer(candidates: List<File>, isHealthy: (host: String, port: Int, password: String?) -> Boolean): SharedServer? {
        for (candidate in candidates) {
            if (!candidate.isFile) continue
            val parsed = runCatching { parseServiceJson(candidate.readText()) }
                .onFailure { logger.debug("[OpenCode] Ignoring unreadable service state file ${candidate.absolutePath}: ${it.message}") }
                .getOrNull()
            if (parsed == null) {
                logger.debug("[OpenCode] No valid service registration in ${candidate.absolutePath}")
                continue
            }
            if (isHealthy(parsed.hostname, parsed.port, parsed.password)) {
                logger.info("[OpenCode] Discovered shared OpenCode service on ${parsed.hostname}:${parsed.port} (registered in ${candidate.absolutePath})")
                return parsed
            }
            logger.debug("[OpenCode] Registered shared service ${parsed.hostname}:${parsed.port} did not pass the health check (${candidate.absolutePath})")
        }
        return null
    }

    /**
     * Parses a `service.json` registration file. Accepts both the runtime
     * registration shape (url/pid/password) and the config override shape
     * (hostname/port/password). A blank password is normalized to null.
     */
    internal fun parseServiceJson(text: String): SharedServer? {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val password = obj.primitiveField("password")?.takeIf { it.isNotBlank() }
        obj.primitiveField("url")?.let { url ->
            val (host, port) = parseHostPort(url) ?: return null
            return SharedServer(host, port, password)
        }
        val hostname = obj.primitiveField("hostname") ?: return null
        val port = obj.primitiveField("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return SharedServer(hostname, port, password)
    }

    /**
     * Candidate `service.json` locations, ordered by platform convention.
     * The health check performed by [findSharedServer] makes wrong candidates harmless.
     */
    internal fun serviceStateFileCandidates(home: String, windows: Boolean, env: (String) -> String?): List<File> {
        val paths = mutableListOf<String>()
        if (windows) {
            env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { localAppData ->
                paths.add("$localAppData\\opencode\\service.json")
                paths.add("$localAppData\\opencode\\state\\opencode\\service.json")
            }
            env("APPDATA")?.takeIf { it.isNotBlank() }?.let { roaming ->
                paths.add("$roaming\\opencode\\service.json")
            }
            paths.add("$home\\.config\\opencode\\service.json")
        } else {
            env("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let { stateHome ->
                paths.add("$stateHome/opencode/service.json")
            }
            paths.add("$home/.local/state/opencode/service.json")
        }
        return paths.filter { it.isNotBlank() }.map(::File).distinct()
    }

    private fun parseHostPort(url: String): Pair<String, Int>? {
        return runCatching {
            val uri = URI(url)
            val host = uri.host ?: return null
            val port = uri.port
            if (port in 1..65535) host to port else null
        }.getOrNull()
    }

    private fun com.google.gson.JsonElement.primitiveField(key: String): String? {
        if (!this.isJsonObject) return null
        val value = this.asJsonObject.get(key) ?: return null
        if (!value.isJsonPrimitive) return null
        return value.asString.trim().takeIf { it.isNotEmpty() }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("windows")
}
