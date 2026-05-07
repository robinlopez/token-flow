package fr.fsh.tokendesigner.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.scanner.TokenIndex

class ShowAllTokensAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        object : Task.Backgroundable(project, "Scanning design tokens", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val tokens = runReadAction { TokenIndex.getInstance(project).get(null) }
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    showResult(project, tokens)
                }
            }
        }.queue()
    }

    private fun showResult(project: com.intellij.openapi.project.Project, tokens: List<DesignToken>) {
        if (tokens.isEmpty()) {
            Messages.showInfoMessage(project, "No design tokens detected.", "Token Flow")
            return
        }
        val byCategory = tokens.groupBy { it.category }.toSortedMap(compareBy { it.name })
        val summary = buildString {
            appendLine("Found ${tokens.size} tokens across ${tokens.map { it.filePath }.toSet().size} file(s):\n")
            for ((cat, list) in byCategory) {
                appendLine("── ${cat.name} (${list.size}) ──")
                list.take(20).forEach { t ->
                    appendLine("  ${t.name} = ${t.displayValue}")
                }
                if (list.size > 20) appendLine("  … and ${list.size - 20} more")
                appendLine()
            }
        }
        Messages.showInfoMessage(project, summary, "Design Tokens")
    }
}
