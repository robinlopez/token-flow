package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import javax.swing.JComponent

object ScopeUIUtils {
    fun createScopeHelpButton(project: Project): JComponent {
        val action = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "Token Flow")
            }
        }
        val helpBtn = com.intellij.ui.components.labels.ActionLink("", action).apply {
            icon = AllIcons.General.ContextHelp
        }
        helpBtn.toolTipText = "<html><b>Scopes</b> determine which tokens are available depending on the file you edit.<br>Click to configure scopes in Settings.</html>"
        return helpBtn
    }
}
