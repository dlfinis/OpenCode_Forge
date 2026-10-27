package ai.opencode.ide.jetbrains.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeConnectDialogAddressTest {
    @Test
    fun parsesRememberedHostnameAndPort() {
        assertEquals(ServerAddress("localhost", 4096), OpenCodeConnectDialog.parseAddress(" localhost:4096 "))
    }

    @Test
    fun ignoresMalformedRememberedAddresses() {
        assertNull(OpenCodeConnectDialog.parseAddress("https://example.com"))
        assertNull(OpenCodeConnectDialog.parseAddress("127.0.0.1:70000"))
    }

    @Test
    fun prefersAnExplicitlyRememberedRemoteAddressOverLocalDiscovery() {
        val remembered = ServerAddress("opencode.example.test", 4096)

        assertEquals("opencode.example.test:4096", chooseConnectionAddress(remembered, 4097))
    }

    @Test
    fun reusesDiscoveredLocalServerInsteadOfStaleRememberedLocalAddress() {
        val remembered = ServerAddress("127.0.0.1", 4096)

        assertEquals("127.0.0.1:4097", chooseConnectionAddress(remembered, 4097))
    }

    @Test
    fun retainsRememberedLocalAddressWhenNoServerIsLive() {
        val remembered = ServerAddress("localhost", 4096)

        assertEquals("localhost:4096", chooseConnectionAddress(remembered, null))
    }

    @Test
    fun choosesDiscoveredLocalServerWhenNoAddressWasRemembered() {
        assertEquals("127.0.0.1:4096", chooseConnectionAddress(null, 4096))
        assertNull(chooseConnectionAddress(null, null))
    }
}
