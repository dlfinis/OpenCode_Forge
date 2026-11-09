package ai.opencode.ide.jetbrains.session

import com.intellij.openapi.project.Project
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Guards the path filter that keeps build output and IDE internals out of the diff pipeline.
 *
 * The case that surfaced it: a runIde sandbox living inside the project it opened, which made the
 * IDE's own indexes look like source changes and captured megabytes nobody edited.
 */
class SessionManagerPathFilterTest {

    private fun manager(): SessionManager {
        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ -> if (method.name == "toString") "MockProject" else null } as Project
        return SessionManager(project)
    }

    @Test
    fun tracksSourceFiles() {
        val filter = manager()
        assertTrue(filter.isTrackablePath("src/main/kotlin/Foo.kt"))
        assertTrue(filter.isTrackablePath("README.md"))
    }

    @Test
    fun ignoresBuildOutputAndIdeInternals() {
        val filter = manager()
        assertFalse(filter.isTrackablePath("build/classes/Foo.class"))
        assertFalse(filter.isTrackablePath(".intellijPlatform/sandbox/index/storage"))
        assertFalse(filter.isTrackablePath(".gradle/configuration-cache/entry"))
        assertFalse(filter.isTrackablePath(".idea/workspace.xml"))
        assertFalse(filter.isTrackablePath("node_modules/pkg/index.js"))
    }

    @Test
    fun doesNotConfuseSimilarNamesWithExcludedDirectories() {
        val filter = manager()
        // A file named like the directory, and a nested directory that merely shares the name.
        assertTrue(filter.isTrackablePath(".gitignore"))
        assertTrue(filter.isTrackablePath("build.gradle.kts"))
        assertTrue(filter.isTrackablePath("docs/build/notes.md"))
    }

    @Test
    fun handlesWindowsSeparators() {
        val filter = manager()
        assertFalse(filter.isTrackablePath("build\\classes\\Foo.class"))
        assertTrue(filter.isTrackablePath("src\\main\\Foo.kt"))
    }
}
