package fr.fsh.tokendesigner.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import fr.fsh.tokendesigner.settings.TokenSelectorSettings

class DesignTokenToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val library = factory.createContent(DesignTokenDashboardPanel(project), "Library", false)
        val hardcoded = factory.createContent(HardcodedValuesPanel(project), "Hardcoded values", false)
        val analyze = factory.createContent(AnalyzePanel(project), "Analyse", false)
        toolWindow.contentManager.addContent(library)
        toolWindow.contentManager.addContent(hardcoded)
        toolWindow.contentManager.addContent(analyze)

        applyIcon(project, toolWindow)
        // Refresh on settings change so users see the new icon immediately.
        TokenSelectorSettings.getInstance(project).addIconChangeListener {
            ApplicationManager.getApplication().invokeLater { applyIcon(project, toolWindow) }
        }
    }

    private fun applyIcon(project: Project, toolWindow: ToolWindow) {
        val variant = IconVariant.fromName(TokenSelectorSettings.getInstance(project).iconVariantName)
        toolWindow.setIcon(variant.load())
    }
}
