package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeVersionGateTest {
    @Test
    fun warnsOnceWhenTheConnectedServerIsNotVersionTwo() {
        assertTrue(OpenCodeVersionGate.shouldWarn("1.5.0", warningAlreadyShown = false))
        assertFalse(OpenCodeVersionGate.shouldWarn("1.5.0", warningAlreadyShown = true))
    }

    @Test
    fun doesNotWarnForVersionTwoOrUnparseableVersions() {
        assertFalse(OpenCodeVersionGate.shouldWarn("2.1.0", warningAlreadyShown = false))
        assertFalse(OpenCodeVersionGate.shouldWarn("unknown", warningAlreadyShown = false))
    }
}
