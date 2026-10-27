package ai.opencode.ide.jetbrains.actions

import ai.opencode.ide.jetbrains.OpenCodeService
import ai.opencode.ide.jetbrains.SendSelectionToTerminalAction
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.integration.FakeOpenCodeServer
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
        val pastedDraft = AtomicReference<String>()
        configureTerminalWriter { draft ->
            pastedDraft.set(draft)
            true
        }

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

    fun testEditorContextRemainsVisibleWithoutVirtualFileData() {
        myFixture.configureByText("Example.java", "class Example {}")
        val event = editorEvent(myFixture.editor)

        SendSelectionToTerminalAction().update(event)

        assertTrue("Editor action should be visible without VIRTUAL_FILE data", event.presentation.isEnabledAndVisible)
    }

    fun testEditorPopupIsVisibleWithoutEditorOrVirtualFileData() {
        val event = eventAt(ActionPlaces.EDITOR_POPUP)

        SendSelectionToTerminalAction().update(event)

        assertTrue("Editor popup action should be visible without editor data", event.presentation.isEnabledAndVisible)
    }

    fun testMainMenuAndFindActionPlacesRemainVisibleWithoutEditorData() {
        listOf(ActionPlaces.MAIN_MENU, ActionPlaces.ACTION_SEARCH).forEach { place ->
            val event = eventAt(place)

            SendSelectionToTerminalAction().update(event)

            assertTrue("Action should remain visible in $place", event.presentation.isEnabledAndVisible)
        }
    }

    fun testEditorPopupUsesSelectedTextEditorWhenEditorDataIsAbsent() {
        val source = "class Example {\n    void selectedMethod() {}\n}\n"
        myFixture.configureByText("Example.java", source)
        val selectedText = "void selectedMethod() {}"
        val selectionStart = source.indexOf(selectedText)
        myFixture.editor.selectionModel.setSelection(selectionStart, selectionStart + selectedText.length)
        assertNotNull("The fixture editor should be the selected text editor", FileEditorManager.getInstance(project).selectedTextEditor)

        val pastedDraft = AtomicReference<String>()
        configureTerminalWriter { draft -> pastedDraft.set(draft); true }

        SendSelectionToTerminalAction().actionPerformed(eventAt(ActionPlaces.EDITOR_POPUP))

        val draft = awaitValue(pastedDraft)
        assertTrue("The active editor selection should be referenced", draft.endsWith("Example.java#L2-2 "))
        assertFalse("The selected source must not be embedded", draft.contains(selectedText))
    }

    fun testMainMenuAndFindActionUseSelectedTextEditorWhenEditorDataIsAbsent() {
        val source = "class Example {\n    void selectedMethod() {}\n}\n"
        myFixture.configureByText("Example.java", source)
        val selectedText = "void selectedMethod() {}"
        val selectionStart = source.indexOf(selectedText)
        myFixture.editor.selectionModel.setSelection(selectionStart, selectionStart + selectedText.length)

        listOf(ActionPlaces.MAIN_MENU, ActionPlaces.ACTION_SEARCH).forEach { place ->
            val pastedDraft = AtomicReference<String>()
            configureTerminalWriter { draft -> pastedDraft.set(draft); true }

            SendSelectionToTerminalAction().actionPerformed(eventAt(place))

            assertTrue("$place should use the active editor", awaitValue(pastedDraft).endsWith("Example.java#L2-2 "))
        }
    }

    fun testProjectViewSelectionTakesPrecedenceOverEditorData() {
        myFixture.configureByText("Selected.java", "class Selected {}")
        val selectedFile = myFixture.file.virtualFile
        val pastedDraft = AtomicReference<String>()
        configureTerminalWriter { draft -> pastedDraft.set(draft); true }

        SendSelectionToTerminalAction().actionPerformed(
            eventAt(ActionPlaces.PROJECT_VIEW_POPUP, myFixture.editor, arrayOf(selectedFile))
        )

        val draft = awaitValue(pastedDraft)
        assertTrue("Project View should send the selected file reference", draft.endsWith("Selected.java "))
        assertFalse("Project View selection must not be replaced by the editor's class reference", draft.contains("#L"))
    }

    fun testSelectedRangeSendsOnlyItsReference() {
        val source = "class Example {\n    void selectedMethod() {}\n}\n"
        myFixture.configureByText("Example.java", source)
        val selectedText = "void selectedMethod() {}"
        val selectionStart = source.indexOf(selectedText)
        myFixture.editor.selectionModel.setSelection(selectionStart, selectionStart + selectedText.length)

        val pastedDraft = AtomicReference<String>()
        val action = SendSelectionToTerminalAction()
        val copiedReference = AtomicReference<String>()
        action.clipboardWriterForTests = { copiedReference.set(it); true }
        configureTerminalWriter { draft -> pastedDraft.set(draft); true }

        action.actionPerformed(editorEvent(myFixture.editor))

        val draft = awaitValue(pastedDraft)
        assertTrue("The selected range reference should retain its line range", draft.endsWith("Example.java#L2-2 "))
        assertTrue("The draft should contain only an @-reference", draft.startsWith("@"))
        assertFalse("Selected source must not be embedded in the terminal draft", draft.contains(selectedText))
        assertNull("Successful terminal insertion must not copy to the clipboard", copiedReference.get())
    }

    fun testCaretInsideJavaClassSendsClassRange() {
        val source = "class Example {\n    void run() {\n        int marker = 1;\n    }\n}\n"
        myFixture.configureByText("Example.java", source)
        myFixture.editor.caretModel.moveToOffset(source.indexOf("marker"))
        val pastedDraft = AtomicReference<String>()
        configureTerminalWriter { draft -> pastedDraft.set(draft); true }

        SendSelectionToTerminalAction().actionPerformed(editorEvent(myFixture.editor))

        val draft = awaitValue(pastedDraft)
        assertTrue("The terminal draft should contain an @-reference", draft.startsWith("@"))
        assertTrue("The enclosing Java class range should be referenced", draft.endsWith("Example.java#L1-5 "))
    }

    fun testCaretInsideKotlinClassSendsClassRange() {
        val source = "class Example {\n    fun run() {\n        val marker = 1\n    }\n}\n"
        myFixture.configureByText("Example.kt", source)
        myFixture.editor.caretModel.moveToOffset(source.indexOf("marker"))
        val pastedDraft = AtomicReference<String>()
        configureTerminalWriter { draft -> pastedDraft.set(draft); true }

        SendSelectionToTerminalAction().actionPerformed(editorEvent(myFixture.editor))

        val draft = awaitValue(pastedDraft)
        assertTrue("The terminal draft should contain an @-reference", draft.startsWith("@"))
        assertTrue("The enclosing Kotlin class range should be referenced", draft.endsWith("Example.kt#L1-5 "))
    }

    fun testCaretOutsideClassFallsBackToWholeFileReference() {
        val source = "plain text at the caret\n"
        myFixture.configureByText("Notes.txt", source)
        myFixture.editor.caretModel.moveToOffset(source.indexOf("caret"))
        val pastedDraft = AtomicReference<String>()
        configureTerminalWriter { draft -> pastedDraft.set(draft); true }

        SendSelectionToTerminalAction().actionPerformed(editorEvent(myFixture.editor))

        val draft = awaitValue(pastedDraft)
        assertTrue("The terminal draft should contain an @-reference", draft.startsWith("@"))
        assertTrue("Non-class files should fall back to a whole-file reference", draft.endsWith("Notes.txt "))
        assertFalse("A non-class file reference should not include a line range", draft.contains("#L"))
    }

    fun testUnavailableTerminalCopiesReferenceAndNotifies() {
        val source = "plain text at the caret\n"
        myFixture.configureByText("Notes.txt", source)
        myFixture.editor.caretModel.moveToOffset(source.indexOf("caret"))
        val copiedReference = AtomicReference<String>()
        val notified = AtomicReference<Boolean>()
        val attemptedDraft = AtomicReference<String>()
        val action = SendSelectionToTerminalAction()
        action.clipboardWriterForTests = { copiedReference.set(it); true }
        action.clipboardFallbackNotifierForTests = { _, copied -> notified.set(copied) }
        action.terminalDeliveryForTests = { draft, onFailure ->
            attemptedDraft.set(draft)
            onFailure()
        }

        action.actionPerformed(editorEvent(myFixture.editor))

        waitForCondition { copiedReference.get() != null && notified.get() != null }
        assertTrue("The unavailable terminal received the unsent reference draft", attemptedDraft.get().endsWith("Notes.txt "))
        assertTrue("The copied fallback should be an @-reference", copiedReference.get().startsWith("@"))
        assertTrue("The copied fallback should be the exact file reference", copiedReference.get().endsWith("Notes.txt"))
        assertEquals("The clipboard fallback should omit only the draft's trailing space", attemptedDraft.get().trimEnd(), copiedReference.get())
        assertEquals(true, notified.get())
    }

    fun testFailedDraftInsertionCopiesReferenceAndNotifies() {
        val source = "class Example {\n    void run() {}\n}\n"
        myFixture.configureByText("Example.java", source)
        myFixture.editor.caretModel.moveToOffset(source.indexOf("run"))
        val copiedReference = AtomicReference<String>()
        val notified = AtomicReference<Boolean>()
        val action = SendSelectionToTerminalAction()
        action.clipboardWriterForTests = { copiedReference.set(it); true }
        action.clipboardFallbackNotifierForTests = { _, copied -> notified.set(copied) }
        configureTerminalWriter { false }

        action.actionPerformed(editorEvent(myFixture.editor))

        waitForCondition { copiedReference.get() != null && notified.get() != null }
        assertTrue("The copied fallback should be an @-reference", copiedReference.get().startsWith("@"))
        assertTrue("The copied fallback should reference the exact class range", copiedReference.get().endsWith("Example.java#L1-3"))
        assertEquals(true, notified.get())
    }

    private fun configureTerminalWriter(writer: (String) -> Boolean) {
        val port = server?.activePort ?: 0
        val service = project.service<OpenCodeService>()
        service.setTestDeps(
            project.service<SessionManager>(),
            project.service<DiffViewerService>(),
            OpenCodeApiClient("127.0.0.1", port)
        )
        service.terminalDraftWriterForTests = writer
    }

    private fun editorEvent(editor: Editor): AnActionEvent {
        return eventAt(ActionPlaces.EDITOR_POPUP, editor)
    }

    private fun eventAt(
        place: String,
        editor: Editor? = null,
        virtualFiles: Array<VirtualFile>? = null
    ): AnActionEvent {
        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.PROJECT, project)
        if (editor != null) dataContext.put(CommonDataKeys.EDITOR, editor)
        if (virtualFiles != null) dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, virtualFiles)
        return AnActionEvent.createFromDataContext(place, null, dataContext)
    }

    private fun awaitValue(value: AtomicReference<String>, timeoutMillis: Long = 5000): String {
        waitForCondition(timeoutMillis) { value.get() != null }
        return value.get() ?: error("The expected terminal draft was not inserted")
    }

    private fun waitForCondition(timeoutMillis: Long = 5000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMillis) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(25)
        }
        assertTrue("Timed out waiting for asynchronous terminal handling", condition())
    }
}
