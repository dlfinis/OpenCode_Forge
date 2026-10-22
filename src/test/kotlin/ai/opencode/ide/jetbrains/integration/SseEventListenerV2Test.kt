package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.api.SseEventListener
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.SessionStatusEvent
import ai.opencode.ide.jetbrains.api.models.UnknownEvent
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SseEventListenerV2Test {
    @Test
    fun filtersDirectAndSessionResolvedEventsAndCachesLookupsAcrossReconnects() {
        val server = FakeOpenCodeServer(0)
        server.start()
        val directory = System.getProperty("java.io.tmpdir")
        val escapedDirectory = directory.replace("\\", "\\\\").replace("\"", "\\\"")
        val statuses = CopyOnWriteArrayList<String>()
        val receivedStatuses = CountDownLatch(6)
        val receivedUnknownEvent = CountDownLatch(1)
        val lookupCounts = ConcurrentHashMap<String, AtomicInteger>()
        val unknownEvents = CopyOnWriteArrayList<OpenCodeEvent>()
        val connected = CountDownLatch(2)
        val listener = SseEventListener(
            baseUrl = "http://127.0.0.1:${server.activePort}",
            projectPath = directory,
            onEvent = { event ->
                if (event is SessionStatusEvent) {
                    statuses.add(event.properties.status.type)
                    receivedStatuses.countDown()
                } else if (event is UnknownEvent) {
                    unknownEvents.add(event)
                    receivedUnknownEvent.countDown()
                }
            },
            onError = {},
            onConnected = { connected.countDown() },
            reconnectDelayMs = 25,
            sessionDirectoryResolver = { sessionId ->
                lookupCounts.computeIfAbsent(sessionId) { AtomicInteger() }.incrementAndGet()
                when (sessionId) {
                    "s1" -> directory
                    "foreign-session" -> "/another/project"
                    else -> null
                }
            }
        )

        fun event(id: String, location: String, status: String): String =
            """{"id":"$id","type":"session.status","created":"2026-10-22T00:00:00Z","data":{"sessionID":"s1","status":{"type":"$status"}},"location":{"directory":"$location"}}"""

        try {
            listener.connect()
            assertTrue(waitUntil(2000) { server.sseConnectionCount >= 1 })

            server.broadcast("not-json")
            server.broadcast(event("other", "/another/project", "busy"))
            server.broadcast(event("first", escapedDirectory, "running"))

            val stringPayload = """{"sessionID":"s1","status":{"type":"retry"}}"""
            val encodedPayload = com.google.gson.JsonPrimitive(stringPayload)
            server.broadcast(
                """{"event":"session.status","data":$encodedPayload,"location":{"directory":"/another/project"}}"""
            )
            server.broadcast(
                """{"event":"session.status","data":$encodedPayload,"location":{"directory":"$escapedDirectory"}}"""
            )
            server.broadcast(
                """{"event":"session.status","data":{"sessionID":"s1","status":{"type":"busy"}},"location":{"directory":"$escapedDirectory"}}"""
            )
            server.broadcastSseEvent(
                "session.status",
                """{"properties":{"sessionID":"s1","status":{"type":"idle"}}}"""
            )
            server.broadcastSseEvent("session.status", """{"sessionID":"s1","status":{"type":"paused"}}""")
            server.broadcast(
                """{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"paused"}},"location":{"directory":"$escapedDirectory"}}"""
            )

            server.broadcastSseEvent("session.status", """{"sessionID":"foreign-session","status":{"type":"busy"}}""")
            server.broadcastSseEvent("session.status", """{"sessionID":"lookup-failed","status":{"type":"busy"}}""")
            server.broadcastSseEvent("session.status", """{"status":{"type":"busy"}}""")
            val encodedAliasPayload = com.google.gson.JsonPrimitive("""{"nested":{"sessionId":"s1"}}""")
            server.broadcast(
                """{"event":"custom.event","data":$encodedAliasPayload}"""
            )

            assertTrue(receivedStatuses.await(2, TimeUnit.SECONDS))
            assertTrue(receivedUnknownEvent.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("running", "retry", "busy", "idle", "paused", "paused"), statuses)
            assertEquals("custom.event", unknownEvents.single().type)
            assertTrue("A direct project location should seed the session cache", lookupCounts["s1"] == null)
            assertEquals(1, lookupCounts["foreign-session"]?.get())
            assertEquals(1, lookupCounts["lookup-failed"]?.get())
            assertEquals(2, lookupCounts.size)

            server.disconnectSseClients()
            assertTrue("Expected the SSE listener to reconnect", connected.await(3, TimeUnit.SECONDS))
            assertTrue(waitUntil(2000) { server.sseConnectionCount >= 2 })
            server.broadcastSseEvent("session.status", """{"sessionID":"s1","status":{"type":"running"}}""")
            assertTrue(receivedStatuses.await(2, TimeUnit.SECONDS))
            assertTrue(waitUntil(2000) { statuses.size == 7 })
            assertEquals(listOf("running", "retry", "busy", "idle", "paused", "paused", "running"), statuses)
            assertEquals(1, lookupCounts["s1"]?.get())
        } finally {
            listener.disconnect()
            server.stop()
        }
    }

    @Test
    fun movedSessionUpdatesLocationCacheAndReconnectForcesFreshLookup() {
        val server = FakeOpenCodeServer(0)
        server.start()
        val directory = System.getProperty("java.io.tmpdir")
        val escapedDirectory = directory.replace("\\", "\\\\").replace("\"", "\\\"")
        val statuses = CopyOnWriteArrayList<String>()
        val resolverCalls = AtomicInteger()
        val connectionCount = AtomicInteger()
        val reconnectObserved = java.util.concurrent.atomic.AtomicBoolean(false)
        val connected = CountDownLatch(2)
        val listener = SseEventListener(
            baseUrl = "http://127.0.0.1:${server.activePort}",
            projectPath = directory,
            onEvent = { event ->
                if (event is SessionStatusEvent) statuses.add(event.properties.status.type)
            },
            onError = {},
            onConnected = {
                if (connectionCount.incrementAndGet() > 1) reconnectObserved.set(true)
                connected.countDown()
            },
            reconnectDelayMs = 25,
            sessionDirectoryResolver = {
                if (resolverCalls.incrementAndGet() == 1 || !reconnectObserved.get()) directory
                else "/another/project"
            }
        )

        fun status(sessionId: String, status: String): String =
            """{"sessionID":"$sessionId","status":{"type":"$status"}}"""

        fun directStatus(sessionId: String, status: String): String =
            """{"type":"session.status","data":${status(sessionId, status)},"location":{"directory":"$escapedDirectory"}}"""

        try {
            listener.connect()
            assertTrue(waitUntil(2000) { server.sseConnectionCount >= 1 })

            server.broadcastSseEvent("session.status", status("moving-session", "running"))
            assertTrue(waitUntil(2000) { statuses.size == 1 })
            assertEquals(1, resolverCalls.get())

            server.broadcast(
                """{"type":"session.status","data":${status("moving-session", "idle")},"location":{"directory":"/another/project"}}"""
            )
            server.broadcastSseEvent("session.status", status("moving-session", "busy"))
            server.broadcast(directStatus("marker-session", "marker"))

            assertTrue(waitUntil(2000) { statuses.size == 2 })
            assertEquals(listOf("running", "marker"), statuses)
            assertEquals("A foreign explicit location must replace the cached project", 1, resolverCalls.get())

            server.disconnectSseClients()
            assertTrue("Expected the SSE listener to reconnect", connected.await(3, TimeUnit.SECONDS))
            assertTrue(waitUntil(2000) { server.sseConnectionCount >= 2 })
            server.broadcastSseEvent("session.status", status("moving-session", "retry"))
            server.broadcast(directStatus("reconnect-marker", "reconnected-marker"))

            assertTrue(waitUntil(2000) { statuses.size == 3 })
            assertEquals(listOf("running", "marker", "reconnected-marker"), statuses)
            assertEquals("Reconnect should discard cached session locations", 2, resolverCalls.get())
        } finally {
            listener.disconnect()
            server.stop()
        }
    }

    @Test
    fun matchesDirectLocationThroughSymlinkedProjectPathWhenSupported() {
        val parent = Files.createTempDirectory("opencode-sse-symlink-")
        val target = Files.createDirectory(parent.resolve("target"))
        val alias = parent.resolve("alias")
        try {
            Files.createSymbolicLink(alias, target)
        } catch (e: Exception) {
            Files.deleteIfExists(target)
            Files.deleteIfExists(parent)
            Assume.assumeTrue("Symbolic links are unavailable: ${e.message}", false)
            return
        }

        val server = FakeOpenCodeServer(0)
        val receivedEvent = CountDownLatch(1)
        val resolverCalls = AtomicInteger()
        val listener = SseEventListener(
            baseUrl = "http://127.0.0.1:${server.activePort}",
            projectPath = alias.toString(),
            onEvent = { if (it is SessionStatusEvent) receivedEvent.countDown() },
            onError = {},
            sessionDirectoryResolver = {
                resolverCalls.incrementAndGet()
                target.toString()
            }
        )
        val escapedTarget = target.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        try {
            server.start()
            listener.connect()
            assertTrue(waitUntil(2000) { server.sseConnectionCount >= 1 })
            server.broadcast(
                """{"type":"session.status","data":{"sessionID":"s1","status":{"type":"running"}},"location":{"directory":"$escapedTarget"}}"""
            )
            assertTrue(receivedEvent.await(2, TimeUnit.SECONDS))
            assertEquals(0, resolverCalls.get())
        } finally {
            listener.disconnect()
            server.stop()
            Files.deleteIfExists(alias)
            Files.deleteIfExists(target)
            Files.deleteIfExists(parent)
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
