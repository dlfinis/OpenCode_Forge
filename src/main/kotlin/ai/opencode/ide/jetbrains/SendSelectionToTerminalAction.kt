package ai.opencode.ide.jetbrains

import ai.opencode.ide.jetbrains.util.PathUtil

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

import java.awt.datatransfer.StringSelection

/**
 * Sends file path references to the OpenCode terminal.
 * 
 * - Editor selection: @path#Lstart-end (NO content, just the reference)
 * - Project View selection: @path for each file/directory
 *
 * If the OpenCode terminal is not open yet, the plugin will create/focus it
 * and then paste the reference (Claude-style UX).
 */
class SendSelectionToTerminalAction : AnAction() {

    internal var clipboardWriterForTests: ((String) -> Boolean)? = null
    internal var clipboardFallbackNotifierForTests: ((Project, Boolean) -> Unit)? = null
    internal var terminalDeliveryForTests: ((String, () -> Unit) -> Unit)? = null

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val openCodeService = project.service<OpenCodeService>()

        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val projectViewHasSelection = e.place == ActionPlaces.PROJECT_VIEW_POPUP && !virtualFiles.isNullOrEmpty()
        val editor = if (projectViewHasSelection) {
            null
        } else {
            e.getData(CommonDataKeys.EDITOR) ?: selectedEditorForActionPlace(e, project)
        }

        val textToSend = StringBuilder()

        if (editor != null) {
            val file = resolveEditorFile(project, e, editor)
            val relativePath = file?.let { getRelativePath(project, it) }

            if (relativePath != null) {
                val referencePath = formatPathReference(relativePath)
                if (editor.selectionModel.hasSelection()) {
                    // Editor selection: only @path#Lstart-end (NO content)
                    val selectionModel = editor.selectionModel
                    val document = editor.document
                    val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
                    val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1

                    textToSend.append("@$referencePath#L$startLine-$endLine")
                } else {
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                        ?: PsiManager.getInstance(project).findFile(file)
                    val classRange = psiFile?.let { findEnclosingClassRange(it, editor.caretModel.offset) }
                    if (classRange != null) {
                        val startLine = editor.document.getLineNumber(classRange.startOffset) + 1
                        val endOffset = (classRange.endOffset - 1).coerceAtLeast(classRange.startOffset)
                        val endLine = editor.document.getLineNumber(endOffset) + 1
                        textToSend.append("@$referencePath#L$startLine-$endLine")
                    } else {
                        // Non-class files and unavailable PSI safely fall back to the whole file.
                        textToSend.append("@$referencePath")
                    }
                }
            }
        } else if (virtualFiles != null && virtualFiles.isNotEmpty()) {
            // Project View selection: @path per file/directory
            val focusedFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val normalizedSelection = normalizeProjectSelection(virtualFiles, focusedFile)
            for (file in normalizedSelection) {
                val relativePath = getRelativePath(project, file)
                val referencePath = formatPathReference(relativePath)
                textToSend.append("@$referencePath ")
            }
        }

