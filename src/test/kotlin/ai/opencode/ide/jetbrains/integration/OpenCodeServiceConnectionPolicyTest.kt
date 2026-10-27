package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.OpenCodeService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeServiceConnectionPolicyTest {
    @Test
    fun opensUiForAnExistingLocalServerEvenWhenTheConnectionWasHeadless() {
        assertTrue(OpenCodeService.shouldOpenUiForExistingConnection("127.0.0.1", requestedUi = false))
        assertTrue(OpenCodeService.shouldOpenUiForExistingConnection("localhost", requestedUi = false))
    }

    @Test
    fun preservesHeadlessModeForAnExistingRemoteServer() {
        assertFalse(OpenCodeService.shouldOpenUiForExistingConnection("opencode.example.test", requestedUi = false))
    }
}
