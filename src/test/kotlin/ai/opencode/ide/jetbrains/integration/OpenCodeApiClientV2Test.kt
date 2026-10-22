package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.models.SessionStatusEvent
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenCodeApiClientV2Test {
    @Test
    fun unwrapsV2ResponsesAndUsesUserMessageIdForDiffs() {
        val server = FakeOpenCodeServer(0)
        server.start()
        try {
            val directory = System.getProperty("java.io.tmpdir")
            val client = OpenCodeApiClient("127.0.0.1", server.activePort)
            server.setDiffResponse(
                "user-message-1",
                """{"data":[{"file":"src/Main.kt","patch":"@@ -1 +1 @@\n-old\n+new\n","additions":1,"deletions":1,"status":"modified"}]}"""
            )
            server.setActiveSessionStatus("session-running", "running")
            val escapedDirectory = directory.replace("\\", "\\\\").replace("\"", "\\\"")
            server.setSessionResponse(
                "session-1",
                """{"data":{"id":"session-1","projectID":"project-1","cost":1.5,"tokens":{"input":3,"output":4,"reasoning":0,"cache":{"read":1,"write":2}},"time":{"created":1000,"updated":2000},"location":{"directory":"$escapedDirectory"}}}"""
            )

            assertTrue(client.checkHealth(directory))
            assertTrue(client.getSessions(directory).isEmpty())
            assertEquals("running", client.getActiveSessionStatuses(directory)?.single()?.status?.type)
            assertTrue(client.getActiveSessionStatuses(directory)?.single()?.status?.isBusy() == true)
            val session = requireNotNull(client.getSession("session-1", directory))
            assertEquals("session-1", session.id)
            assertEquals("project-1", session.projectID)
            assertEquals(directory, session.location.directory)
            assertEquals(1000.0, session.time.created, 0.0)
            assertEquals(2000.0, session.time.updated, 0.0)
            assertNull(session.title)
            assertNull(session.parentID)

            val diff = client.getSessionDiff("session-1", directory, "user-message-1").single()
            assertEquals("src/Main.kt", diff.file)
            assertEquals("old\n", diff.before)
            assertEquals("new\n", diff.after)
            assertEquals("modified", diff.status)

            val sessionRequests = server.requestUris
            assertTrue(sessionRequests.any { it.startsWith("/api/session?directory=") })
            assertTrue(sessionRequests.contains("/api/session/active"))
            assertTrue(sessionRequests.contains("/api/session/session-1"))
            assertTrue(sessionRequests.contains("/api/session/session-1/diff?from=user-message-1"))
            assertFalse(sessionRequests.any { it.startsWith("/api/session/active?") })
            assertFalse(sessionRequests.any { it.startsWith("/api/session/session-1?") })
            assertFalse(sessionRequests.any { it.contains("/diff?") && it.contains("directory=") })
        } finally {
            server.stop()
        }
    }

    @Test
    fun eventListenerResolvesAndCachesLocationlessSessionDirectories() {
        val server = FakeOpenCodeServer(0)
        server.start()
        val directory = System.getProperty("java.io.tmpdir")
        val escapedDirectory = directory.replace("\\", "\\\\").replace("\"", "\\\"")
        val receivedEvents = CountDownLatch(2)
        val client = OpenCodeApiClient("127.0.0.1", server.activePort)
        val listener = client.createEventListener(
            directory = directory,
            onEvent = { if (it is SessionStatusEvent) receivedEvents.countDown() },
            onError = {}
        )
        server.setSessionResponse(
            "session-current",
            """{"data":{"id":"session-current","projectID":"project-1","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"$escapedDirectory"}}}"""
        )

        try {
            listener.connect()
            assertTrue(waitUntil(2000) { server.sseConnectionCount >= 1 })
            server.broadcastSseEvent(
                "session.status",
                """{"sessionID":"session-current","status":{"type":"running"}}"""
            )
            server.broadcastSseEvent(
                "session.status",
                """{"sessionID":"session-current","status":{"type":"idle"}}"""
            )

            assertTrue("Current-location events should dispatch", receivedEvents.await(3, TimeUnit.SECONDS))
            assertEquals(1, server.requestUris.count { it == "/api/session/session-current" })
        } finally {
            listener.disconnect()
            server.stop()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
