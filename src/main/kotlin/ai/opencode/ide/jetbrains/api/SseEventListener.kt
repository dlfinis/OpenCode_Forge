package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.OpenCodeEventDeserializer
import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.api.models.FileDiffDeserializer
import ai.opencode.ide.jetbrains.util.PathUtil
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import okhttp3.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSE (Server-Sent Events) listener for OpenCode server.
 */
class SseEventListener(
    private val baseUrl: String,
    private val projectPath: String,
    private val onEvent: (OpenCodeEvent) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val username: String? = null,
    private val password: String? = null,
    private val reconnectDelayMs: Long = 3000L,
    private val sessionDirectoryResolver: (String) -> String? = { null }
) {
    private val logger = Logger.getInstance(SseEventListener::class.java)
    private val canonicalProjectPath = canonicalizePath(projectPath)
    private val sessionDirectoryCache = ConcurrentHashMap<String, Path>()

    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
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

    private val gson = GsonBuilder()
        .registerTypeAdapter(OpenCodeEvent::class.java, OpenCodeEventDeserializer())
        .registerTypeAdapter(FileDiff::class.java, FileDiffDeserializer())
        .create()

    private var call: Call? = null
    private val isConnected = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(true)
    private var reconnectTask: ScheduledFuture<*>? = null
    private var reconnectAttemptCount = 0

    fun connect() {
        if (isConnected.get()) return
        shouldReconnect.set(true)
        reconnectAttemptCount = 0
        doConnect()
    }

    private fun doConnect() {
        reconnectAttemptCount++
        val url = "$baseUrl/api/event"

        logger.info("[SSE] Connecting to $url (attempt #$reconnectAttemptCount)...")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        call = client.newCall(request)
        call?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isConnected.set(false)
                logger.warn("[SSE] Connection failure: ${e.message} (attempt #$reconnectAttemptCount)")
                safelyNotify { onError(e) }
                safelyNotify(onDisconnected)
                if (shouldReconnect.get()) scheduleReconnect()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        isConnected.set(false)
                        logger.warn("[SSE] Handshake failed: HTTP ${response.code} (attempt #$reconnectAttemptCount)")
                        safelyNotify { onError(IOException("SSE failed: ${response.code}")) }
                        safelyNotify(onDisconnected)
                        if (shouldReconnect.get()) scheduleReconnect()
                        return
                    }

                    clearSessionDirectoryCache()
                    isConnected.set(true)
                    reconnectAttemptCount = 0
                    safelyNotify(onConnected)
                    logger.info("[SSE] Connection established")

                    try {
                        val source = response.body.source()
                        val eventData = StringBuilder()
                        var eventName: String? = null
                        while (!source.exhausted() && !call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                dispatchBufferedEvent(eventData, eventName)
                                eventName = null
                            } else if (line.startsWith("data:")) {
                                if (eventData.isNotEmpty()) eventData.append('\n')
                                eventData.append(line.substring(5).removePrefix(" "))
                            } else if (line.startsWith("event:")) {
                                eventName = line.substring(6).removePrefix(" ").takeIf { it.isNotEmpty() }
                            }
                        }
                        dispatchBufferedEvent(eventData, eventName)
                    } catch (e: Exception) {
                        if (!call.isCanceled()) logger.warn("[SSE] Stream interrupted: ${e.message}")
                    } finally {
                        isConnected.set(false)
                        safelyNotify(onDisconnected)
                        if (shouldReconnect.get() && !call.isCanceled()) {
                            logger.info("[SSE] Connection lost. Scheduling reconnect.")
                            scheduleReconnect()
                        }
                    }
                }
            }
        })
    }

    private fun parseAndDispatchEvent(json: String, eventName: String?) {
        try {
            val payload = decodeJson(JsonParser.parseString(json))
            if (!belongsToProject(payload)) return

            val normalized = if (eventName == null) {
                payload
            } else {
                JsonObject().apply {
                    addProperty("event", eventName)
                    add("data", payload)
                }
            }
            val event = gson.fromJson(normalized, OpenCodeEvent::class.java)
            if (event != null) {
                logger.debug("[SSE] Dispatching event type=${event.type}")
                onEvent(event)
            }
        } catch (e: Exception) {
            logger.warn("[SSE] Event dispatch failed: ${e.message}", e)
        }
    }

    private fun dispatchBufferedEvent(eventData: StringBuilder, eventName: String?) {
        if (eventData.isNotEmpty()) parseAndDispatchEvent(eventData.toString(), eventName)
        eventData.setLength(0)
    }

    private fun belongsToProject(payload: JsonElement): Boolean {
        val sessionId = findSessionId(payload)
        val location = findEventLocation(payload)
        if (location != null) {
            if (sessionId != null) updateSessionDirectoryCache(sessionId, location.directory)
            val directory = location.directory ?: return false
            return pathsMatchProject(directory)
        }

        val resolvedSessionId = sessionId ?: return false
        val sessionPath = resolveSessionDirectory(resolvedSessionId) ?: return false
        return canonicalPathsEqual(sessionPath, canonicalProjectPath)
    }

    private fun updateSessionDirectoryCache(sessionId: String, directory: String?) {
        val resolvedPath = directory?.let(::canonicalizePath)
        synchronized(sessionDirectoryCache) {
            if (resolvedPath == null) {
                sessionDirectoryCache.remove(sessionId)
            } else {
                sessionDirectoryCache[sessionId] = resolvedPath
            }
        }
    }

    private fun clearSessionDirectoryCache() {
        synchronized(sessionDirectoryCache) {
            sessionDirectoryCache.clear()
        }
    }

    private fun resolveSessionDirectory(sessionId: String): Path? = try {
        synchronized(sessionDirectoryCache) {
            sessionDirectoryCache[sessionId] ?: run {
                val resolvedPath = sessionDirectoryResolver(sessionId)?.let(::canonicalizePath)
                if (resolvedPath != null) sessionDirectoryCache[sessionId] = resolvedPath
                resolvedPath
            }
        }
    } catch (e: Exception) {
        logger.warn("[SSE] Failed to resolve location for session $sessionId: ${e.message}", e)
        null
    }

    private fun decodeJson(element: JsonElement): JsonElement {
        var decoded = element
        repeat(3) {
            if (!decoded.isJsonPrimitive || !decoded.asJsonPrimitive.isString) return decoded
            decoded = try {
                JsonParser.parseString(decoded.asString)
            } catch (_: Exception) {
                return decoded
            }
        }
        return decoded
    }

    private data class EventLocation(val directory: String?)

    private fun findEventLocation(element: JsonElement): EventLocation? {
        val decoded = decodeJson(element)
        return when {
            decoded.isJsonObject -> {
                val envelope = decoded.asJsonObject
                if (envelope.has("location")) {
                    val location = envelope.get("location")?.takeIf { it.isJsonObject }?.asJsonObject
                    val directory = location?.get("directory")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                    EventLocation(directory)
                } else {
                    envelope.entrySet().firstNotNullOfOrNull { entry -> findEventLocation(entry.value) }
                }
            }
            decoded.isJsonArray -> decoded.asJsonArray.firstNotNullOfOrNull(::findEventLocation)
            else -> null
        }
    }

    private fun findSessionId(element: JsonElement): String? {
        val decoded = decodeJson(element)
        return when {
            decoded.isJsonObject -> {
                val envelope = decoded.asJsonObject
                val sessionId = listOf("sessionID", "sessionId").firstNotNullOfOrNull { key ->
                    envelope.get(key)
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                        ?.takeIf { it.isNotBlank() }
                }
                sessionId ?: envelope.entrySet().firstNotNullOfOrNull { entry -> findSessionId(entry.value) }
            }
            decoded.isJsonArray -> decoded.asJsonArray.firstNotNullOfOrNull(::findSessionId)
            else -> null
        }
    }

    private fun pathsMatchProject(directory: String): Boolean =
        canonicalPathsEqual(canonicalizePath(directory), canonicalProjectPath)

    private fun canonicalPathsEqual(first: Path?, second: Path?): Boolean {
        if (first == null || second == null) return false
        return try {
            Files.isSameFile(first, second)
        } catch (_: IOException) {
            PathUtil.toSystemIndependentPath(first.normalize().toString()) ==
                PathUtil.toSystemIndependentPath(second.normalize().toString())
        }
    }

    private fun canonicalizePath(path: String): Path? {
        if (path.isBlank()) return null
        return try {
            val normalized = Paths.get(PathUtil.toSystemIndependentPath(path)).normalize()
            try {
                normalized.toRealPath()
            } catch (_: IOException) {
                normalized.toFile().canonicalFile.toPath().normalize()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun safelyNotify(callback: () -> Unit) {
        try {
            callback()
        } catch (e: Exception) {
            logger.warn("[SSE] Listener callback failed: ${e.message}", e)
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        reconnectTask?.cancel(false)
        reconnectTask = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (shouldReconnect.get()) doConnect()
        }, reconnectDelayMs, TimeUnit.MILLISECONDS)
    }

    fun disconnect() {
        shouldReconnect.set(false)
        reconnectTask?.cancel(true)
        call?.cancel()
        isConnected.set(false)
    }

    fun isConnected(): Boolean = isConnected.get()
}
