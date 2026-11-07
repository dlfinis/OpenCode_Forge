package ai.opencode.ide.jetbrains.util

/** Shell-specific formatting for commands entered into the user's terminal. */
internal object OpenCodeTerminalCommand {
    enum class ShellFamily { CMD, POWERSHELL, POSIX, WSL }

    private val windowsDrivePath = Regex("^[A-Za-z]:[\\\\/].*")

    fun detectShell(shellPath: String?, windows: Boolean): ShellFamily {
        val path = shellPath?.trim()?.lowercase().orEmpty()
        return when {
            path.contains("wsl") -> ShellFamily.WSL
            path.contains("powershell") || path.contains("pwsh") -> ShellFamily.POWERSHELL
            path.contains("cmd.exe") || path == "cmd" -> ShellFamily.CMD
            path.contains("system32") && path.contains("bash") -> ShellFamily.WSL
            path.contains("bash") || path.contains("zsh") || path.contains("fish") ||
                path.contains("cygwin") || path.contains("msys") || path.contains("sh.exe") || path.contains("git") ->
                ShellFamily.POSIX
            else -> if (windows) ShellFamily.CMD else ShellFamily.POSIX
        }
    }

    fun buildWindowsCommand(
        binary: String,
        hostname: String,
        port: Int,
        password: String?,
        continueSession: Boolean,
        useServiceDiscoveryAuth: Boolean,
        shell: ShellFamily
    ): String {
        val executablePath = normalizeWindowsPath(binary, shell)
        val executable = when (shell) {
            ShellFamily.POWERSHELL -> if (isPath(binary)) "& ${powershellQuote(executablePath)}" else executablePath
            ShellFamily.POSIX, ShellFamily.WSL -> shellQuote(executablePath)
            ShellFamily.CMD -> windowsQuoteIfNeeded(executablePath)
        }
        val suffix = if (continueSession) " --continue" else ""
        if (useServiceDiscoveryAuth) return executable + suffix

        val connectHost = if (hostname == "0.0.0.0") "127.0.0.1" else hostname
        val url = "http://$connectHost:$port"
        val urlArgument = when (shell) {
            ShellFamily.POWERSHELL -> powershellQuote(url)
            ShellFamily.POSIX, ShellFamily.WSL -> shellQuote(url)
            ShellFamily.CMD -> windowsQuoteIfNeeded(url)
        }
        val command = "$executable --server $urlArgument$suffix"
        if (password.isNullOrBlank()) return command

        return when (shell) {
            ShellFamily.CMD -> {
                val safePassword = password.replace("\"", "\\\"")
                "cmd /c \"set \"OPENCODE_PASSWORD=$safePassword\" && " +
                    "set \"OPENCODE_SERVER_PASSWORD=$safePassword\" && $command\""
            }
            ShellFamily.POWERSHELL -> powershellWithCredentials(command, password)
            ShellFamily.POSIX, ShellFamily.WSL -> {
                val safePassword = shellEscape(password)
                "OPENCODE_PASSWORD='$safePassword' OPENCODE_SERVER_PASSWORD='$safePassword' $command"
            }
        }
    }

    private fun normalizeWindowsPath(binary: String, shell: ShellFamily): String {
        if (!windowsDrivePath.matches(binary)) return binary
        val normalized = binary.replace('\\', '/')
        if (shell != ShellFamily.WSL) return normalized
        val drive = normalized[0].lowercaseChar()
        return "/mnt/$drive${normalized.substring(2)}"
    }

    private fun isPath(binary: String): Boolean = binary.contains('/') || binary.contains('\\')

    private fun windowsQuoteIfNeeded(value: String): String =
        if (value.any(Char::isWhitespace)) "\"$value\"" else value

    private fun powershellQuote(value: String): String = "'${value.replace("'", "''")}'"

    private fun shellQuote(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9_./:-]+"))) value else "'${shellEscape(value)}'"

    private fun shellEscape(value: String): String = value.replace("'", "'\\''")

    private fun powershellWithCredentials(command: String, password: String): String {
        val safePassword = powershellQuote(password)
        return "\$__ocPassword = \$env:OPENCODE_PASSWORD; " +
            "\$__ocServerPassword = \$env:OPENCODE_SERVER_PASSWORD; " +
            "try { \$env:OPENCODE_PASSWORD = $safePassword; " +
            "\$env:OPENCODE_SERVER_PASSWORD = $safePassword; $command } finally { " +
            "if (\$null -eq \$__ocPassword) { Remove-Item Env:OPENCODE_PASSWORD -ErrorAction SilentlyContinue } " +
            "else { \$env:OPENCODE_PASSWORD = \$__ocPassword }; " +
            "if (\$null -eq \$__ocServerPassword) { " +
            "Remove-Item Env:OPENCODE_SERVER_PASSWORD -ErrorAction SilentlyContinue } " +
            "else { \$env:OPENCODE_SERVER_PASSWORD = \$__ocServerPassword } }"
    }
}
