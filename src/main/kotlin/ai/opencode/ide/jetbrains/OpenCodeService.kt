package ai.opencode.ide.jetbrains

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.SseEventListener
import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.session.TurnSnapshot
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalFileEditor
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalFileEditorProvider
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalLinkFilter
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalVirtualFile
import ai.opencode.ide.jetbrains.ui.OpenCodeConnectDialog
import ai.opencode.ide.jetbrains.ui.chooseConnectionAddress
import ai.opencode.ide.jetbrains.util.PathUtil
import ai.opencode.ide.jetbrains.util.PortFinder
import ai.opencode.ide.jetbrains.util.ProcessAuthDetector
import ai.opencode.ide.jetbrains.util.ServiceDiscovery
import ai.opencode.ide.jetbrains.web.OpenCodeWebVirtualFile
import ai.opencode.ide.jetbrains.web.WebModeSupport

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SystemNotifications
import com.intellij.util.concurrency.AppExecutorUtil

import org.jetbrains.plugins.terminal.TerminalView

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(OpenCodeService::class.java)

    private enum class ConnectionMode { NONE, TERMINAL, WEB, REMOTE }

    companion object {
        private const val OPEN_CODE_TAB_PREFIX = "OpenCode"
        private const val RETRY_INTERVAL_MS = 5000L
        private const val BARRIER_TIMEOUT_MS = 2000L
        private const val GENERATED_PASSWORD_PROPERTY = "opencode.generatedPassword"
        private const val PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val GENERATED_PASSWORD_LENGTH = 24
        private val PASSWORD_RANDOM = SecureRandom()
        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "0.0.0.0")
        private val LOCAL_CONNECTION_HOSTS = setOf("127.0.0.1", "localhost", "0.0.0.0")
        internal var DEBOUNCE_MS = 1500L

        internal fun shouldOpenUiForExistingConnection(hostname: String, requestedUi: Boolean): Boolean =
            requestedUi || hostname in LOCAL_CONNECTION_HOSTS
    }

    private var hostname: String = "127.0.0.1"
    private var port: Int? = null
    private var username: String? = null
    private var password: String? = null
    private var apiClient: OpenCodeApiClient? = null
    private var sseListener: SseEventListener? = null
    @Volatile private var localServerProcess: OSProcessHandler? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    @Volatile private var reviveAttempted = false
    private var lastMode: ConnectionMode = ConnectionMode.NONE
    private var wasEverConnected = false
    private var remoteReconnectFailures = 0
    private var remoteReconnectDialogShown = false

    private var terminalVirtualFile: OpenCodeTerminalVirtualFile? = null
    private var terminalEditor: OpenCodeTerminalFileEditor? = null
    private var terminalEndpoint: String? = null
    private var webVirtualFile: OpenCodeWebVirtualFile? = null

    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var connectionManagerTask: ScheduledFuture<*>? = null
    @Volatile private var lastIdleNotification: Notification? = null
    
    // Turn state: keyed by sessionId
    private val turnMessageIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val pendingUserMessageIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val turnPendingPayloads = java.util.concurrent.ConcurrentHashMap<String, List<FileDiff>>()
    private val turnSnapshots = java.util.concurrent.ConcurrentHashMap<String, TurnSnapshot>()
    private val turnIdleWaiting = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val turnBarrierTasks = java.util.concurrent.ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val turnLastTriggerTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val activeSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val turnIdleHandled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val sessionManager: SessionManager get() = _sessionManager ?: project.service()
    private val diffViewerService: DiffViewerService get() = _diffViewerService ?: project.service()

    // Test hooks
    private var _sessionManager: SessionManager? = null
    private var _diffViewerService: DiffViewerService? = null
    internal var terminalDraftWriterForTests: ((String) -> Boolean)? = null

    internal fun setTestDeps(sm: SessionManager, dvs: DiffViewerService, client: OpenCodeApiClient) {
        _sessionManager = sm
        _diffViewerService = dvs
        apiClient = client
    }
    
    internal var invokeLater: (Runnable) -> Unit = { 
        ApplicationManager.getApplication().invokeLater(it) 
    }

    // ==================== Public API ====================

    fun focusOrCreateTerminal(interactive: Boolean = false) {
        val uiOpen = hasTerminalUI()
        val running = port?.let { PortFinder.isOpenCodeRunningOnPort(it, hostname, username, password) } ?: false
        if (isConnected.get() && !running) isConnected.set(false)
        
        when {
            isConnected.get() && uiOpen -> if (interactive) focusTerminalUI()
            interactive && port != null -> showReconnectOrNewDialog(running)
            !interactive && running && port != null -> restoreUiForMode()
            !interactive && !running && port != null -> showDisconnectedDialog(false)
            else -> showConnectionDialog()
        }
    }

    fun hasTerminalUI(): Boolean {
        val tf = terminalVirtualFile
        if (tf != null && FileEditorManager.getInstance(project).openFiles.contains(tf)) return true
        val wf = webVirtualFile
        if (wf != null && FileEditorManager.getInstance(project).openFiles.contains(wf)) return true
        return false
    }

    fun stopLocalServer() {
        AppExecutorUtil.getAppExecutorService().submit {
            val activePort = port ?: return@submit
            if (hostname !in LOCAL_HOSTS) {
                logger.warn("[OpenCode] Stop action ignored for remote server $hostname:$activePort")
                return@submit
            }
            terminateLocalServerOnPort(activePort)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                disconnectAndReset(terminateServer = false)
                sendNotification(
                    "OpenCode Server Stopped",
                    "The OpenCode server on :$activePort was stopped."
                )
            }
        }
    }

    private fun showDisconnectedDialog(interactive: Boolean) {
        if (!interactive || lastMode == ConnectionMode.NONE) return
        ApplicationManager.getApplication().invokeLater {
            val res = Messages.showYesNoCancelDialog(
                project,
                "Server at $hostname:$port not running. Restart?",
                "OpenCode",
                "Restart",
                "New",
                "Cancel",
                Messages.getWarningIcon()
            )
            if (res == Messages.YES) restartServer(lastMode)
            else if (res == Messages.NO) {
                disconnectAndReset(terminateServer = false)
                showConnectionDialog()
            }
        }
    }

    fun focusOrCreateTerminalAndPaste(text: String, onPasteFailure: (() -> Unit)? = null) {
        if (text.isBlank() || project.isDisposed) return
        if (apiClient == null && terminalEditor == null) {
            ApplicationManager.getApplication().invokeLater {
                if (onPasteFailure != null) {
                    onPasteFailure()
                } else {
                    Messages.showInfoMessage(project, "OpenCode not running.", "OpenCode")
                }
            }
            return
        }
        schedulePasteAttempt(text, 20, 100L, onPasteFailure)
        ApplicationManager.getApplication().invokeLater {
            if (terminalDraftWriterForTests != null) return@invokeLater
            if (terminalVirtualFile == null) {
                val activePort = port ?: return@invokeLater
                createTerminalUIInternal(hostname, activePort, password, false)
            } else {
                focusTerminalUI()
            }
        }
    }

    fun pasteToTerminal(text: String): Boolean {
        if (text.isBlank()) return false
        terminalDraftWriterForTests?.let { return it(text) }
        val widget = terminalEditor?.terminalWidget ?: return false
        return try {
            widget.executeWithTtyConnector { connector -> connector.write(text) }
            true
        } catch (e: Exception) {
            logger.warn("[Paste] Failed to insert context into the terminal draft", e)
            false
        }
    }

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener); listener(isConnected.get())
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) { connectionListeners.remove(listener) }

    fun onTerminalDisposed() {
        if (isConnected.get() || port != null) {
            logger.info("[OpenCode] Terminal disposed (timeout or closed). Resetting connection state.")
            disconnectAndReset(terminateServer = false)
        }
    }

    // ==================== Event Handling & Barrier ====================

    private fun handleEvent(event: OpenCodeEvent) {
        if (event !is MessagePartUpdatedEvent && event !is UnknownEvent) logger.info("[OpenCode] SSE: ${event.type}")
        when (event) {
            is SessionStatusEvent -> {
                val sId = event.properties.sessionID
                val status = event.properties.status
                if (status.isBusy()) {
                    if (activeSessionIds.add(sId)) {
                        val pendingMessageId = pendingUserMessageIds.remove(sId)
                        val started = sessionManager.onTurnStart()
                        if (started) clearTurnState(sId)
                        if (pendingMessageId != null) turnMessageIds[sId] = pendingMessageId
                        turnIdleHandled.remove(sId)
                    }
                } else if (status.isIdle()) {
                    handleSessionIdle(sId, "status event")
                }
            }
            is SessionIdleEvent -> {
                handleSessionIdle(event.properties.sessionID, "idle event")
            }
            is FileEditedEvent -> sessionManager.onFileEdited(event.properties.file)
            is MessageUpdatedEvent -> {
                val info = event.properties.info
                val sessionId = event.properties.sessionID ?: info.sessionID
                if ((info.role ?: info.type) == "user" && sessionId != null) {
                    pendingUserMessageIds[sessionId] = info.id
                    if (activeSessionIds.contains(sessionId)) {
                        pendingUserMessageIds.remove(sessionId, info.id)
                        recordTurnUserMessageId(sessionId, info.id)
                    }
                }
            }
            is SessionDiffEvent -> if (event.properties.diff.isNotEmpty()) {
                turnPendingPayloads[event.properties.sessionID] = event.properties.diff
                attemptBarrierTrigger(event.properties.sessionID)
            }
            else -> {}
        }
    }

    private fun clearTurnState(sessionId: String) {
        val oldSnapshot = turnSnapshots.remove(sessionId)
        turnMessageIds.remove(sessionId)
        pendingUserMessageIds.remove(sessionId)
        turnPendingPayloads.remove(sessionId)
        turnIdleWaiting.remove(sessionId)
        turnBarrierTasks.remove(sessionId)?.cancel(false)
        turnLastTriggerTimes.remove(sessionId)
        
        if (oldSnapshot != null) {
            logger.info("[OpenCode] Turn state cleared (was Turn #${oldSnapshot.turnNumber})")
        } else {
            logger.info("[OpenCode] Turn state cleared (no previous snapshot)")
        }
    }

    private fun handleSessionIdle(sessionId: String, source: String) {
        if (!turnIdleHandled.add(sessionId)) return
        activeSessionIds.remove(sessionId)

        // Capture snapshot BEFORE attempting the diff barrier.
        val snapshot = sessionManager.onTurnEnd()
        if (snapshot != null) {
            turnSnapshots[sessionId] = snapshot
            logger.info("[OpenCode] Turn #${snapshot.turnNumber} snapshot captured ($source)")
        }
        turnIdleWaiting[sessionId] = true
        attemptBarrierTrigger(sessionId)
        if (snapshot != null) {
            sendNotification(
                "OpenCode Task Completed",
                "Session is now idle. Checking for changes...",
                replacePrevious = true
            )
        }
    }

    private fun recordTurnUserMessageId(sessionId: String, messageId: String) {
        if (turnMessageIds.containsKey(sessionId)) return
        turnMessageIds[sessionId] = messageId
        val turnNum = turnSnapshots[sessionId]?.turnNumber ?: sessionManager.getCurrentTurnNumber()
        logger.info("[OpenCode] Turn #$turnNum UserMessageID: $messageId")
        attemptBarrierTrigger(sessionId)
    }

    private fun attemptBarrierTrigger(sessionId: String) {
        val ready = turnIdleWaiting[sessionId] == true
        val hasId = turnMessageIds.containsKey(sessionId)
        val hasPayload = turnPendingPayloads.containsKey(sessionId)
        val snapshot = turnSnapshots[sessionId]
        
        logger.debug("[OpenCode] Barrier check: ready=$ready, hasId=$hasId, hasPayload=$hasPayload, hasSnapshot=${snapshot != null}")
        
        if (ready && (hasId || hasPayload)) {
            turnIdleWaiting[sessionId] = false
            turnBarrierTasks.remove(sessionId)?.cancel(false)
            if (snapshot != null) {
                logger.info("[OpenCode] Turn #${snapshot.turnNumber} Barrier triggered → fetching diffs")
                triggerDiffFetch(sessionId, snapshot)
            } else {
                logger.warn("[OpenCode] Barrier triggered but no snapshot available!")
            }
        } else if (ready) {
            scheduleBarrierTimeout(sessionId)
        }
    }

    private fun scheduleBarrierTimeout(sessionId: String) {
        if (turnBarrierTasks.containsKey(sessionId)) return
        val turnNum = turnSnapshots[sessionId]?.turnNumber ?: "?"
        logger.debug("[OpenCode] Turn #$turnNum Barrier timeout scheduled (${BARRIER_TIMEOUT_MS}ms)")
        val task = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            val snapshot = turnSnapshots[sessionId]
            if (turnIdleWaiting[sessionId] == true && snapshot != null) {
                logger.warn("[OpenCode] Turn #${snapshot.turnNumber} Barrier timeout → forcing diff fetch")
                turnIdleWaiting[sessionId] = false
                triggerDiffFetch(sessionId, snapshot)
            }
            turnBarrierTasks.remove(sessionId)
        }, BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        turnBarrierTasks[sessionId] = task
    }

    private fun triggerDiffFetch(sessionId: String, snapshot: TurnSnapshot) {
        val now = System.currentTimeMillis()
        if (now - (turnLastTriggerTimes[sessionId] ?: 0) < DEBOUNCE_MS) {
            logger.debug("[OpenCode] Turn #${snapshot.turnNumber} Debounced (too soon)")
            return
        }
        turnLastTriggerTimes[sessionId] = now
        fetchAndShowDiffs(sessionId, snapshot)
    }

    private fun forceVfsRefresh(diffs: List<FileDiff>) {
        if (diffs.isEmpty()) return
        try {
            val files = diffs.mapNotNull { diff -> 
                PathUtil.resolveProjectPath(project, diff.file)?.let { 
                    val ioFile = java.io.File(it)
                    // If file is deleted, we must refresh the parent directory to detect deletion
                    if (!ioFile.exists()) ioFile.parentFile else ioFile
                } 
            }
            if (files.isNotEmpty()) {
                logger.info("[OpenCode] Forcing VFS refresh for ${files.size} paths: ${files.map { it.absolutePath }}")
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshIoFiles(files, false, false, null)
            }
        } catch (e: Exception) {
            logger.debug("[OpenCode] VFS refresh skipped: ${e.message}")
        }
    }

    private fun fetchAndShowDiffs(sessionId: String, snapshot: TurnSnapshot) {
        val client = apiClient ?: return
        val path = project.basePath ?: return
        val userMessageId = turnMessageIds[sessionId]
        val payload = turnPendingPayloads[sessionId]
        
        logger.info("[OpenCode] Turn #${snapshot.turnNumber} fetchAndShowDiffs: userMessageId=$userMessageId, payloadSize=${payload?.size ?: 0}")
        
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                var diffs: List<FileDiff> = emptyList()
                
                // V2 `from` refers to the user message that started this turn.
                if (userMessageId != null) {
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Fetching diffs from user message: $userMessageId")
                    diffs = client.getSessionDiff(sessionId, path, userMessageId)
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Server returned ${diffs.size} diffs: ${diffs.map { "${it.file}(+${it.additions}/-${it.deletions})" }}")
                }
                
                // Priority 2: Use cached SSE payload
                if (diffs.isEmpty() && payload != null) {
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Using SSE payload (${payload.size} files): ${payload.map { it.file }}")
                    diffs = payload
                }

                if (diffs.isEmpty() && userMessageId == null && payload == null) {
                    logger.warn("[OpenCode] Turn #${snapshot.turnNumber} No user message ID or SSE payload; trying the latest session diff")
                    diffs = client.getSessionDiff(sessionId, path)
                }
                
                // 1. Force VFS refresh for Server files and Known files BEFORE processing
                // This ensures we catch deletions of files that Server missed but AI previously touched.
                // We rely on Server Authoritative logic: if Server missed a file and we have no history of it, we skip it.
                val serverFiles = diffs.map { it.file }
                val knownFiles = sessionManager.getKnownFilePaths()
                val filesToRefresh = (serverFiles + knownFiles).distinct()
                
                if (filesToRefresh.isNotEmpty()) {
                    // Create dummy FileDiffs just to pass the filename
                    forceVfsRefresh(filesToRefresh.map { FileDiff(it, "", "", 0, 0) })
                }
                
                // Capture any late VFS events triggered by the refresh
                val lateVfsEvents = sessionManager.getLiveVfsChangedFiles()
                val lateAiCreatedFiles = sessionManager.getLiveAiCreatedFiles()

                // Process using the snapshot via SessionManager (centralized logic)
                // Pass late VFS events to help with affinity checks
                val entries = sessionManager.getProcessedDiffs(diffs, snapshot, lateVfsEvents, lateAiCreatedFiles)
                
                if (entries.isNotEmpty()) {
                    sessionManager.updateKnownState(entries.map { it.file })
                    
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Showing ${entries.size} diffs")
                    invokeLater {
                        if (!project.isDisposed) diffViewerService.showMultiFileDiff(entries)
                    }
                } else {
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} No diffs to show after processing.")
                }
            } catch (e: Exception) {
                logger.error("[OpenCode] Turn #${snapshot.turnNumber} Diff fetch error", e)
            } finally {
                turnPendingPayloads.remove(sessionId)
            }
        }
    }
    
    // createSyntheticDiff removed - logic moved to SessionManager


    // ==================== Lifecycle & Connection ====================

    private fun initializeApiClient(host: String, port: Int) {
        val apiHost = if (host == "0.0.0.0") "127.0.0.1" else host
        apiClient = OpenCodeApiClient(apiHost, port, username, password)
    }

    private fun startConnectionManager() {
        if (connectionManagerTask?.isDone == false || port == null || apiClient == null) return
        connectionManagerTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ if (!project.isDisposed) tryConnect() }, 0, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun tryConnect() {
        if (isConnected.get() || !isConnecting.compareAndSet(false, true)) return
        try {
            apiClient?.let {
                if (it.checkHealth(project.basePath!!)) {
                    remoteReconnectFailures = 0
                    reviveAttempted = false
                    wasEverConnected = true
                    connectToSse()
                } else {
                    handleConnectionFailure()
                }
            }
        } catch (_: Exception) {
            handleConnectionFailure()
        } finally {
            isConnecting.set(false)
        }
    }

    private fun handleConnectionFailure() {
        if (lastMode == ConnectionMode.NONE || !wasEverConnected) return
        if (attemptAutomaticRevive()) return
        if (++remoteReconnectFailures >= 2 && !remoteReconnectDialogShown) { remoteReconnectDialogShown = true; showRemoteReconnectDialog() }
    }

    private fun attemptAutomaticRevive(): Boolean {
        if (lastMode != ConnectionMode.TERMINAL && lastMode != ConnectionMode.WEB) return false
        val reviveHost = hostname
        if (reviveHost !in LOCAL_HOSTS || reviveAttempted) return false
        val revivePort = port ?: return false
        if (!PortFinder.isPortAvailable(revivePort)) return false

        reviveAttempted = true
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                val activePort = port ?: return@submit
                if (activePort != revivePort || hostname != reviveHost || !PortFinder.isPortAvailable(activePort)) {
                    return@submit
                }

                // v2 architecture: if the shared background service is alive on a different
                // port, reattach to it instead of spawning a private replacement server.
                val shared = ServiceDiscovery.findSharedServer()
                if (shared != null && shared.port != activePort) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        lastMode = if (lastMode == ConnectionMode.WEB) ConnectionMode.WEB else ConnectionMode.TERMINAL
                        connectToExistingServer(shared.hostname, shared.port, ProcessAuthDetector.ServerAuth("opencode", shared.password), true, false)
                        sendNotification(
                            "OpenCode Server Reattached",
                            "Reattached to the shared OpenCode service on :${shared.port}."
                        )
                    }
                    return@submit
                }

                val binary = detectOpenCodeBinary() ?: return@submit
                val serverPassword = password ?: resolveServerPassword(null)
                val hostForListen = if (reviveHost == "0.0.0.0") "127.0.0.1" else reviveHost
                if (!startLocalServer(binary, hostForListen, activePort, serverPassword)) {
                    logger.warn("[OpenCode] Automatic server restart could not start OpenCode")
                    return@submit
                }
                if (!PortFinder.waitForPort(activePort, hostForListen, timeoutMs = 30000, requireHealth = true)) {
                    logger.warn("[OpenCode] Automatic server restart timed out for $hostForListen:$activePort")
                    return@submit
                }

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || port != activePort || hostname != reviveHost) return@invokeLater

                    password = serverPassword
                    username = if (serverPassword.isNullOrBlank()) null else "opencode"
                    initializeApiClient(hostForListen, activePort)

                    terminalVirtualFile?.let { oldTerminalFile ->
                        FileEditorManager.getInstance(project).closeFile(oldTerminalFile)
                        OpenCodeTerminalFileEditorProvider.cancelDisposal(oldTerminalFile)
                        OpenCodeTerminalFileEditorProvider.disposeWidget(oldTerminalFile, null)
                        terminalVirtualFile = null
                        terminalEditor = null
                        terminalEndpoint = null
                        createTerminalUIInternal(hostForListen, activePort, serverPassword, true)
                    }

                    sendNotification(
                        "OpenCode Server Restarted",
                        "The server on :$activePort was restarted automatically."
                    )
                }
            } catch (e: Exception) {
                logger.warn("[OpenCode] Automatic server restart failed", e)
            }
        }
        return true
    }

    private fun showRemoteReconnectDialog() {
        ApplicationManager.getApplication().invokeLater {
            if (isConnected.get()) return@invokeLater
            val result = Messages.showYesNoCancelDialog(
                project,
                "Connection lost. Reconnect?",
                "OpenCode",
                "Reconnect",
                "New",
                "Cancel",
                Messages.getWarningIcon()
            )
            if (result == Messages.NO) {
                disconnectAndReset(terminateServer = false)
                showConnectionDialog()
            }
        }
    }

    private fun connectToSse() {
        sseListener?.disconnect()
        val path = project.basePath ?: return
        apiClient?.let { client ->
            sseListener = client.createEventListener(
                path,
                { handleEvent(it) },
                { updateConnectionState(false) },
                {
                    updateConnectionState(true)
                    reconcileSessionStatuses(client, path)
                },
                { updateConnectionState(false) }
            ).apply { connect() }
        }
    }

    private fun reconcileSessionStatuses(client: OpenCodeApiClient, path: String) {
        AppExecutorUtil.getAppExecutorService().submit {
            val statuses = client.getActiveSessionStatuses(path) ?: return@submit
            val activeNow = statuses.filter { it.status.isBusy() }.mapTo(mutableSetOf()) { it.sessionID }
            val previouslyActive = activeSessionIds.toSet()

            (activeNow - previouslyActive).forEach { sessionId ->
                handleEvent(
                    SessionStatusEvent(
                        properties = SessionStatusProperties(sessionId, SessionStatusType("running"))
                    )
                )
            }
            (previouslyActive - activeNow).forEach { sessionId ->
                handleEvent(
                    SessionStatusEvent(
                        properties = SessionStatusProperties(sessionId, SessionStatusType("idle"))
                    )
                )
            }
        }
    }

    private fun updateConnectionState(connected: Boolean) { if (isConnected.getAndSet(connected) != connected) connectionListeners.forEach { it(connected) } }
    private fun sendNotification(
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
        replacePrevious: Boolean = false
    ) {
        val time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val message = "[$time] $content"
        try {
            invokeLater {
                try {
                    if (project.isDisposed) return@invokeLater
                    if (replacePrevious) lastIdleNotification?.expire()
                    val notification = NotificationGroupManager.getInstance()
                        .getNotificationGroup("OpenCode")
                        .createNotification(title, message, type)
                        .setImportant(true)
                    if (replacePrevious) lastIdleNotification = notification
                    notification.notify(project)
                    try {
                        SystemNotifications.getInstance().notify("OpenCode", title, message)
                    } catch (e: Throwable) {
                        logger.debug("[OpenCode] System notification failed: ${e.message}")
                    }
                } catch (e: Throwable) {
                    logger.warn("[OpenCode] Notification unavailable; event processing will continue", e)
                }
            }
        } catch (e: Throwable) {
            logger.warn("[OpenCode] Notification unavailable; event processing will continue", e)
        }
    }

    override fun dispose() {
        disconnectAndReset(terminateServer = false)
        OpenCodeTerminalFileEditorProvider.clearAll()
    }

    private fun disconnectAndReset(terminateServer: Boolean = true) {
        connectionManagerTask?.cancel(true); sseListener?.disconnect(); isConnected.set(false); isConnecting.set(false)
        turnMessageIds.clear(); pendingUserMessageIds.clear(); turnPendingPayloads.clear(); turnSnapshots.clear(); turnIdleWaiting.clear();
        activeSessionIds.clear(); turnIdleHandled.clear(); turnBarrierTasks.values.forEach { it.cancel(false) }; turnBarrierTasks.clear()
        if (terminateServer) terminateProcess() else detachLocalServerProcess()
        terminalVirtualFile?.let { OpenCodeTerminalFileEditorProvider.disposeWidget(it, null) }
        terminalVirtualFile = null; terminalEditor = null; terminalEndpoint = null; webVirtualFile = null; port = null; hostname = "127.0.0.1"; apiClient = null
    }

    private fun detachLocalServerProcess() {
        if (localServerProcess?.process?.isAlive == true) localServerProcess = null
    }

    private fun terminateProcess() {
        try {
            val serverHandler = localServerProcess
            localServerProcess = null
            if (serverHandler != null) {
                serverHandler.destroyProcess()
                val serverProcess = serverHandler.process
                if (serverProcess.isAlive && !serverProcess.waitFor(2, TimeUnit.SECONDS)) {
                    serverProcess.destroyForcibly()
                }
            }

            val process = terminalEditor?.terminalWidget?.processTtyConnector?.process
            if (process?.isAlive == true) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        } catch (_: Exception) {}
    }

    private fun showReconnectOrNewDialog(running: Boolean) {
        val msg = if (running) "Session active. Restore UI?" else "Server dead. Restart?"
        ApplicationManager.getApplication().invokeLater {
            val res = Messages.showYesNoCancelDialog(project, msg, "OpenCode", if (running) "Restore" else "Restart", "New", "Cancel", Messages.getQuestionIcon())
            if (res == Messages.YES) if (running) restoreUiForMode() else restartServer(lastMode)
            else if (res == Messages.NO) { disconnectAndReset(terminateServer = false); showConnectionDialog() }
        }
    }

    private fun showConnectionDialog() {
        AppExecutorUtil.getAppExecutorService().submit {
            val suggestedAddress = resolveSuggestedAddress()
            ApplicationManager.getApplication().invokeLater {
                OpenCodeConnectDialog.show(project, suggestedAddress)?.let {
                    processConnectionChoice(it.hostname, it.port, it.password, it.useWebInterface, it.customBasePath) 
                }
            }
        }
    }

    internal fun resolveSuggestedAddress(): String {
        val rememberedAddress = OpenCodeConnectDialog.getRememberedAddress()
        val rememberedServerIsLive = rememberedAddress?.takeIf { it.isLocal }?.let {
            val probeHost = if (it.hostname == "0.0.0.0") "127.0.0.1" else it.hostname
            PortFinder.isOpenCodeRunningOnPort(it.port, probeHost)
        } == true
        val discoveredLocalPort = when {
            rememberedServerIsLive -> rememberedAddress?.port
            rememberedAddress == null || rememberedAddress.isLocal ->
                // Prefer the OpenCode v2 shared background service; fall back to a port scan.
                ServiceDiscovery.findSharedServer()?.port ?: PortFinder.findRunningOpenCodeServer()
            else -> null
        }

        return chooseConnectionAddress(rememberedAddress, discoveredLocalPort)
            ?: "127.0.0.1:${PortFinder.findAvailablePort()}"
    }

    private fun processConnectionChoice(h: String, p: Int, pwd: String?, web: Boolean, customBasePath: String? = null) {
        val safeH = h.trim().ifBlank { "127.0.0.1" }
        val local = safeH in listOf("0.0.0.0", "127.0.0.1", "localhost")
        
        AppExecutorUtil.getAppExecutorService().submit {
            val shared = if (local) ServiceDiscovery.findSharedServer() else null
            val a = when {
                !pwd.isNullOrBlank() -> ProcessAuthDetector.ServerAuth("opencode", pwd)
                // The v2 shared service publishes its password in service.json; reuse it
                // instead of asking the user to restart a server the plugin doesn't own.
                local && shared?.port == p -> ProcessAuthDetector.ServerAuth("opencode", shared.password)
                local -> ProcessAuthDetector.detectAuthForPort(p)
                else -> ProcessAuthDetector.ServerAuth("opencode", null)
            }
            val running = if (local) PortFinder.isOpenCodeRunningOnPort(p, safeH, a.username, a.password) else true
            val occupied = if (local && !running) !PortFinder.isPortAvailable(p) else false
            val requiresManagedPassword = local && running && pwd.isNullOrBlank() && a.password == null &&
                PortFinder.isAuthRequiredOnPort(p, safeH)

            ApplicationManager.getApplication().invokeLater {
                if (requiresManagedPassword) {
                    val choice = Messages.showYesNoDialog(
                        project,
                        "The OpenCode server on :$p requires a password the plugin doesn't control. " +
                            "Restart it with a plugin-managed password?",
                        "Connection Warning",
                        "Restart",
                        "Connect Anyway",
                        Messages.getWarningIcon()
                    )
                    if (choice == Messages.YES) {
                        val resolvedPassword = resolveServerPassword(pwd)
                        AppExecutorUtil.getAppExecutorService().submit {
                            terminateLocalServerOnPort(p)
                            ApplicationManager.getApplication().invokeLater {
                                if (project.isDisposed) return@invokeLater
                                lastMode = if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                                val managedAuth = ProcessAuthDetector.ServerAuth("opencode", resolvedPassword)
                                if (web) {
                                    createWebTerminal(safeH, p, managedAuth.password, customBasePath)
                                } else {
                                    createLocalTerminal(safeH, p, managedAuth.password, customBasePath)
                                }
                            }
                        }
                    } else {
                        connectToExistingServer(safeH, p, a, web, web, customBasePath)
                    }
                } else if (running) {
                    lastMode = if (!local) ConnectionMode.REMOTE else if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                    // Keep remote connections headless unless web mode is requested.
                    // Attach to an existing local server and open its terminal or web UI; do not start another server.
                    connectToExistingServer(safeH, p, a, web, web, customBasePath)
                } else if (occupied) {
                    val choice = Messages.showYesNoDialog(
                        project,
                        "Port $p is in use but did not pass the health check.\nIt might be an older OpenCode version or a different service.\n\nTry to connect anyway?",
                        "Connection Warning",
                        "Connect Anyway",
                        "Cancel",
                        Messages.getWarningIcon()
                    )
                    if (choice == Messages.YES) {
                        lastMode = if (!local) ConnectionMode.REMOTE else if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                        connectToExistingServer(safeH, p, a, web, web, customBasePath)
                    } else {
                        showConnectionDialog()
                    }
                } else {
                    // Not running and not occupied -> Start new server
                    // v2 architecture: prefer attaching to the shared background service
                    // instead of spawning a second private server on this machine.
                    if (shared != null) {
                        lastMode = if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                        connectToExistingServer(shared.hostname, shared.port, ProcessAuthDetector.ServerAuth("opencode", shared.password), web, web, customBasePath)
                    } else if (!local) {
                         // Safety net: If remote host is not running, we cannot "start" it locally.
                         Messages.showErrorDialog(project, "Cannot connect to remote server $safeH:$p (Not reachable).", "Connection Failed")
                         showConnectionDialog()
                    } else {
                        lastMode = if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                        val resolvedPassword = resolveServerPassword(a.password)
                        val managedAuth = ProcessAuthDetector.ServerAuth("opencode", resolvedPassword)
                        if (web) {
                            createWebTerminal(safeH, p, managedAuth.password, customBasePath)
                        } else {
                            createLocalTerminal(safeH, p, managedAuth.password, customBasePath)
                        }
                    }
                }
            }
        }
    }

    private fun connectToExistingServer(h: String, p: Int, a: ProcessAuthDetector.ServerAuth, ui: Boolean, web: Boolean, customBasePath: String? = null) {
        hostname = h; port = p; username = a.username; password = a.password
        if (shouldOpenUiForExistingConnection(h, ui)) {
            if (web) createWebUI(h, p) else createTerminalUIInternal(h, p, a.password, false, customBasePath)
        }
        else { ApplicationManager.getApplication().invokeLater { Messages.showInfoMessage(project, "Connected to $h:$p", "OpenCode") } }
        initializeApiClient(h, p); startConnectionManager()
    }

    private fun createWebUI(h: String, p: Int) {
        val connectHost = if (h == "0.0.0.0") "127.0.0.1" else h
        webVirtualFile = WebModeSupport.openWebTab(project, connectHost, p, password) {}
    }

    private fun createWebTerminal(h: String, p: Int, pwd: String?, customBasePath: String? = null) {
        val serverPassword = resolveServerPassword(pwd)
        AppExecutorUtil.getAppExecutorService().submit {
            val bin = detectOpenCodeBinary()
            if (bin == null) {
                showCliNotFoundError()
                return@submit
            }
            val connectHost = if (h == "0.0.0.0") "127.0.0.1" else h
            // Attach to an already-running server (e.g. the v2 shared service) instead of spawning a duplicate.
            if (!PortFinder.isOpenCodeRunningOnPort(p, connectHost) && !startLocalServer(bin, h, p, serverPassword)) {
                showCliNotFoundError()
                return@submit
            }
            if (!PortFinder.waitForPort(p, connectHost, timeoutMs = 30000, requireHealth = true)) {
                logger.warn("[OpenCode] Initial waitForPort (Web) timed out for $h:$p, starting ConnectionManager anyway")
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    detachLocalServerProcess()
                    return@invokeLater
                }
                hostname = h
                port = p
                username = if (serverPassword.isNullOrBlank()) null else "opencode"
                password = serverPassword
                lastMode = ConnectionMode.WEB
                createTerminalUIInternal(h, p, serverPassword, true, customBasePath)
                createWebUI(h, p)
                initializeApiClient(h, p)
                startConnectionManager()
            }
        }
    }

    private fun createLocalTerminal(h: String, p: Int, pwd: String?, customBasePath: String? = null) {
        val safeH = h.trim().ifBlank { "127.0.0.1" }
        if (safeH !in listOf("0.0.0.0", "127.0.0.1", "localhost")) {
            // Guard: Never attempt to spawn local terminal for remote host
            connectToExistingServer(safeH, p, ProcessAuthDetector.ServerAuth("opencode", pwd), false, false, customBasePath)
            return
        }
        val serverPassword = resolveServerPassword(pwd)
        AppExecutorUtil.getAppExecutorService().submit {
            val bin = detectOpenCodeBinary()
            if (bin == null) {
                showCliNotFoundError()
                return@submit
            }
            val connectHost = if (safeH == "0.0.0.0") "127.0.0.1" else safeH
            // Attach to an already-running server (e.g. the v2 shared service) instead of spawning a duplicate.
            if (!PortFinder.isOpenCodeRunningOnPort(p, connectHost) && !startLocalServer(bin, safeH, p, serverPassword)) {
                showCliNotFoundError()
                return@submit
            }
            if (!PortFinder.waitForPort(p, connectHost, timeoutMs = 30000)) {
                logger.warn("[OpenCode] Initial waitForPort timed out for $h:$p, starting ConnectionManager anyway")
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    detachLocalServerProcess()
                    return@invokeLater
                }
                hostname = safeH
                port = p
                username = if (serverPassword.isNullOrBlank()) null else "opencode"
                password = serverPassword
                lastMode = ConnectionMode.TERMINAL
                createTerminalUIInternal(safeH, p, serverPassword, true, customBasePath)
                initializeApiClient(safeH, p)
                startConnectionManager()
            }
        }
    }

    private fun resolveServerPassword(manual: String?): String? {
        if (!manual.isNullOrBlank()) {
            username = "opencode"
            return manual
        }

        val properties = PropertiesComponent.getInstance()
        val storedPassword = runCatching {
            val encoded = properties.getValue(GENERATED_PASSWORD_PROPERTY, "")
            if (encoded.isBlank()) null else String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (storedPassword != null) {
            username = "opencode"
            return storedPassword
        }

        val generatedPassword = buildString(GENERATED_PASSWORD_LENGTH) {
            repeat(GENERATED_PASSWORD_LENGTH) {
                append(PASSWORD_ALPHABET[PASSWORD_RANDOM.nextInt(PASSWORD_ALPHABET.length)])
            }
        }
        runCatching {
            val encoded = Base64.getEncoder().encodeToString(generatedPassword.toByteArray(StandardCharsets.UTF_8))
            properties.setValue(GENERATED_PASSWORD_PROPERTY, encoded)
        }.onFailure { logger.warn("[OpenCode] Failed to persist the generated server password", it) }
        username = "opencode"
        return generatedPassword
    }

    private fun terminateLocalServerOnPort(port: Int) {
        val handler = localServerProcess
        localServerProcess = null
        if (handler != null) {
            val process = handler.process
            if (process.isAlive) {
                handler.destroyProcess()
                try {
                    if (process.isAlive && !process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(2, TimeUnit.SECONDS)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        if (PortFinder.isPortAvailable(port)) return

        findListenerProcessIds(port).forEach { terminateListenerProcess(it, force = false) }
        waitForPortRelease(port, timeoutMs = 2000)
        if (!PortFinder.isPortAvailable(port)) {
            findListenerProcessIds(port).forEach { terminateListenerProcess(it, force = true) }
            waitForPortRelease(port, timeoutMs = 1000)
        }
        if (!PortFinder.isPortAvailable(port)) {
            logger.warn("[OpenCode] Port $port remained occupied after attempting to stop its listener")
        }
    }

    private fun findListenerProcessIds(port: Int): List<String> {
        val output = if (isWindows()) {
            runShortCommand(listOf("netstat", "-ano", "-p", "TCP"), timeoutMs = 3000).orEmpty()
        } else {
            runShortCommand(listOf("lsof", "-ti", ":$port", "-sTCP:LISTEN"), timeoutMs = 3000).orEmpty()
        }
        val processIds = if (isWindows()) {
            output.mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                val localAddress = columns.getOrNull(1)
                if (columns.getOrNull(0) == "TCP" && columns.getOrNull(3) == "LISTENING" &&
                    localAddress?.substringAfterLast(':') == port.toString()
                ) {
                    columns.getOrNull(4)
                } else {
                    null
                }
            }
        } else {
            output
        }
        return processIds.filter { it.matches(Regex("\\d+")) }.distinct()
    }

    private fun terminateListenerProcess(processId: String, force: Boolean) {
        if (!processId.matches(Regex("\\d+"))) return
        val command = if (isWindows()) {
            listOf("taskkill", "/PID", processId, "/F")
        } else {
            listOf("kill", if (force) "-KILL" else "-TERM", processId)
        }
        runShortCommand(command, timeoutMs = 2000)
    }

    private fun waitForPortRelease(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!PortFinder.isPortAvailable(port) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun runShortCommand(command: List<String>, timeoutMs: Int): List<String>? {
        return try {
            val output = CapturingProcessHandler(GeneralCommandLine(command)).runProcess(timeoutMs)
            if (output.isTimeout) null else output.stdoutLines
        } catch (e: Exception) {
            logger.debug("[OpenCode] Failed to run process command: ${command.firstOrNull()}", e)
            null
        }
    }

    private fun startLocalServer(binary: String, h: String, p: Int, pwd: String?): Boolean {
        return try {
            localServerProcess?.process?.takeIf { it.isAlive }?.let { return true }
            val commandLine = GeneralCommandLine(listOf(binary, "serve", "--hostname", h, "--port", p.toString()))
            project.basePath?.let(commandLine::withWorkDirectory)
            commandLine.environment["OPENCODE_SERVER_PASSWORD"] = resolveServerPassword(pwd) ?: return false

            val handler = OSProcessHandler(commandLine)
            handler.addProcessListener(object : ProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    if (localServerProcess === handler) {
                        logger.warn("[OpenCode] Local server exited with code ${event.exitCode}")
                    }
                }
            })
            localServerProcess = handler
            handler.startNotify()
            true
        } catch (e: Exception) {
            logger.error("[OpenCode] Failed to start local OpenCode server", e)
            false
        }
    }

    private fun createTerminalUIInternal(h: String, p: Int, pwd: String?, cont: Boolean = true, customBasePath: String? = null) {
        val endpointHost = if (h == "0.0.0.0") "127.0.0.1" else h
        val target = "$endpointHost:$p"
        val existing = terminalVirtualFile
        if (terminalEndpoint == target && existing != null && OpenCodeTerminalFileEditorProvider.hasWidget(existing)) {
            // A TUI client for this server is already running: focus its tab instead
            // of spawning yet another opencode client process.
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(existing, true)
            }
            return
        }
        if (terminalEndpoint != null && existing != null) {
            // Stale tab from a previous endpoint: release it before opening the new one.
            FileEditorManager.getInstance(project).closeFile(existing)
            OpenCodeTerminalFileEditorProvider.disposeWidget(existing, null)
            terminalVirtualFile = null
        }
        val t = "$OPEN_CODE_TAB_PREFIX($p)"
        val wd = customBasePath ?: project.basePath
        val w = TerminalView.getInstance(project).createLocalShellWidget(wd, t)
        OpenCodeTerminalLinkFilter.install(project, w)
        val f = OpenCodeTerminalVirtualFile(t)
        terminalVirtualFile = f; OpenCodeTerminalFileEditorProvider.registerWidget(f, w)
        terminalEndpoint = target
        // When the target is the v2 shared background service, let the CLI discover
        // and authenticate on its own instead of inlining credentials in the command.
        val usesServiceDiscoveryAuth = runCatching {
            ServiceDiscovery.findSharedServer()?.let { it.port == p && it.hostname == endpointHost } == true
        }.getOrDefault(false)
        ApplicationManager.getApplication().invokeLater {
            terminalEditor = FileEditorManager.getInstance(project).openFile(f, true).firstOrNull { it is OpenCodeTerminalFileEditor } as? OpenCodeTerminalFileEditor
            terminalEditor?.let { 
                w.executeCommand(buildOpenCodeCommand(getOpenCodeBinary(), h, p, pwd, cont, usesServiceDiscoveryAuth)); pinTerminalTab(f)
            }
        }
    }

    internal fun buildOpenCodeCommand(command: String, h: String, p: Int, pwd: String?, cont: Boolean, useServiceDiscoveryAuth: Boolean = false): String {
        val cmdSafe = if (isWindows()) windowsQuote(command) else shellQuote(command)
        if (useServiceDiscoveryAuth) {
            // Attached to the v2 shared background service: the CLI discovers the
            // service and its credentials from its own state file, so neither the
            // URL nor a password needs to appear in the terminal.
            return if (cont) "$cmdSafe --continue" else cmdSafe
        }
        val connectHost = if (h == "0.0.0.0") "127.0.0.1" else h
        val serverUrl = "http://$connectHost:$p"
        val serverUrlSafe = if (isWindows()) windowsQuote(serverUrl) else shellQuote(serverUrl)
        val base = "$cmdSafe --server $serverUrlSafe${if (cont) " --continue" else ""}"
        if (pwd.isNullOrBlank()) return base
        return if (isWindows()) {
            val windowsPassword = pwd.replace("\"", "\\\"")
            "cmd /c \"set \"OPENCODE_PASSWORD=$windowsPassword\" && " +
                "set \"OPENCODE_SERVER_PASSWORD=$windowsPassword\" && $base\""
        } else {
            val shellPassword = pwd.replace("'", "'\\''")
            "OPENCODE_PASSWORD='$shellPassword' OPENCODE_SERVER_PASSWORD='$shellPassword' $base"
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun windowsQuote(value: String): String = "\"${value.replace("\"", "\\\"")}\""

    @Volatile private var _cachedBinary: String? = null

    private fun getOpenCodeBinary(): String {
        _cachedBinary?.let { return it }

        // Fast check common paths (safe for EDT - no process execution)
        val home = System.getProperty("user.home")
        val candidates = if (isWindows()) {
            listOf(
                java.io.File(home, ".opencode/bin/opencode.exe"),
                java.io.File("C:\\Program Files\\opencode\\opencode.exe"),
                java.io.File(System.getenv("LOCALAPPDATA") ?: "", "opencode\\opencode.exe"),
            )
        } else {
            listOf(
                java.io.File(home, ".opencode/bin/opencode"),
                java.io.File("/opt/homebrew/bin/opencode"),
                java.io.File("/usr/local/bin/opencode"),
                java.io.File("/home/linuxbrew/.linuxbrew/bin/opencode"),
                java.io.File(home, ".linuxbrew/bin/opencode"),
                java.io.File("/usr/bin/opencode"),
                java.io.File("/snap/bin/opencode"),
            )
        }
        
        for (candidate in candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                val path = candidate.absolutePath
                logger.info("[OpenCode] Resolved CLI to: $path")
                _cachedBinary = path
                return path
            }
        }

        return "opencode"
    }

    /** Detect OpenCode binary path (Background thread safe) */
    private fun detectOpenCodeBinary(): String? {
        val home = System.getProperty("user.home")
        val exe = if (isWindows()) ".exe" else ""
        
        // Check common installation paths (ordered by priority)
        val candidates = if (isWindows()) {
            listOf(
                java.io.File(home, ".opencode/bin/opencode.exe"),
                java.io.File("C:\\Program Files\\opencode\\opencode.exe"),
                java.io.File("C:\\Program Files (x86)\\opencode\\opencode.exe"),
                java.io.File(System.getenv("LOCALAPPDATA") ?: "", "opencode\\opencode.exe"),
            )
        } else {
            listOf(
                java.io.File(home, ".opencode/bin/opencode"),           // npm global install
                java.io.File("/opt/homebrew/bin/opencode"),             // macOS ARM Homebrew
                java.io.File("/usr/local/bin/opencode"),                // macOS Intel Homebrew / Linux standard
                java.io.File("/home/linuxbrew/.linuxbrew/bin/opencode"),// Linux Homebrew
                java.io.File(home, ".linuxbrew/bin/opencode"),          // Linux Homebrew (user install)
                java.io.File("/usr/bin/opencode"),                      // System package manager
                java.io.File("/snap/bin/opencode"),                     // Snap install
            )
        }
        
        for (candidate in candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                logger.info("[OpenCode] Detected CLI at: ${candidate.absolutePath}")
                _cachedBinary = candidate.absolutePath
                return candidate.absolutePath
            }
        }

        // Fallback: Check PATH (may fail in sandboxed environments like Snap)
        if (checkOpenCodeCliAvailable()) {
            logger.info("[OpenCode] CLI detected in PATH")
            _cachedBinary = "opencode"
            return "opencode"
        }

        return null
    }

    /** Check CLI availability (safe for background thread) */
    private fun checkOpenCodeCliAvailable(): Boolean {
        val cmds = if (isWindows()) {
            listOf(
                listOf("cmd", "/c", "where", "opencode"),
                listOf("powershell", "-Command", "Get-Command opencode")
            )
        } else {
            listOf(
                listOf("which", "opencode"),
                listOf("sh", "-lc", "command -v opencode")
            )
        }
        return cmds.any { try { CapturingProcessHandler(GeneralCommandLine(it)).runProcess(5000).exitCode == 0 } catch (_: Exception) { false } }
    }
    
    /** Show CLI not found error (must call on any thread, will dispatch to EDT) */
    private fun showCliNotFoundError() {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, "OpenCode CLI not found.", "Error")
        }
    }

    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("windows")
    private fun pinTerminalTab(f: VirtualFile) { try { FileEditorManagerEx.getInstanceEx(project).currentWindow?.setFilePinned(f, true) } catch (_: Exception) {} }
    private fun restartServer(m: ConnectionMode) {
        val h = hostname
        val p = port ?: return
        val pwd = password
        val liveServerHandler = localServerProcess?.takeIf { it.process.isAlive }
        disconnectAndReset(terminateServer = false)
        localServerProcess = liveServerHandler
        hostname = h
        port = p
        password = pwd
        lastMode = m
        if (m == ConnectionMode.WEB) createWebTerminal(h, p, pwd)
        else if (m == ConnectionMode.REMOTE) {
            connectToExistingServer(h, p, ProcessAuthDetector.ServerAuth("opencode", pwd), false, false)
        } else createLocalTerminal(h, p, pwd)
    }
    private fun focusTerminalUI() { terminalVirtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) } ?: webVirtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) } }
    private fun restoreUiForMode() { 
        when (lastMode) { 
            ConnectionMode.TERMINAL -> ensureTerminalUi()
            ConnectionMode.WEB -> ensureWebUi()
            ConnectionMode.REMOTE -> restoreRemoteConnection()
            else -> showConnectionDialog() 
        } 
    }
    private fun ensureTerminalUi() { 
        val f = terminalVirtualFile
        if (f != null && OpenCodeTerminalFileEditorProvider.hasWidget(f)) {
            focusTerminalUI()
        } else {
            // Terminal UI doesn't exist, need to create new terminal and start opencode
            try {
                createTerminalUIInternal(hostname, port ?: return, password, false)
            } catch (e: Exception) {
                logger.warn("[OpenCode] Failed to create terminal UI", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Failed to create terminal: ${e.message}", "OpenCode")
                }
            }
        }
    }
    private fun ensureWebUi() { if (webVirtualFile != null) focusTerminalUI() else createWebUI(hostname, port ?: return) }
    private fun restoreRemoteConnection() {
        // For remote connections, verify connection is alive and show status
        val p = port ?: return
        val h = hostname
        AppExecutorUtil.getAppExecutorService().submit {
            val running = PortFinder.isOpenCodeRunningOnPort(p, h, username, password)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (running) {
                    if (!isConnected.get()) {
                        // Reconnect SSE if not connected
                        initializeApiClient(h, p)
                        startConnectionManager()
                    }
                    Messages.showInfoMessage(project, "Connected to $h:$p", "OpenCode")
                } else {
                    Messages.showWarningDialog(project, "Remote server $h:$p is not reachable.", "OpenCode")
                }
            }
        }
    }
    private fun showHeadlessStatusDialog() {
        ApplicationManager.getApplication().invokeLater {
            val result = Messages.showYesNoDialog(
                project,
                "Connected to $hostname:$port (Headless). Disconnect?",
                "OpenCode",
                "Disconnect",
                "Keep",
                Messages.getInformationIcon()
            )
            if (result == Messages.YES) disconnectAndReset(terminateServer = false)
        }
    }
    
    private fun schedulePasteAttempt(t: String, l: Int, d: Long, onPasteFailure: (() -> Unit)?) {
        if (l <= 0 || project.isDisposed) {
            if (l <= 0 && !project.isDisposed) {
                if (onPasteFailure != null) {
                    onPasteFailure()
                } else {
                    logger.warn("[Paste] Context could not be inserted because no terminal connector became available")
                    sendNotification(
                        "OpenCode Terminal Unavailable",
                        "Context was not inserted. Open an OpenCode terminal and try again.",
                        NotificationType.WARNING
                    )
                }
            }
            return
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ 
            ApplicationManager.getApplication().invokeLater { 
                if (!project.isDisposed) {
                    if (!pasteToTerminal(t)) {
                        schedulePasteAttempt(t, l - 1, d, onPasteFailure)
                    }
                }
            } 
        }, d, TimeUnit.MILLISECONDS) 
    }

}
