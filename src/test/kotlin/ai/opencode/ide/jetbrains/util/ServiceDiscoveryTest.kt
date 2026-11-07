package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServiceDiscoveryTest {
    @Test
    fun parsesRuntimeRegistrationWithUrlAndPassword() {
        val server = ServiceDiscovery.parseServiceJson(
            """{"id":"fa8b4fc4","version":"2.2.0","url":"http://127.0.0.1:49374","pid":79784,"password":"secret"}"""
        )
        assertEquals("127.0.0.1", server?.hostname)
        assertEquals(49374, server?.port)
        assertEquals("secret", server?.password)
        assertEquals("79784", server?.pid)
        assertEquals("http://127.0.0.1:49374", server?.url)
    }

    @Test
    fun parsesConfigOverrideShapeWithHostnameAndPort() {
        val server = ServiceDiscovery.parseServiceJson("""{"hostname":"127.0.0.1","port":45278,"password":""}""")
        assertEquals("127.0.0.1", server?.hostname)
        assertEquals(45278, server?.port)
        assertNull(server?.password)
        assertEquals("http://127.0.0.1:45278", server?.url)
    }

    @Test
    fun rejectsMalformedRegistrations() {
        assertNull(ServiceDiscovery.parseServiceJson("not json at all"))
        assertNull(ServiceDiscovery.parseServiceJson("[]"))
        assertNull(ServiceDiscovery.parseServiceJson("""{"url":"http://127.0.0.1"}""")) // missing port
        assertNull(ServiceDiscovery.parseServiceJson("""{"hostname":"127.0.0.1"}""")) // missing port
        assertNull(ServiceDiscovery.parseServiceJson("""{"hostname":"127.0.0.1","port":70000}"""))
    }

    @Test
    fun returnsFirstHealthyCandidateAndSkipsUnparsableOnes() {
        val dir = createTempDirectory()
        val broken = File(dir, "a-service.json").apply { writeText("{ not json") }
        val registered = File(dir, "service.json").apply {
            writeText("""{"url":"http://127.0.0.1:49374","pid":123,"password":"secret"}""")
        }
        val server = ServiceDiscovery.findSharedServer(listOf(broken, registered)) { _, port, _ -> port == 49374 }
        assertEquals(49374, server?.port)
        assertEquals("secret", server?.password)
    }

    @Test
    fun returnsNullWhenNoCandidateIsHealthy() {
        val dir = createTempDirectory()
        val registered = File(dir, "service.json").apply {
            writeText("""{"url":"http://127.0.0.1:49374","pid":1,"password":"secret"}""")
        }
        val server = ServiceDiscovery.findSharedServer(listOf(registered)) { _, _, _ -> false }
        assertNull(server)
    }

    @Test
    fun unixCandidatesHonorXdgStateHomeAndFallBackToLocalState() {
        val candidates = ServiceDiscovery.serviceStateFileCandidates("/home/u", false) { key ->
            if (key == "XDG_STATE_HOME") "/xdg" else null
        }
        assertEquals(File("/xdg/opencode/service.json"), candidates.first())
        assertTrue(candidates.contains(File("/home/u/.local/state/opencode/service.json")))
    }

    @Test
    fun windowsCandidatesCoverLocalAppDataAndUserProfileConfig() {
        val candidates = ServiceDiscovery.serviceStateFileCandidates("C:\\Users\\dev", true) { key ->
            when (key) {
                "LOCALAPPDATA" -> "C:\\Users\\u\\AppData\\Local"
                "APPDATA" -> "C:\\Users\\u\\AppData\\Roaming"
                else -> null
            }
        }
        assertTrue(candidates.contains(File("C:\\Users\\u\\AppData\\Local\\opencode\\service.json")))
        assertTrue(candidates.contains(File("C:\\Users\\u\\AppData\\Roaming\\opencode\\service.json")))
        assertTrue(candidates.contains(File("C:\\Users\\dev\\.config\\opencode\\service.json")))
    }

    private fun createTempDirectory(): File =
        File(System.getProperty("java.io.tmpdir")).resolve("servicediscovery-${System.nanoTime()}").apply { mkdirs() }
}
