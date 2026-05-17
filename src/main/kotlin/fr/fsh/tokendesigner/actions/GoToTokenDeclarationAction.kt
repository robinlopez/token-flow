package fr.fsh.tokendesigner.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.scanner.TokenLocator

/**
 * Navigates to the source file & offset where the token under the caret was
 * declared. Mirrors what the dashboard's locate icon does, but as a keyboard
 * action that works directly from any code file (`.scss`, `.css`, `.ts`, …).
 */
class GoToTokenDeclarationAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val caret = editor.caretModel.offset
        val ext = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(editor.document)?.extension
        val hit = TokenLocator.find(editor.document, caret, ext) ?: run {
            JBPopupFactory.getInstance()
                .createMessage("Place the caret on a design token reference.")
                .showCenteredInCurrentWindow(project)
            return
        }

        val file = com.intellij.openapi.fileEditor.FileDocumentManager
            .getInstance().getFile(editor.document)

        object : Task.Backgroundable(project, "Locating token declaration", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val tokens = runReadAction { TokenIndex.getInstance(project).get(file) }
                val tokenNames = tokens.map { it.name }.toSet()
                val resolved = fr.fsh.tokendesigner.scanner.TokenNameParser
                    .resolveReference(hit.name, tokenNames)
                val match = resolved?.let { r -> tokens.firstOrNull { it.name == r.tokenName } }
                ApplicationManager.getApplication().invokeLater {
                    if (match == null) {
                        JBPopupFactory.getInstance()
                            .createMessage("No declaration found for ${hit.name}.")
                            .showCenteredInCurrentWindow(project)
                        return@invokeLater
                    }
                    val vf = LocalFileSystem.getInstance().findFileByPath(match.filePath) ?: return@invokeLater
                    OpenFileDescriptor(project, vf, match.offset).navigate(true)
                }
            }
        }.queue()
    }
}
