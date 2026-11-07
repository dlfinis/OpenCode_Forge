package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeCliResolverTest {
    @Test
    fun skipsExtensionlessShimsAndSelectsTheFirstRunnablePath() {
        val output = """
            C:\Users\dev\node\bin\opencode
            C:\Users\dev\node\bin\opencode.cmd
            C:\Program Files\OpenCode\opencode.exe
        """.trimIndent()

        assertEquals("C:\\Users\\dev\\node\\bin\\opencode.cmd", OpenCodeCliResolver.selectWindowsExecutable(output))
    }

    @Test
    fun honorsWherePathOrder() {
        val output = "C:\\Users\\dev\\npm\\opencode.exe\r\nC:\\tools\\opencode.cmd\r\n"

        assertEquals("C:\\Users\\dev\\npm\\opencode.exe", OpenCodeCliResolver.selectWindowsExecutable(output))
    }

    @Test
    fun acceptsRunnableExtensionsWithoutCaseSensitivity() {
        assertEquals("C:\\tools\\opencode.CMD", OpenCodeCliResolver.selectWindowsExecutable("C:\\tools\\opencode.CMD"))
        assertEquals("C:\\tools\\opencode.bat", OpenCodeCliResolver.selectWindowsExecutable("C:\\tools\\opencode.bat"))
    }

    @Test
    fun ignoresExtensionsJavaCannotLaunchAsAWindowsExecutable() {
        val output = """
            C:\Users\dev\npm\opencode.ps1
            C:\Users\dev\npm\opencode
            C:\tools.cmd\opencode
        """.trimIndent()

        assertNull(OpenCodeCliResolver.selectWindowsExecutable(output))
    }

    @Test
    fun returnsNullWhenWhereFindsNoRunnableEntry() {
        assertNull(OpenCodeCliResolver.selectWindowsExecutable("\r\n  \r\n"))
    }
}
