package fr.fsh.tokendesigner.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.model.TokenReference
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.scanner.TokenLocator
import fr.fsh.tokendesigner.scanner.TokenNameParser
import fr.fsh.tokendesigner.ui.ColorConversions
import fr.fsh.tokendesigner.ui.ColorParser
import fr.fsh.tokendesigner.util.readAction
import java.awt.Color
import java.awt.Point
import java.awt.datatransfer.StringSelection

/**
 * Modifier+click dropdown (issue #27): resolves the token under the cursor and
 * offers one-click copy of its values. The resolved primitive value is the
 * preselected default; below it sit the token reference and — for colours —
 * the alternate notations (HEX / RGB / HSL / OKLCH) of the resolved colour.
 *
 * Resolution reuses the same pipeline as [TokenInfoShower]: alias chains are
 * followed all the way to the primitive via [TokenNameParser] + [TokenIndex].
 */
object CopyTokenValueShower {

    /** One copyable row: [primary] is the string copied, [label] the grey caption. */
    private data class Option(val primary: String, val label: String, val swatch: Color?)

    fun show(project: Project, editor: Editor, hit: TokenLocator.Hit, anchorScreenLocation: Point? = null) {
        object : Task.Backgroundable(project, "Resolving token value", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val file = com.intellij.openapi.fileEditor.FileDocumentManager
                    .getInstance().getFile(editor.document)
                val tokens = readAction { TokenIndex.getInstance(project).get(file) }
                val tokenNames = tokens.map { it.name }.toSet()
                val resolved = TokenNameParser.resolveReference(hit.name, tokenNames) ?: return
                val token = tokens.firstOrNull { it.name == resolved.tokenName } ?: return
                val options = buildOptions(token)
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    showPopup(project, editor, options, anchorScreenLocation)
                }
            }
        }.queue()
    }

    private fun buildOptions(token: DesignToken): List<Option> {
        val out = mutableListOf<Option>()
        val color = if (token.category == TokenCategory.COLOR) ColorParser.parse(token.resolvedValue) else null

        // 1. The resolved primitive value — the default action.
        out += Option(token.resolvedValue, "Resolved value", color)

        // 2. Alternate colour notations (skip the one the value already uses).
        if (color != null) {
            val source = ColorConversions.detect(token.resolvedValue)
            for (fmt in ColorConversions.Format.entries) {
                if (fmt == source) continue
                val text = when (fmt) {
                    ColorConversions.Format.HEX -> ColorConversions.toHex(color)
                    ColorConversions.Format.RGB -> ColorConversions.toRgb(color)
                    ColorConversions.Format.HSL -> ColorConversions.toHsl(color)
                    ColorConversions.Format.OKLCH -> ColorConversions.toOklch(color)
                }
                out += Option(text, fmt.name, color)
            }
        }

        // 3. The token reference as written in code (var(--x), $x, '{x}', …).
        val ref = TokenReference.expression(token)
        if (ref != token.resolvedValue) out += Option(ref, "Token name", null)

        return out
    }

    private fun showPopup(
        project: Project,
        editor: Editor,
        options: List<Option>,
        anchorScreenLocation: Point?,
    ) {
        if (options.isEmpty()) return

        val renderer = SimpleListCellRenderer.create<Option> { label, value, _ ->
            label.text = "<html>${escape(value.primary)} " +
                "<font color='#888888'>&nbsp;&nbsp;${escape(value.label)}</font></html>"
            label.icon = value.swatch?.let { ColorIcon(JBUI.scale(14), it) }
            label.iconTextGap = JBUI.scale(8)
            label.border = JBUI.Borders.empty(2, 4)
        }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(options)
            .setTitle("Copy token value")
            .setRenderer(renderer)
            .setRequestFocus(true)
            .setItemChosenCallback { copy(project, editor, it, anchorScreenLocation) }
            .createPopup()

        popup.show(anchorPoint(editor, anchorScreenLocation))
    }

    private fun copy(project: Project, editor: Editor, option: Option, anchorScreenLocation: Point?) {
        CopyPasteManager.getInstance().setContents(StringSelection(option.primary))
        val msg = "📋 Copied \"${escape(option.primary)}\""
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(msg, MessageType.INFO, null)
            .setFadeoutTime(1800)
            .createBalloon()
            .show(anchorPoint(editor, anchorScreenLocation), com.intellij.openapi.ui.popup.Balloon.Position.above)
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
