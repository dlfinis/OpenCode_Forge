package ai.opencode.ide.jetbrains.util

import com.google.gson.JsonParser

sealed interface OpenCodeHealthResult {
    data class Healthy(val info: OpenCodeServerInfo) : OpenCodeHealthResult
    data class AuthRequired(val statusCode: Int) : OpenCodeHealthResult
    data class ForeignService(val statusCode: Int, val reason: String) : OpenCodeHealthResult
    data class NotResponding(val reason: String?) : OpenCodeHealthResult
}

data class OpenCodeServerInfo(val version: String) {
    val majorVersion: Int?
        get() = version.substringBefore('.').toIntOrNull()

    companion object {
        private val VERSION_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+(?:[-+].*)?$")

        internal fun parse(body: String?): OpenCodeServerInfo? {
            if (body.isNullOrBlank()) return null
            val json = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return null
            if (!json.isJsonObject) return null

            val versionElement = json.asJsonObject.get("version") ?: return null
            if (!versionElement.isJsonPrimitive || !versionElement.asJsonPrimitive.isString) return null

            val version = versionElement.asString.trim()
            if (!VERSION_PATTERN.matches(version)) return null
            return OpenCodeServerInfo(version)
        }
    }
}

internal object OpenCodeHealthClassifier {
    fun fromResponse(statusCode: Int, body: String?): OpenCodeHealthResult {
        if (statusCode == 401 || statusCode == 403) {
            return OpenCodeHealthResult.AuthRequired(statusCode)
        }

        if (statusCode != 200) {
            return OpenCodeHealthResult.ForeignService(statusCode, "Unexpected HTTP response")
        }

        val info = OpenCodeServerInfo.parse(body)
        return if (info != null) {
            OpenCodeHealthResult.Healthy(info)
        } else {
            OpenCodeHealthResult.ForeignService(statusCode, "Response is not a valid OpenCode /api/info object")
        }
    }

    fun fromFailure(error: Exception): OpenCodeHealthResult =
        OpenCodeHealthResult.NotResponding(error.message ?: error.javaClass.simpleName)
}
