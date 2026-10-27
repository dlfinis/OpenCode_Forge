package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.OpenCodeService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

class OpenCodeServiceEndpointSuggestionTest : BasePlatformTestCase() {
    private val lastAddressKey = "opencode.lastAddress"
    private var previousAddress: String = ""

    override fun setUp() {
        super.setUp()
        previousAddress = PropertiesComponent.getInstance().getValue(lastAddressKey, "")
    }

    override fun tearDown() {
        val properties = PropertiesComponent.getInstance()
        if (previousAddress.isBlank()) {
            properties.unsetValue(lastAddressKey)
        } else {
            properties.setValue(lastAddressKey, previousAddress)
        }
        super.tearDown()
    }

    fun testLiveRememberedLocalServerRemainsTheSuggestedEndpoint() {
        val server = FakeOpenCodeServer(0)
        server.start()
        PropertiesComponent.getInstance().setValue(lastAddressKey, "localhost:${server.activePort}")

        try {
            assertEquals(
                "localhost:${server.activePort}",
                OpenCodeService(project).resolveSuggestedAddress()
            )
        } finally {
            server.stop()
        }
    }

    fun testPreservesExplicitlyRememberedRemoteEndpoint() {
        PropertiesComponent.getInstance().setValue(lastAddressKey, "opencode.example.test:4096")

        assertEquals(
            "opencode.example.test:4096",
            OpenCodeService(project).resolveSuggestedAddress()
        )
    }
}