        if (textToSend.isNotEmpty()) {
            val reference = textToSend.toString().trimEnd()
            val onPasteFailure = { copyReferenceToClipboard(project, reference) }
            val testDelivery = terminalDeliveryForTests
            if (testDelivery != null) {
                testDelivery("$reference ", onPasteFailure)
            } else {
                openCodeService.focusOrCreateTerminalAndPaste("$reference ", onPasteFailure)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        val hasEditorContext = editor != null || e.place == ActionPlaces.EDITOR_POPUP ||
            ActionPlaces.isMainMenuOrActionSearch(e.place)
        val hasFileSelection = virtualFiles != null && virtualFiles.isNotEmpty()

        // Always available even if terminal isn't created yet
        e.presentation.isEnabledAndVisible = hasEditorContext || hasFileSelection
    }

    private fun selectedEditorForActionPlace(event: AnActionEvent, project: Project) =
        if (event.place == ActionPlaces.EDITOR_POPUP || ActionPlaces.isMainMenuOrActionSearch(event.place)) {
            FileEditorManager.getInstance(project).selectedTextEditor
        } else {
            null
        }

    private fun getRelativePath(project: Project, file: VirtualFile): String {
        return PathUtil.relativizeToProject(project, file.path)
    }

    private fun resolveEditorFile(
        project: Project,
        event: AnActionEvent,
        editor: com.intellij.openapi.editor.Editor
    ): VirtualFile? {
        return event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileDocumentManager.getInstance().getFile(editor.document)
            ?: PsiDocumentManager.getInstance(project).getPsiFile(editor.document)?.virtualFile
    }

    private fun findEnclosingClassRange(psiFile: PsiElement, caretOffset: Int): TextRange? {
        if (psiFile.textLength == 0) return null
        val elementOffset = caretOffset.coerceAtMost(psiFile.textLength - 1).coerceAtLeast(0)
        var element: PsiElement? = psiFile.containingFile.findElementAt(elementOffset)
        while (element != null) {
            if (isJavaClassDeclaration(element) || isKotlinClassDeclaration(element)) {
                return element.textRange
            }
            element = element.parent
        }
        return null
    }

    private fun isJavaClassDeclaration(element: PsiElement): Boolean {
        return implementsInterfaceNamed(element.javaClass, JAVA_PSI_CLASS_INTERFACE)
    }

    private fun implementsInterfaceNamed(type: Class<*>, interfaceName: String): Boolean {
        if (type.name == interfaceName) return true
        if (type.interfaces.any { implementsInterfaceNamed(it, interfaceName) }) return true
        return type.superclass?.let { implementsInterfaceNamed(it, interfaceName) } ?: false
    }

    private fun isKotlinClassDeclaration(element: PsiElement): Boolean {
        // Avoid a required Kotlin plugin dependency while still recognizing its PSI declaration nodes.
        return element.javaClass.name == "org.jetbrains.kotlin.psi.KtClass" ||
            element.javaClass.name == "org.jetbrains.kotlin.psi.KtObjectDeclaration"
    }

    private fun copyReferenceToClipboard(project: Project, reference: String) {
        val copied = try {
            val testWriter = clipboardWriterForTests
            if (testWriter != null) {
                testWriter(reference)
            } else {
                CopyPasteManager.getInstance().setContents(StringSelection(reference))
                true
            }
        } catch (exception: Exception) {
            LOG.warn("Failed to copy OpenCode reference to the clipboard", exception)
            false
        }

        val testNotifier = clipboardFallbackNotifierForTests
        if (testNotifier != null) {
            testNotifier(project, copied)
            return
        }

        val notification = if (copied) {
            "The OpenCode terminal was unavailable. The reference was copied to the clipboard."
        } else {
            "The OpenCode terminal was unavailable, and the reference could not be copied to the clipboard."
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                "OpenCode Reference",
                notification,
                if (copied) NotificationType.INFORMATION else NotificationType.WARNING
            )
            .notify(project)
    }

    private fun formatPathReference(path: String): String {
        return if (path.contains(" ")) {
            "\"$path\""
        } else {
            path
        }
    }

    private fun normalizeProjectSelection(
        selectedFiles: Array<VirtualFile>,
        focusedFile: VirtualFile?
    ): List<VirtualFile> {
        val all = LinkedHashMap<String, VirtualFile>()
        selectedFiles.forEach { all[it.path] = it }

        // Some IDE versions may provide descendants in VIRTUAL_FILE_ARRAY when a directory is selected.
        // If focused item is a directory but missing from array, add it to preserve directory semantics.
        if (focusedFile != null && focusedFile.isDirectory) {
            all.putIfAbsent(focusedFile.path, focusedFile)
        }

        val sorted = all.values.sortedBy { it.path.length }
        val kept = mutableListOf<VirtualFile>()
        for (candidate in sorted) {
            val candidatePath = PathUtil.toSystemIndependentPath(candidate.path)
            val hasParentSelected = kept.any { parent ->
                val parentPath = PathUtil.toSystemIndependentPath(parent.path)
                candidatePath == parentPath || candidatePath.startsWith("$parentPath/")
            }
            if (!hasParentSelected) {
                kept.add(candidate)
            }
        }
        return kept
    }

    private companion object {
        private const val NOTIFICATION_GROUP_ID = "OpenCode"
        private const val JAVA_PSI_CLASS_INTERFACE = "com.intellij.psi.PsiClass"
        private val LOG = Logger.getInstance(SendSelectionToTerminalAction::class.java)
    }
}
