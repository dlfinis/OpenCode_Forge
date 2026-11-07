package ai.opencode.ide.jetbrains.util

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeHealthTest {
    @Test
    fun rejectsSuccessfulHttpResponsesWithoutOpenCodeInfo() {
        val result = OpenCodeHealthClassifier.fromResponse(200, "{\"service\":\"other\"}")

        assertTrue(result is OpenCodeHealthResult.ForeignService)
    }

    @Test
    fun classifiesUnauthorizedResponsesAsAuthenticationRequired() {
        assertEquals(OpenCodeHealthResult.AuthRequired(401), OpenCodeHealthClassifier.fromResponse(401, null))
    }

    @Test
    fun acceptsBareOpenCodeInfoObjects() {
        assertEquals(
            OpenCodeHealthResult.Healthy(OpenCodeServerInfo("2.3.1")),
            OpenCodeHealthClassifier.fromResponse(200, "{\"version\":\"2.3.1\",\"commit\":\"abc123\"}")
        )
    }

    @Test
    fun classifiesTimeoutsAsNotResponding() {
        assertTrue(OpenCodeHealthClassifier.fromFailure(SocketTimeoutException("read timed out")) is OpenCodeHealthResult.NotResponding)
    }
}
