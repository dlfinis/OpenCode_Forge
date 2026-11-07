package ai.opencode.ide.jetbrains.util

/** Selects a Windows command entry point that Java can launch from `where opencode` output. */
internal object OpenCodeCliResolver {
    private val runnableExtensions = listOf(".exe", ".com", ".cmd", ".bat")

    /** Keeps PATH order while skipping extensionless POSIX shims and PowerShell scripts. */
    fun selectWindowsExecutable(whereOutput: String): String? =
        whereOutput.lineSequence()
            .map(String::trim)
            .firstOrNull { path ->
                path.isNotEmpty() && runnableExtensions.any { extension -> path.endsWith(extension, ignoreCase = true) }
            }
}
