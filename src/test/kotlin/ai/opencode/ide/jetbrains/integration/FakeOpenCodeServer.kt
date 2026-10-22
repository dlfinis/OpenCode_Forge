package ai.opencode.ide.jetbrains.integration

import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FakeOpenCodeServer(port: Int) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val sseClients = CopyOnWriteArrayList<OutputStream>()
    private val diffResponses = ConcurrentHashMap<String, String>()
    private val diffDelays = ConcurrentHashMap<String, Long>()
    private val activeSessionStatuses = ConcurrentHashMap<String, String>()
    private val sessionResponses = ConcurrentHashMap<String, String>()
    private val connectionCount = AtomicInteger()
    val requestUris = CopyOnWriteArrayList<String>()

    val activePort: Int
        get() = server.address.port

    val sseConnectionCount: Int
        get() = connectionCount.get()

    init {
        server.createContext("/") { exchange ->
            val response = "Not found".toByteArray()
            exchange.sendResponseHeaders(404, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        server.createContext("/api/info") { exchange ->
            val response = "{\"version\":\"2.1.3\"}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        server.createContext("/api/event") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, 0)

            val output = exchange.responseBody
            sseClients.add(output)
            connectionCount.incrementAndGet()
            try {
                output.write(": ready\n\n".toByteArray())
                output.flush()
                while (!Thread.currentThread().isInterrupted) Thread.sleep(1000)
            } catch (_: Exception) {
                // Closing the fake stream is part of reconnect tests.
            } finally {
                sseClients.remove(output)
                try {
                    output.close()
                } catch (_: Exception) {
                    // The client may already have closed the stream.
                }
            }
        }

        server.createContext("/api/session") { exchange ->
            requestUris.add(exchange.requestURI.toString())
            val path = exchange.requestURI.path
            val body = when {
                path.endsWith("/diff") -> {
                    val fromId = exchange.requestURI.rawQuery.orEmpty()
                        .split('&')
                        .firstOrNull { it.startsWith("from=") }
                        ?.substringAfter('=')
                        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
                    val delay = fromId?.let(diffDelays::get) ?: 0L
                    if (delay > 0) Thread.sleep(delay)
                    fromId?.let(diffResponses::get) ?: "{\"data\":[]}"
                }
                path.endsWith("/active") -> {
                    val statuses = activeSessionStatuses.entries.joinToString(",") { (id, status) ->
                        "\"$id\":{\"type\":\"$status\"}"
                    }
                    "{\"data\":{$statuses}}"
                }
                path == "/api/session" -> "{\"data\":[],\"cursor\":null}"
                else -> sessionResponses[path.substringAfterLast('/')]
                    ?: "{\"data\":null}"
            }
            val response = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        server.executor = Executors.newCachedThreadPool()
    }

    fun start() {
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    fun broadcast(json: String) {
        val message = "data: $json\n\n".toByteArray(StandardCharsets.UTF_8)
        broadcast(message)
    }

    fun broadcastSseEvent(eventName: String, data: String) {
        val message = "event: $eventName\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8)
        broadcast(message)
    }

    private fun broadcast(message: ByteArray) {
        sseClients.forEach { client ->
            try {
                client.write(message)
                client.flush()
            } catch (_: Exception) {
                sseClients.remove(client)
            }
        }
    }

    fun disconnectSseClients() {
        sseClients.forEach { client ->
            try {
                client.close()
            } catch (_: Exception) {
                // The stream may already be closed.
            }
        }
        sseClients.clear()
    }

    fun setDiffResponse(fromMessageId: String, json: String, delayMs: Long = 0) {
        diffResponses[fromMessageId] = json
        if (delayMs > 0) diffDelays[fromMessageId] = delayMs
    }

    fun setActiveSessionStatus(sessionId: String, status: String) {
        activeSessionStatuses[sessionId] = status
    }

    fun setSessionResponse(sessionId: String, json: String) {
        sessionResponses[sessionId] = json
    }
}
