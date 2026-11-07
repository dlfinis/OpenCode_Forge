package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.util.OpenCodeServerInfo
import ai.opencode.ide.jetbrains.util.PathUtil
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * HTTP client for OpenCode Server API.
 */
open class OpenCodeApiClient(
    private val hostname: String, 
    private val port: Int,
    private val username: String? = null,
    private val password: String? = null
) {

    private val logger = Logger.getInstance(OpenCodeApiClient::class.java)
    private val baseUrl = "http://$hostname:$port"

    // Make client protected so subclasses can potentially use it or override methods
    protected val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (username != null && password != null) {
                addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(username, password))
                        .build()
                    chain.proceed(request)
                }
            }
        }
        .build()

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(OpenCodeEvent::class.java, OpenCodeEventDeserializer())
        .registerTypeAdapter(FileDiff::class.java, FileDiffDeserializer())
        .create()

    fun getSessions(directory: String): List<Session> {
        val url = "${apiUrl("/session")}?directory=${encode(PathUtil.toOpenCodeServerPath(directory))}"
        val type = object : TypeToken<List<Session>>() {}.type
        return unwrapData(get<JsonElement>(url, JsonElement::class.java))?.let { gson.fromJson<List<Session>>(it, type) }
            ?: emptyList()
    }

    fun getSessionStatuses(directory: String): List<SessionStatus> {
        return getActiveSessionStatuses(directory).orEmpty()
    }

    /** Returns null when the request failed, and an empty list when the server reported no active sessions. */
    fun getActiveSessionStatuses(directory: String): List<SessionStatus>? {
        val payload = unwrapData(get<JsonElement>(apiUrl("/session/active"), JsonElement::class.java))
            ?: return null

        if (!payload.isJsonObject) return null
        return payload.asJsonObject.entrySet().mapNotNull { (sessionId, value) ->
            if (!value.isJsonObject) return@mapNotNull null
            val status = gson.fromJson(value, SessionStatusType::class.java)
            SessionStatus(sessionId, status)
        }
    }

    fun findBusySession(directory: String): SessionStatus? {
        return getSessionStatuses(directory).find { it.status.isBusy() }
    }

    open fun getSessionDiff(sessionId: String, directory: String, userMessageId: String? = null): List<FileDiff> {
        val query = userMessageId?.let { "?from=${encode(it)}" }.orEmpty()
        val url = "${apiUrl("/session/${encodePath(sessionId)}/diff")}$query"
        val type = object : TypeToken<List<FileDiff>>() {}.type
        val diffs = unwrapData(get<JsonElement>(url, JsonElement::class.java))?.let { gson.fromJson<List<FileDiff>>(it, type) }
            ?: emptyList()
        
        if (diffs.isNotEmpty()) {
            logger.info("[API] Received ${diffs.size} diffs for session $sessionId")
            diffs.forEach { d ->
                val b = if (d.before.length > 20) d.before.take(20) + "..." else d.before.replace("\n", "\\n")
                val a = if (d.after.length > 20) d.after.take(20) + "..." else d.after.replace("\n", "\\n")
                logger.debug("  Diff: ${d.file} | Before[${d.before.length}]: '$b' | After[${d.after.length}]: '$a'")
            }
        }
        return diffs
    }

    /**
     * Get a specific session by ID.
     */
    open fun getSession(sessionId: String, directory: String): Session? {
        val url = apiUrl("/session/${encodePath(sessionId)}")
        val payload = unwrapData(get<JsonElement>(url, JsonElement::class.java)) ?: return null
        return gson.fromJson(payload, Session::class.java)
    }

    fun getServerInfo(): OpenCodeServerInfo? =
        get<JsonElement>(apiUrl("/info"), JsonElement::class.java)
            ?.let { OpenCodeServerInfo.parse(it.toString()) }

    fun checkHealth(directory: String): Boolean = getServerInfo() != null

    fun createEventListener(
        directory: String,
        onEvent: (OpenCodeEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {}
    ): SseEventListener {
        val serverPath = PathUtil.toOpenCodeServerPath(directory)
        return SseEventListener(
            baseUrl,
            serverPath,
            onEvent,
            onError,
            onConnected,
            onDisconnected,
            username,
            password,
            sessionDirectoryResolver = { sessionId ->
                getSession(sessionId, directory)?.location?.directory
            }
        )
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun encodePath(value: String): String = encode(value).replace("+", "%20")

    private fun apiUrl(path: String): String = "$baseUrl/api$path"

    private fun unwrapData(response: JsonElement?): JsonElement? {
        if (response == null || !response.isJsonObject) return response
        val objectResponse = response.asJsonObject
        return objectResponse.get("data") ?: response
    }

    private fun <T> get(url: String, clazz: Class<T>): T? {
        val request = Request.Builder().url(url).get().build()
        return execute(request, clazz)
    }

    private fun <T> execute(request: Request, clazz: Class<T>): T? {
        val startTime = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    logger.debug("[API] Success: ${request.method} ${request.url} (took ${duration}ms)")
                    // 强制使用 UTF-8 解码，避免服务端未设置 charset 导致中文乱码或内容丢失
                    val bodyBytes = response.body?.bytes()
                    val jsonString = bodyBytes?.toString(Charsets.UTF_8)
                    gson.fromJson(jsonString, clazz)
                } else {
                    val bodyPreview = try { response.peekBody(1024).string() } catch (_: Exception) { "no body" }
                    logger.warn("[API] Failed: ${request.method} ${request.url} (HTTP ${response.code}, took ${duration}ms). Response: $bodyPreview")
                    null
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.debug("[API] Error: ${request.method} ${request.url} -> ${e.message} (after ${duration}ms)")
            null
        }
    }
}
