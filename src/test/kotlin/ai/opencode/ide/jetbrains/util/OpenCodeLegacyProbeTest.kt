package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeLegacyProbeTest {
    @Test
    fun reportsVersionOneWhenLegacyHealthEndpointAnswers() {
        assertTrue(OpenCodeLegacyProbe.isVersionOneServer("127.0.0.1", 4096) { 200 })
    }

    @Test
    fun ignoresServersWithoutTheLegacyEndpoint() {
        assertFalse(OpenCodeLegacyProbe.isVersionOneServer("127.0.0.1", 4096) { 404 })
    }

    @Test
    fun ignoresUnreachableEndpoints() {
        assertFalse(OpenCodeLegacyProbe.isVersionOneServer("127.0.0.1", 4096) { null })
    }

    @Test
    fun probesTheLegacyHealthPath() {
        var probed: String? = null
        OpenCodeLegacyProbe.isVersionOneServer("10.0.0.5", 5000) { url ->
            probed = url
            200
        }
        assertEquals("http://10.0.0.5:5000/global/health", probed)
    }
}
