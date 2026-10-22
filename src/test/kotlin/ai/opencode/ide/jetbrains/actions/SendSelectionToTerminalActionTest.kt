package ai.opencode.ide.jetbrains.actions

import ai.opencode.ide.jetbrains.OpenCodeService
import ai.opencode.ide.jetbrains.SendSelectionToTerminalAction
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.integration.FakeOpenCodeServer
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SendSelectionToTerminalActionTest : BasePlatformTestCase() {

    private var server: FakeOpenCodeServer? = null

    override fun setUp() {
        super.setUp()
        // Setup Fake Server on random port
        server = FakeOpenCodeServer(0).apply { start() }
    }

    override fun tearDown() {
        try {
            server?.stop()
        } finally {
            super.tearDown()
        }
    }

    fun testDirectorySelectionSendsDirectoryPath() {
        val port = server?.activePort ?: 0
        val service = project.service<OpenCodeService>()
        val sm = project.service<SessionManager>()
        val dvs = project.service<DiffViewerService>()
        val client = OpenCodeApiClient("127.0.0.1", port)

        // Inject dependencies with real API client talking to fake server
        service.setTestDeps(sm, dvs, client)
        val pastedDraft = AtomicReference<String>()
        service.terminalDraftWriterForTests = { draft ->
            pastedDraft.set(draft)
            true
        }

        // Force connection state
        // We use reflection to set 'isConnected' to true so 'focusOrCreateTerminalAndPaste' 
        // believes we are connected and proceeds to paste.
        val connectedField = OpenCodeService::class.java.getDeclaredField("isConnected")
        connectedField.isAccessible = true
        (connectedField.get(service) as AtomicBoolean).set(true)
        
        // Also simulate port running so 'focusOrCreateTerminal' checks pass
        val portField = OpenCodeService::class.java.getDeclaredField("port")
        portField.isAccessible = true
        portField.set(service, port)

        // 1. Setup Virtual File System
        val dir = myFixture.tempDirFixture.findOrCreateDir("root/mydir")
        myFixture.tempDirFixture.createFile("root/mydir/file1.txt")
        myFixture.tempDirFixture.createFile("root/mydir/file2.txt")

        // 2. Create Action Event with Directory Selected
        val action = SendSelectionToTerminalAction()
        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.PROJECT, project)
        dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(dir))
        dataContext.put(CommonDataKeys.EDITOR, null)

        val event = AnActionEvent.createFromDataContext("place", null, dataContext)

        // 3. Perform Action
        action.actionPerformed(event)

        // 4. Verify
        // Wait for the terminal draft writer to receive the context (async)
        val start = System.currentTimeMillis()
        while (pastedDraft.get() == null && System.currentTimeMillis() - start < 2000) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }

        val draft = pastedDraft.get()
        assertNotNull("Context should be inserted into the terminal draft", draft)
        assertTrue("Should contain directory path", draft.contains("mydir"))
        assertFalse("Should NOT contain file1", draft.contains("file1.txt"))
        assertFalse("Should NOT contain file2", draft.contains("file2.txt"))
        assertFalse("Draft insertion must not submit the prompt", draft.endsWith("\n"))
    }
}
