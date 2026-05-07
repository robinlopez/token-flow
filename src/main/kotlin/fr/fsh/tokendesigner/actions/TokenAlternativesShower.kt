package fr.fsh.tokendesigner.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Point
import fr.fsh.tokendesigner.inspection.LiteralFinder
import fr.fsh.tokendesigner.inspection.PropertyContext
import fr.fsh.tokendesigner.inspection.SuggestionEngine
import fr.fsh.tokendesigner.inspection.TokenValueIndex
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.scanner.CandidateSorter
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.scanner.TokenLocator
import fr.fsh.tokendesigner.ui.PopupRowRenderer
import fr.fsh.tokendesigner.ui.RowGrouping
import fr.fsh.tokendesigner.ui.TokenPopupRow
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared logic for showing the alternatives popup. Used by both the manual
 * action ([ShowTokenAlternativesAction]) and the optional hover trigger.
 */
object TokenAlternativesShower {

    private val activePopup = AtomicReference<JBPopup?>(null)

    fun show(project: Project, editor: Editor, hit: TokenLocator.Hit, anchorScreenLocation: Point? = null) {
        // Avoid stacking multiple popups when the user keeps hovering over tokens.
        activePopup.getAndSet(null)?.takeIf { it.isVisible }?.cancel()

        object : Task.Backgroundable(project, "Looking up token alternatives", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
                val tokens = ReadAction.compute<List<DesignToken>, RuntimeException> {
                    TokenIndex.getInstance(project).get(file)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    showPopup(project, editor, hit, tokens, anchorScreenLocation)
                }
            }
        }.queue()
    }

    private fun showPopup(
        project: Project,
        editor: Editor,
        hit: TokenLocator.Hit,
        all: List<DesignToken>,
        anchorScreenLocation: Point?,
    ) {
        // Pivot lookup must tolerate JS preset paths whose mode segment was
        // stripped at indexing time — `'{token.modeLight.x.y}'` and
        // `'{token.modeDark.x.y}'` both resolve to canonical `token.x.y`.
        val canonicalName = fr.fsh.tokendesigner.scanner.TokenNameParser
            .stripModeSegment(hit.name) ?: hit.name
        val pivot = all.firstOrNull { it.name == hit.name || it.name == canonicalName }
        val category = pivot?.category
        val candidates = if (category != null) {
            all.filter { it.category == category }
        } else {
            val prefix = hit.name.takeWhile { it != '-' || it == hit.name.first() }
            all.filter { it.name.startsWith(prefix) }
        }
        if (candidates.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage("No alternatives found for ${hit.name}.")
                .showCenteredInCurrentWindow(project)
            return
        }
        val sorted = CandidateSorter.sort(category ?: candidates.first().category, candidates, pivot)
        val rows = RowGrouping.buildRows(sorted, pivot)
        val pivotRow = pivot?.let { p -> rows.firstOrNull { it is TokenPopupRow && it.token == p } }

        val title = if (pivot != null) {
            "${pivot.category.name.lowercase().replaceFirstChar { it.uppercase() }} alternatives — ${hit.name}"
        } else {
            "Tokens matching ${hit.name}"
        }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setRenderer(PopupRowRenderer())
            .setTitle(title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setNamerForFiltering { it.filterKey }
            .setItemChosenCallback { selected ->
                if (selected is TokenPopupRow) replaceToken(project, editor, hit, selected.token)
            }
            .setSelectedValue(pivotRow, true)
            .setMinSize(JBUI.size(580, 380))
            .setDimensionServiceKey("DesignTokenSelector.AlternativesPopup")
            .createPopup()

        activePopup.set(popup)
        if (anchorScreenLocation != null) {
            val component = editor.contentComponent
            val local = Point(anchorScreenLocation).also { javax.swing.SwingUtilities.convertPointFromScreen(it, component) }
            popup.show(RelativePoint(component, local))
        } else {
            popup.showInBestPositionFor(editor)
        }
    }

    private fun replaceToken(project: Project, editor: Editor, hit: TokenLocator.Hit, replacement: DesignToken) {
        if (replacement.name == hit.name) return
        // The Hit range matches the full reference syntax (incl. `'{…}'` for JS
        // paths), so wrap the replacement in the form expected by the kind.
        // For JS tokens whose canonical name had its mode segment stripped at
        // indexing time, re-inject the mode segment from the original hit so
        // `{token.modeLight.…}` stays under the same mode after substitution.
        val replaceText = when (replacement.kind) {
            TokenKind.JS_OBJECT_PATH -> {
                val raw = fr.fsh.tokendesigner.scanner.TokenNameParser.rawModeSegmentOf(hit.name)
                val idx = fr.fsh.tokendesigner.scanner.TokenNameParser.modeSegmentIndex(hit.name)
                val finalPath = if (raw != null && idx >= 0) {
                    fr.fsh.tokendesigner.scanner.TokenNameParser
                        .injectModeSegment(replacement.name, raw, idx)
                } else replacement.name
                "'{$finalPath}'"
            }
            else -> replacement.name
        }
        WriteCommandAction.runWriteCommandAction(project, "Replace Design Token", null, {
            editor.document.replaceString(hit.startOffset, hit.endOffset, replaceText)
        })
    }

    /**
     * Variant of [show] for hardcoded literal values (`12px`, `#fff`, …).
     * Computes [SuggestionEngine] suggestions, ranks by surrounding CSS property,
     * and offers a popup whose selection replaces the literal range with
     * `var(--name)` or `$name`.
     */
    fun showForLiteral(project: Project, editor: Editor, hit: LiteralFinder.Hit, anchorScreenLocation: Point? = null) {
        activePopup.getAndSet(null)?.takeIf { it.isVisible }?.cancel()

        object : Task.Backgroundable(project, "Looking up token suggestions", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
                val ext = file?.extension?.lowercase()
                val allTokens = ReadAction.compute<List<DesignToken>, RuntimeException> {
                    TokenIndex.getInstance(project).get(file)
                }
                val tokens = allTokens.filter { it.kind in compatibleKinds(ext) }
                val text = ReadAction.compute<String, RuntimeException> { editor.document.text }
                val expected = PropertyContext.detectAt(text, hit.startOffset)
                val valueIndex = TokenValueIndex(tokens)
                val suggestions = if (isJsExt(ext) && hit.insidePartialString) emptyList()
                else SuggestionEngine.findSuggestions(hit, valueIndex, tokens, expected)

                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    if (suggestions.isEmpty()) {
                        JBPopupFactory.getInstance()
                            .createMessage("No matching design token for ${hit.text}.")
                            .show(anchorPoint(editor, anchorScreenLocation))
                        return@invokeLater
                    }
                    showLiteralPopup(project, editor, hit, suggestions, expected, anchorScreenLocation)
                }
            }
        }.queue()
    }

    private fun showLiteralPopup(
        project: Project,
        editor: Editor,
        hit: LiteralFinder.Hit,
        suggestions: List<fr.fsh.tokendesigner.inspection.TokenSuggestion>,
        expected: fr.fsh.tokendesigner.model.TokenCategory?,
        anchorScreenLocation: Point?,
    ) {
        val rows = suggestions.map { TokenPopupRow(it.token) }
        val title = buildString {
            append("Tokens matching ")
            append(hit.text)
            expected?.let { append(" (").append(it.name.lowercase()).append(" context)") }
        }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setRenderer(PopupRowRenderer())
            .setTitle(title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setNamerForFiltering { it.filterKey }
            .setItemChosenCallback { selected ->
                if (selected is TokenPopupRow) replaceLiteralRange(project, editor, hit, selected.token)
            }
            .setMinSize(JBUI.size(580, 320))
            .setDimensionServiceKey("DesignTokenSelector.LiteralSuggestionsPopup")
            .createPopup()

        activePopup.set(popup)
        popup.show(anchorPoint(editor, anchorScreenLocation))
    }

    private fun replaceLiteralRange(project: Project, editor: Editor, hit: LiteralFinder.Hit, token: DesignToken) {
        val replacement = when (token.kind) {
            TokenKind.SCSS_VARIABLE -> token.name
            TokenKind.CSS_CUSTOM_PROPERTY -> "var(${token.name})"
            TokenKind.JS_OBJECT_PATH -> "'{${token.name}}'"
        }
        WriteCommandAction.runWriteCommandAction(project, "Replace Hardcoded Value with Token", null, {
            editor.document.replaceString(hit.replaceStart, hit.replaceEndExclusive, replacement)
        })
    }

    private fun isJsExt(ext: String?): Boolean =
        ext in setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")

    private fun compatibleKinds(ext: String?): Set<TokenKind> = when (ext) {
        "ts", "tsx", "js", "jsx", "mjs", "cjs" -> setOf(TokenKind.JS_OBJECT_PATH)
        "scss", "sass" -> setOf(TokenKind.SCSS_VARIABLE, TokenKind.CSS_CUSTOM_PROPERTY)
        "css" -> setOf(TokenKind.CSS_CUSTOM_PROPERTY)
        else -> TokenKind.entries.toSet()
    }

    private fun anchorPoint(editor: Editor, anchorScreenLocation: Point?): RelativePoint {
        if (anchorScreenLocation != null) {
            val component = editor.contentComponent
            val local = Point(anchorScreenLocation).also { javax.swing.SwingUtilities.convertPointFromScreen(it, component) }
            return RelativePoint(component, local)
        }
        // Fall back to the caret position.
        val visual = editor.caretModel.primaryCaret.visualPosition
        val xy = editor.visualPositionToXY(visual)
        xy.translate(0, editor.lineHeight)
        return RelativePoint(editor.contentComponent, xy)
    }
}
