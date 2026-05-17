package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import javax.swing.JComponent
import javax.swing.JLabel

object ScopeUIUtils {
    fun createScopeHelpButton(project: Project): JComponent {
        return JLabel(AllIcons.General.ContextHelp).apply {
            toolTipText = "<html><b>Scopes</b> determine which tokens are available depending on the file you edit.<br>Click to configure scopes in Settings.</html>"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Token Flow")
                }
            })
        }
    }
}
