package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeTerminalCommandTest {
    @Test
    fun detectsWindowsShellFamiliesFromTheConfiguredShellPath() {
        assertEquals(
            OpenCodeTerminalCommand.ShellFamily.CMD,
            OpenCodeTerminalCommand.detectShell("C:\\Windows\\System32\\cmd.exe", true)
        )
        assertEquals(
            OpenCodeTerminalCommand.ShellFamily.POWERSHELL,
            OpenCodeTerminalCommand.detectShell("pwsh.exe", true)
        )
        assertEquals(
            OpenCodeTerminalCommand.ShellFamily.POSIX,
            OpenCodeTerminalCommand.detectShell("C:\\Program Files\\Git\\bin\\bash.exe", true)
        )
        assertEquals(OpenCodeTerminalCommand.ShellFamily.WSL, OpenCodeTerminalCommand.detectShell("wsl.exe", true))
        assertEquals(OpenCodeTerminalCommand.ShellFamily.POSIX, OpenCodeTerminalCommand.detectShell(null, false))
    }

    @Test
    fun writesWindowsPathsWithForwardSlashesInCmd() {
        val command = windowsCommand(
            binary = "C:\\Program Files\\OpenCode\\opencode.exe",
            shell = OpenCodeTerminalCommand.ShellFamily.CMD
        )

        assertEquals(
            "\"C:/Program Files/OpenCode/opencode.exe\" --server http://127.0.0.1:4096 --continue",
            command
        )
    }

    @Test
    fun usesPowerShellCallOperatorForPathsWithSpaces() {
        val command = windowsCommand(
            binary = "C:\\Program Files\\OpenCode\\opencode.exe",
            shell = OpenCodeTerminalCommand.ShellFamily.POWERSHELL
        )

        assertEquals(
            "& 'C:/Program Files/OpenCode/opencode.exe' --server 'http://127.0.0.1:4096' --continue",
            command
        )
    }

    @Test
    fun formatsWindowsExecutablePathsForBashAndWsl() {
        val bash = windowsCommand(
            binary = "C:\\Program Files\\OpenCode\\opencode.exe",
            shell = OpenCodeTerminalCommand.ShellFamily.POSIX
        )
        val wsl = windowsCommand(
            binary = "C:\\Program Files\\OpenCode\\opencode.exe",
            shell = OpenCodeTerminalCommand.ShellFamily.WSL
        )

        assertEquals(
            "'C:/Program Files/OpenCode/opencode.exe' --server http://127.0.0.1:4096 --continue",
            bash
        )
        assertEquals(
            "'/mnt/c/Program Files/OpenCode/opencode.exe' --server http://127.0.0.1:4096 --continue",
            wsl
        )
    }

    @Test
    fun scopesPowerShellCredentialsAndRestoresPreviousEnvironment() {
        val command = windowsCommand(
            binary = "C:\\OpenCode\\opencode.exe",
            password = "secret'password",
            shell = OpenCodeTerminalCommand.ShellFamily.POWERSHELL
        )

        assertTrue(command.contains("\$env:OPENCODE_PASSWORD = 'secret''password'"))
        assertTrue(command.contains("& 'C:/OpenCode/opencode.exe'"))
        assertTrue(command.contains("Remove-Item Env:OPENCODE_SERVER_PASSWORD"))
    }

    private fun windowsCommand(
        binary: String,
        password: String? = null,
        shell: OpenCodeTerminalCommand.ShellFamily
    ): String = OpenCodeTerminalCommand.buildWindowsCommand(
        binary = binary,
        hostname = "127.0.0.1",
        port = 4096,
        password = password,
        continueSession = true,
        useServiceDiscoveryAuth = false,
        shell = shell
    )
}
