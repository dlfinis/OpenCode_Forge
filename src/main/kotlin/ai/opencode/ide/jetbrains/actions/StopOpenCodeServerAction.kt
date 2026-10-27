package ai.opencode.ide.jetbrains.actions

import ai.opencode.ide.jetbrains.OpenCodeService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class StopOpenCodeServerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<OpenCodeService>().stopLocalServer()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
