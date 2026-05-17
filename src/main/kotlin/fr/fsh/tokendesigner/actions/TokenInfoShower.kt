package fr.fsh.tokendesigner.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.scanner.TokenLocator
import fr.fsh.tokendesigner.ui.ColorParser
import fr.fsh.tokendesigner.ui.RoundSwatch
import fr.fsh.tokendesigner.ui.VariantTableHtml
import java.awt.BorderLayout
import java.awt.Point
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Hover-trigger popup that surfaces the resolved value(s) of the token under
 * the caret as a compact card:
 *  - Header line: token name (+ swatch for COLOR tokens).
 *  - Body: a single-row HTML table whose columns are the resolution contexts
 *    (default, dark mode, ≥1024, …). For colors each cell shows a coloured
 *    pastille + hex; for everything else it shows the raw value.
 *
 * This replaces the former hover behaviour, which opened the alternatives
 * dropdown — an unwanted action when the user just wanted to *read* what the
 * token resolves to.
 */
object TokenInfoShower {

    private val activePopup = AtomicReference<JBPopup?>(null)

    fun show(project: Project, editor: Editor, hit: TokenLocator.Hit, anchorScreenLocation: Point? = null) {
        activePopup.getAndSet(null)?.takeIf { it.isVisible }?.cancel()

        object : Task.Backgroundable(project, "Looking up token info", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val file = com.intellij.openapi.fileEditor.FileDocumentManager
                    .getInstance().getFile(editor.document)
                val tokens = runReadAction { TokenIndex.getInstance(project).get(file) }
                val tokenNames = tokens.map { it.name }.toSet()
                val resolved = fr.fsh.tokendesigner.scanner.TokenNameParser
                    .resolveReference(hit.name, tokenNames) ?: return
                val token = tokens.firstOrNull { it.name == resolved.tokenName } ?: return
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    showPopup(project, editor, token, anchorScreenLocation)
                }
            }
        }.queue()
    }

    private fun showPopup(
        project: Project,
        editor: Editor,
        token: DesignToken,
        anchorScreenLocation: Point?,
    ) {
        val content = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8, 10)
        }
        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            if (token.category == TokenCategory.COLOR) {
                val sw = RoundSwatch(diameterPx = 16)
                sw.color = ColorParser.parse(token.resolvedValue)
                add(sw, BorderLayout.WEST)
            }
            add(JLabel("<html><b>${escape(token.name)}</b></html>"), BorderLayout.CENTER)
        }
        content.add(header, BorderLayout.NORTH)
        content.add(JLabel(VariantTableHtml.build(token)), BorderLayout.CENTER)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setRequestFocus(false)
            .setMovable(true)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .setDimensionServiceKey(project, "DesignTokenSelector.HoverInfoPopup", false)
            .createPopup()

        activePopup.set(popup)
        popup.show(anchorPoint(editor, anchorScreenLocation))
    }

    private fun anchorPoint(editor: Editor, anchorScreenLocation: Point?): RelativePoint {
        if (anchorScreenLocation != null) {
            val component = editor.contentComponent
            val local = Point(anchorScreenLocation).also {
                javax.swing.SwingUtilities.convertPointFromScreen(it, component)
            }
            return RelativePoint(component, local)
        }
        val visual = editor.caretModel.primaryCaret.visualPosition
        val xy = editor.visualPositionToXY(visual)
        xy.translate(0, editor.lineHeight)
        return RelativePoint(editor.contentComponent, xy)
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
