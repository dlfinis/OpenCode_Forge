package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.OpenCodeService
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.history.Label
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Logic Unit Test using Fake Server.
 * Simplified suite covering core Diff scenarios.
 */
class OpenCodeLogicTest {
    private var eventId = 0

    private fun v2Event(type: String, data: String): String {
        eventId++
        val directory = System.getProperty("java.io.tmpdir").replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"id":"event-$eventId","type":"$type","created":"2026-10-22T00:00:00Z","data":$data,"location":{"directory":"$directory"}}"""
    }

    private fun v2SessionResponse(sessionId: String, directory: String): String {
        val escapedDirectory = directory.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"data":{"id":"$sessionId","projectID":"project-1","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"$escapedDirectory"}}}"""
    }

    private fun diffResponse(file: String, patch: String, additions: Int, deletions: Int, status: String = "modified"): String {
        val escapedPatch = patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")
        return """{"data":[{"file":"$file","patch":"$escapedPatch","additions":$additions,"deletions":$deletions,"status":"$status"}]}"""
    }

    private fun emptyDiffResponse(): String = """{"data":[]}"""
    
    @Before
    fun setUp() {
        OpenCodeService.DEBOUNCE_MS = 0L
    }
    
    @After
    fun tearDown() {
    }
    
    @Test
    fun testDiffScenarios() {
        println("=== Starting OpenCode Logic Tests (Diff Isolation) ===")
        
        // Setup Fake Server
        val server = FakeOpenCodeServer(0)
        server.start()
        val port = server.activePort
        
        // Setup Test Runner (Mock IDE)
        val runner = TestRunner(port)
        
        val r = runner
        val s = server
        s.setSessionResponse("s1", v2SessionResponse("s1", System.getProperty("java.io.tmpdir")))
        
        try {
            // Wait for connection to establish
            Thread.sleep(500)
            
            // --------------------------------------------------
            // TEST: Scenario A: Normal Turn (Modification)
            // --------------------------------------------------
            println("\n--------------------------------------------------")
            println("TEST: Scenario A: Normal Turn")
            println("--------------------------------------------------")
            
            r.resetState()
            
            // 1. Start Turn
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"busy"}}"""))
            Thread.sleep(100)
            
            // 2. Edit file
            s.broadcast(v2Event("file.edited", """{"file":"a.kt"}"""))
            
            // 3. Update Message
            s.broadcastSseEvent(
                "message.updated",
                """{"sessionID":"s1","info":{"id":"msg-1","type":"user"}}"""
            )
            
            // 4. End Turn
            s.setDiffResponse("msg-1", diffResponse("a.kt", "@@ -1 +1 @@\n-old\n+new\n", 1, 1))
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"idle"}}"""))
            
            // Verify
            r.waitForDiffs(1)
            r.assertDiffShown("a.kt")
            assertTrue(s.requestUris.contains("/api/session/s1/diff?from=msg-1"))
            println("✓ Passed")

            // --------------------------------------------------
            // TEST: Scenario C: Turn Isolation
            // --------------------------------------------------
            println("\n--------------------------------------------------")
            println("TEST: Scenario C: Turn Isolation")
            println("--------------------------------------------------")
            
            r.resetState()
            
            // Turn 1
            println("  Step 1: Turn 1 edits a.kt")
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"busy"}}"""))
            Thread.sleep(100)
            s.broadcast(v2Event("file.edited", """{"file":"a.kt"}"""))
            s.broadcast(v2Event("message.updated", """{"info":{"id":"msg-3","sessionID":"s1","role":"user"}}"""))
            
            s.setDiffResponse("msg-3", diffResponse("a.kt", "@@ -1 +1 @@\n-old\n+new\n", 1, 1))
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"idle"}}"""))
            
            r.waitForDiffs(1)
            r.assertDiffShown("a.kt")
            
            // Turn 2: Starts immediately
            println("  Step 2: Turn 2 starts (clearing real-time state)")
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"busy"}}"""))
            Thread.sleep(100)
            
            // Turn 2: Pure chat
            s.broadcast(v2Event("message.updated", """{"info":{"id":"msg-4","sessionID":"s1","role":"user"}}"""))
            s.setDiffResponse("msg-4", emptyDiffResponse())
            
            // Turn 2 Ends
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"idle"}}"""))
            
            r.waitForDiffs(0, timeoutMs = 1000)
            r.assertNoDiffsShown()
            println("✓ Passed")
            
            // --------------------------------------------------
            // TEST: Scenario F: Create File (Safety Check)
            // --------------------------------------------------
            println("\n--------------------------------------------------")
            println("TEST: Scenario F: Create File (Safety Check)")
            println("--------------------------------------------------")
            
            r.resetState()
            
            // 1. Start Turn
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"busy"}}"""))
            Thread.sleep(100)
            
            // 2. Simulate AI writing a new file to disk
            val tempDir = System.getProperty("java.io.tmpdir")
            val newFile = java.io.File(tempDir, "new.kt")
            newFile.writeText("fun new() {}")
            r.sessionManager.simulateFileCreation("new.kt") // Signal VFS creation
            
            s.broadcast(v2Event("file.edited", """{"file":"new.kt"}"""))
            s.broadcast(v2Event("message.updated", """{"info":{"id":"msg-8","sessionID":"s1","role":"user"}}"""))
            
            // 3. Server says it's a new file (before="", after="fun new() {}")
            s.setDiffResponse("msg-8", diffResponse("new.kt", "@@ -0,0 +1 @@\n+fun new() {}\n", 1, 0, "added"))
            
            // 4. End Turn
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"idle"}}"""))
            
            // 5. Verify
            r.waitForDiffs(1)
            r.assertDiffContent("new.kt", "") // Before should be empty!
            println("✓ Passed")
            
            newFile.delete()

            // --------------------------------------------------
            // TEST: Scenario G: User Edit Rescue Safety
            // --------------------------------------------------
            println("\n--------------------------------------------------")
            println("TEST: Scenario G: User Edit Rescue Safety")
            println("--------------------------------------------------")
            
            r.resetState()
            
            // 1. Start Turn
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"busy"}}"""))
            Thread.sleep(100)
            
            // 2. Simulate User editing a file
            r.sessionManager.simulateUserEdit("user_edited.kt")
            r.sessionManager.simulateVfsChange("user_edited.kt")
            
            // 3. AI edits another file
            s.broadcast(v2Event("file.edited", """{"file":"ai_edited.kt"}"""))
            s.broadcast(v2Event("message.updated", """{"info":{"id":"msg-9","sessionID":"s1","role":"user"}}"""))
            
            // 4. Server returns ONLY AI file
            s.setDiffResponse("msg-9", diffResponse("ai_edited.kt", "@@ -0,0 +1 @@\n+content\n", 1, 0, "added"))
            
            // 5. End Turn
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"idle"}}"""))
            
            // 6. Verify
            r.waitForDiffs(1)
            r.assertDiffShown("ai_edited.kt")
            
            // Verify user_edited.kt is NOT shown
            val diffs = r.mockDiffViewer.shownDiffs.lastOrNull() ?: emptyList()
            if (diffs.any { it.file == "user_edited.kt" }) {
                throw AssertionError("CRITICAL FAIL: User edited file was rescued as a Ghost Diff!")
            }
            println("✓ Passed")

            // --------------------------------------------------
            // TEST: Scenario L: Rescue Deletion
            // --------------------------------------------------
            println("\n--------------------------------------------------")
            println("TEST: Scenario L: Rescue Deletion (The Only Rescue)")
            println("--------------------------------------------------")
            
            r.resetState()
            
            // 1. Prepare file
            val toDelete = java.io.File(tempDir, "to_delete.kt")
            toDelete.writeText("delete me")
            
            // 2. Start Turn
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"busy"}}"""))
            Thread.sleep(100)
            Thread.sleep(100) // Wait for async onTurnStart
            
            // 2.5 Simulate capture happening during the turn
            r.sessionManager.simulateCapture("to_delete.kt", "delete me")
            
            // 3. Delete file physically
            val deleted = toDelete.delete()
            println("File deleted? $deleted. Exists? ${toDelete.exists()}")
            r.sessionManager.simulateVfsChange("to_delete.kt")
            
            // 4. Server returns NOTHING
            s.setDiffResponse("msg-14", emptyDiffResponse())
            s.broadcast(v2Event("message.updated", """{"info":{"id":"msg-14","sessionID":"s1","role":"user"}}"""))
            
            // 5. End Turn
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"idle"}}"""))
            
            // 6. Verify
            r.waitForDiffs(1)
            r.assertDiffShown("to_delete.kt")
            println("✓ Passed")

            // --------------------------------------------------
            // TEST: Scenario N: Server Authoritative (No VFS)
            // --------------------------------------------------
            println("\n--------------------------------------------------")
            println("TEST: Scenario N: Server Authoritative (With SSE, No VFS)")
            println("--------------------------------------------------")
            
            r.resetState()
            
            // 1. Start Turn
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"busy"}}"""))
            Thread.sleep(100)
            
            // 2. No VFS events! 
            // Use simulate to avoid race conditions in test
            r.sessionManager.simulateServerEdited("server_only.kt")
            
            // 3. End Turn (Server returns a diff)
            s.setDiffResponse("msg-16", diffResponse("server_only.kt", "@@ -1 +1 @@\n-a\n+b\n", 1, 1))
            s.broadcast(v2Event("message.updated", """{"info":{"id":"msg-16","sessionID":"s1","role":"user"}}"""))
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"idle"}}"""))
            Thread.sleep(100)
            
            // 4. Verify: Diff SHOWN
            r.waitForDiffs(1)
            r.assertDiffShown("server_only.kt")
            println("✓ Passed")

            // --------------------------------------------------
            // TEST: Scenario O: Create then Modify (Rescue)
            // --------------------------------------------------
            println("\n--------------------------------------------------")
            println("TEST: Scenario O: Create then Modify (Rescue)")
            println("--------------------------------------------------")
            
            r.resetState()
            
            // Turn 1: Create 1.md
            println("  Step 1: Create 1.md")
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"busy"}}"""))
            Thread.sleep(100)
            
            val file1 = java.io.File(tempDir, "1.md")
            file1.writeText("created content")
            r.sessionManager.simulateFileCreation("1.md")
            s.broadcast(v2Event("file.edited", """{"file":"1.md"}"""))
            s.broadcast(v2Event("message.updated", """{"info":{"id":"msg-17-a","sessionID":"s1","role":"user"}}"""))
            
            // Server might return diff or not. Let's say it returns nothing (Rescue Creation)
            s.setDiffResponse("msg-17-a", emptyDiffResponse())
            
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"idle"}}"""))
            
            r.waitForDiffs(1)
            r.assertDiffShown("1.md")
            
            // Turn 2: Modify 1.md
            println("  Step 2: Modify 1.md")
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"busy"}}"""))
            Thread.sleep(100)
            
            file1.writeText("modified content")
            // Crucially: NOT calling simulateFileCreation here, only VfsChange
            r.sessionManager.simulateVfsChange("1.md")
            s.broadcast(v2Event("file.edited", """{"file":"1.md"}"""))
            s.broadcast(v2Event("message.updated", """{"info":{"id":"msg-17-b","sessionID":"s1","role":"user"}}"""))
            
            // Server returns empty diffs again (Simulate API failure)
            s.setDiffResponse("msg-17-b", emptyDiffResponse())
            
            s.broadcast(v2Event("session.status", """{"sessionID":"s1","status":{"type":"idle"}}"""))
            
            // Verify diff is shown (Rescue Modification)
            r.waitForDiffs(1)
            r.assertDiffShown("1.md")
            
            // Cleanup
            file1.delete()
            println("✓ Passed")

            println("\nAll tests passed!")
            
        } catch (e: Throwable) {
            println("\n!!! TEST FAILED: ${e.message}")
            e.printStackTrace()
            throw e
        } finally {
            try {
                server.stop()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}

class TestRunner(val serverPort: Int) {
    val mockProject: Project
    val mockDiffViewer: MockDiffViewerService
    val sessionManager: TestSessionManager
    val openCodeService: OpenCodeService

    init {
        // Setup Mocks
        val tempDir = System.getProperty("java.io.tmpdir")
        mockProject = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> tempDir
                "isDisposed" -> false
                "toString" -> "MockProject"
                "hashCode" -> 12345
                "equals" -> false
                else -> null
            }
        } as Project

        mockDiffViewer = MockDiffViewerService(mockProject)
        sessionManager = TestSessionManager(mockProject)
        
        // Initialize Service with REAL ApiClient pointing to Fake Server
        val apiClient = OpenCodeApiClient("127.0.0.1", serverPort)
        
        openCodeService = OpenCodeService(mockProject)
        openCodeService.setTestDeps(sessionManager, mockDiffViewer, apiClient)
        
        // Mock UI execution
        openCodeService.invokeLater = { r -> r.run() }
        
        // Trigger connection manually
        val connectMethod = OpenCodeService::class.java.getDeclaredMethod("connectToSse")
        connectMethod.isAccessible = true
        connectMethod.invoke(openCodeService)
    }
    
    fun resetState() {
        sessionManager.clear()
        mockDiffViewer.shownDiffs.clear()
    }
    
    fun waitForDiffs(count: Int, timeoutMs: Long = 2000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (mockDiffViewer.shownDiffs.size >= count && count > 0) return
            if (count == 0 && mockDiffViewer.shownDiffs.isNotEmpty()) throw AssertionError("Expected 0 diffs, got ${mockDiffViewer.shownDiffs.size}")
            Thread.sleep(50)
        }
        if (mockDiffViewer.shownDiffs.size != count) {
            if (count == 0) return 
            throw AssertionError("Timeout waiting for diffs. Expected $count, got ${mockDiffViewer.shownDiffs.size}")
        }
    }
    
    fun assertDiffShown(file: String) {
        val shown = mockDiffViewer.shownDiffs.lastOrNull() ?: throw AssertionError("No diffs shown")
        val files = shown.map { it.file }
        if (file !in files) throw AssertionError("File $file not in shown diffs: $files")
        mockDiffViewer.shownDiffs.clear()
    }
    
    fun assertDiffContent(file: String, expectedBefore: String) {
        val shown = mockDiffViewer.shownDiffs.lastOrNull()
        if (shown == null) {
            System.err.println("!!! assertDiffContent FAILED: No diffs shown at all.")
            throw AssertionError("No diffs shown")
        }
        val diff = shown.firstOrNull { it.file == file }
        if (diff == null) {
            System.err.println("!!! assertDiffContent FAILED: File $file not found. Shown files: ${shown.map { it.file }}")
            throw AssertionError("File $file not in shown diffs. Shown: ${shown.map { it.file }}")
        }
        if (diff.beforeContent != expectedBefore) {
            System.err.println("!!! assertDiffContent FAILED: Content mismatch. Expected '$expectedBefore', got '${diff.beforeContent}'")
            throw AssertionError("Expected before '$expectedBefore', got '${diff.beforeContent}'")
        }
        mockDiffViewer.shownDiffs.clear()
    }
    
    fun assertNoDiffsShown() {
        if (mockDiffViewer.shownDiffs.isNotEmpty()) {
            throw AssertionError("Expected no diffs, but got: ${mockDiffViewer.shownDiffs.map { it.map { e -> e.file } }}")
        }
    }
}

class MockDiffViewerService(project: Project) : DiffViewerService(project) {
    val shownDiffs = CopyOnWriteArrayList<List<DiffEntry>>()
    
    override fun showMultiFileDiff(entries: List<DiffEntry>, initialIndex: Int?) {
        println("  [MockViewer] Showing diffs: ${entries.map { it.file }}")
        shownDiffs.add(entries)
    }
}

class TestSessionManager(project: Project) : SessionManager(project) {
    private val simulatedCreatedFiles = mutableSetOf<String>()
    private val simulatedVfsChangedFiles = mutableSetOf<String>()
    private val simulatedServerEditedFiles = mutableSetOf<String>()
    private val simulatedUserEditedFiles = mutableSetOf<String>()
    private val simulatedCapturedContent = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun createSystemLabel(name: String): Label? {
        return null
    }

    override fun onTurnStart(): Boolean {
        println("[TestSessionManager] onTurnStart CALLED")
        simulatedCreatedFiles.clear()
        simulatedVfsChangedFiles.clear()
        simulatedServerEditedFiles.clear()
        simulatedUserEditedFiles.clear()
        simulatedCapturedContent.clear()
        val res = super.onTurnStart()
        println("[TestSessionManager] onTurnStart RESULT: $res")
        return res
    }

    override fun onTurnEnd(): ai.opencode.ide.jetbrains.session.TurnSnapshot? {
        println("[TestSessionManager] onTurnEnd CALLED")
        val realSnapshot = super.onTurnEnd()
        if (realSnapshot == null) {
            println("[TestSessionManager] onTurnEnd: super returned null (isBusy=false?)")
            return null
        }

        // Merge simulated data into the snapshot
        val merged = realSnapshot.copy(
            aiCreatedFiles = realSnapshot.aiCreatedFiles + simulatedCreatedFiles,
            vfsChangedFiles = realSnapshot.vfsChangedFiles + simulatedVfsChangedFiles,
            serverEditedFiles = realSnapshot.serverEditedFiles + simulatedServerEditedFiles,
            userEditedFiles = realSnapshot.userEditedFiles + simulatedUserEditedFiles,
            capturedBeforeContent = realSnapshot.capturedBeforeContent + simulatedCapturedContent
        )
        println("[TestSessionManager] onTurnEnd merged snapshot:")
        println("[TestSessionManager]   aiCreatedFiles: ${merged.aiCreatedFiles}")
        println("[TestSessionManager]   vfsChangedFiles: ${merged.vfsChangedFiles}")
        println("[TestSessionManager]   serverEditedFiles: ${merged.serverEditedFiles}")
        println("[TestSessionManager]   userEditedFiles: ${merged.userEditedFiles}")
        println("[TestSessionManager]   capturedBeforeContent: ${merged.capturedBeforeContent.keys}")
        return merged
    }

    fun simulateUserEdit(file: String) {
        simulatedUserEditedFiles.add(file)
        simulatedVfsChangedFiles.add(file)
    }

    fun simulateCapture(path: String, content: String) {
        simulatedCapturedContent[path] = content
    }

    fun simulateFileCreation(file: String) {
        simulatedCreatedFiles.add(file)
        simulatedVfsChangedFiles.add(file)
    }

    fun simulateServerEdited(file: String) {
        simulatedServerEditedFiles.add(file)
    }

    fun simulateVfsChange(file: String) {
        simulatedVfsChangedFiles.add(file)
    }
}
