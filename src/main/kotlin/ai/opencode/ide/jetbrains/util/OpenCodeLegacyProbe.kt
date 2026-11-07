package ai.opencode.ide.jetbrains.util

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/**
 * Detects an OpenCode v1 server so the plugin can explain that it requires v2.
 *
 * This is a diagnostic only. v1 responses are never parsed, and no v1 endpoint is
 * called for anything except this single probe. `/global/health` is the cheapest
 * unambiguous marker because v2 dropped it in favour of `/api/info`.
 */
internal object OpenCodeLegacyProbe {
    private val logger = Logger.getInstance(OpenCodeLegacyProbe::class.java)
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val READ_TIMEOUT_MS = 1500

    fun isVersionOneServer(host: String, port: Int): Boolean =
        isVersionOneServer(host, port) { url -> statusCode(url) }

    /** Test seam: lets the caller supply the status code instead of performing a request. */
    internal fun isVersionOneServer(host: String, port: Int, statusCode: (String) -> Int?): Boolean {
        val code = statusCode("http://$host:$port/global/health") ?: return false
        return code == 200
    }

    private fun statusCode(url: String): Int? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.responseCode
        } catch (e: Exception) {
            logger.debug("[OpenCode] v1 probe failed for $url: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
