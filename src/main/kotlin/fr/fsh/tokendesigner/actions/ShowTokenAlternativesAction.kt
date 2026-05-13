package fr.fsh.tokendesigner.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.popup.JBPopupFactory
import fr.fsh.tokendesigner.inspection.LiteralFinder
import fr.fsh.tokendesigner.scanner.TokenLocator

class ShowTokenAlternativesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val caret = editor.caretModel.offset

        // 1) Token reference under the caret? (e.g. $var, --var)
        val ext = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(editor.document)?.extension
        val tokenHit = TokenLocator.find(editor.document, caret, ext)
        if (tokenHit != null) {
            TokenAlternativesShower.show(project, editor, tokenHit)
            return
        }

        // 2) Hardcoded literal under the caret? (e.g. 12px, #fff)
        val literalHit = findLiteralAtCaret(editor.document.charsSequence, caret)
        if (literalHit != null) {
            TokenAlternativesShower.showForLiteral(project, editor, literalHit)
            return
        }

        JBPopupFactory.getInstance()
            .createMessage("Place the caret on a token (\$var, --var) or a hardcoded value (12px, #fff).")
            .showCenteredInCurrentWindow(project)
    }

    /**
     * Walks LiteralFinder over the file text and returns the literal whose
     * range contains the caret offset, if any.
     */
    /**
     * Caret can sit on the inner literal (`18px`) OR anywhere inside the
     * transparent wrapper (`utils.rem-calc(18px)`). Both should resolve to the
     * same Hit so the replacement covers the full wrapper.
     */
    private fun findLiteralAtCaret(text: CharSequence, offset: Int): LiteralFinder.Hit? =
        LiteralFinder.findIn(text).firstOrNull {
            offset >= it.replaceStart && offset < it.replaceEndExclusive
        }
}
