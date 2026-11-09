package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.OpenCodeService
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.lang.reflect.Proxy

class OpenCodeServiceCommandTest {
    @Test
    fun includesBothCredentialVariablesWithShellEscapedPassword() {
        assumeFalse(System.getProperty("os.name", "").lowercase().contains("windows"))

        val command = newService().buildOpenCodeCommand(
            command = "/opt/Open Code/opencode",
            h = "127.0.0.1",
            p = 4096,
            pwd = "s3cure'pw",
            cont = true
        )

        assertEquals(
            "OPENCODE_PASSWORD='s3cure'\\''pw' OPENCODE_SERVER_PASSWORD='s3cure'\\''pw' " +
                "'/opt/Open Code/opencode' --server 'http://127.0.0.1:4096' --continue",
            command
        )
    }

    @Test
    fun omitsCredentialVariablesWhenNoPasswordIsProvided() {
        assumeFalse(System.getProperty("os.name", "").lowercase().contains("windows"))

        val command = newService().buildOpenCodeCommand(
            command = "/usr/local/bin/opencode",
            h = "127.0.0.1",
            p = 4096,
            pwd = null,
            cont = false
        )

        assertEquals("'/usr/local/bin/opencode' --server 'http://127.0.0.1:4096'", command)
        assertFalse(command.contains("OPENCODE_PASSWORD"))
        assertFalse(command.contains("OPENCODE_SERVER_PASSWORD"))
        assertFalse(command.contains("s3cure"))
    }

    @Test
    fun omitsServerUrlAndCredentialsWhenUsingServiceDiscoveryAuth() {
        assumeFalse(System.getProperty("os.name", "").lowercase().contains("windows"))

        val command = newService().buildOpenCodeCommand(
            command = "/usr/local/bin/opencode",
            h = "127.0.0.1",
            p = 49374,
            pwd = "should-not-appear",
            cont = true,
            useServiceDiscoveryAuth = true
        )

        assertEquals("'/usr/local/bin/opencode' --continue", command)
        assertFalse(command.contains("OPENCODE_PASSWORD"))
        assertFalse(command.contains("OPENCODE_SERVER_PASSWORD"))
        assertFalse(command.contains("should-not-appear"))
        assertFalse(command.contains("--server"))
    }

    @Test
    fun resumesASpecificSessionInsteadOfTheLastOne() {
        assumeFalse(System.getProperty("os.name", "").lowercase().contains("windows"))

        val command = newService().buildOpenCodeCommand(
            command = "/usr/local/bin/opencode",
            h = "127.0.0.1",
            p = 4096,
            pwd = null,
            cont = true,
            sessionId = "ses_abc123"
        )

        assertEquals(
            "'/usr/local/bin/opencode' --server 'http://127.0.0.1:4096' --session 'ses_abc123'",
            command
        )
        assertFalse(command.contains("--continue"))
    }

    @Test
    fun buildsAnHttpsServerUrlWhenTheConnectionUsesTls() {
        assumeFalse(System.getProperty("os.name", "").lowercase().contains("windows"))

        val command = newService().buildOpenCodeCommand(
            command = "/usr/local/bin/opencode",
            h = "opencode.example.test",
            p = 443,
            pwd = null,
            cont = false,
            scheme = "https"
        )

        assertEquals(
            "'/usr/local/bin/opencode' --server 'https://opencode.example.test:443'",
            command
        )
    }

    private fun newService(): OpenCodeService {
        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            if (method.name == "toString") "MockProject" else null
        } as Project
        return OpenCodeService(project)
    }
}
