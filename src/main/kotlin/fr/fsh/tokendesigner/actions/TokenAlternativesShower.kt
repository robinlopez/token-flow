package fr.fsh.tokendesigner.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
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
import fr.fsh.tokendesigner.model.TokenReference
import fr.fsh.tokendesigner.scanner.CandidateSorter
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.scanner.TokenLocator
import fr.fsh.tokendesigner.ui.PopupRow
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
                val tokens = runReadAction { TokenIndex.getInstance(project).get(file) }
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
        // Helper call (`spacing(0.5)`, `radius(2)`): synthesise the scale of
        // common multipliers from the indexed function's unit, pre-select the
        // current value, and let the user pick a neighbouring scale step.
        val helperCall = HELPER_CALL_REGEX.matchEntire(hit.name)
        if (helperCall != null) {
            val helperName = helperCall.groupValues[1]
            val helper = all.firstOrNull {
                it.kind == TokenKind.JS_RUNTIME_FUNCTION &&
                    it.name == helperName &&
                    it.functionUnit != null
            }
            if (helper != null) {
                showHelperScalePopup(project, editor, hit, helper, helperCall.groupValues[2], anchorScreenLocation)
                return
            }
        }
        // Pivot lookup must tolerate JS preset paths whose mode segment and/or
        // export binding name were stripped at indexing time. `'{token.modeLight.x.y}'`
        // resolves to canonical `x.y` (binding `token.` + mode `modeLight` both stripped).
        val tokenNames = all.map { it.name }.toSet()
        val resolved = fr.fsh.tokendesigner.scanner.TokenNameParser.resolveReference(hit.name, tokenNames)
        val pivot = resolved?.let { r -> all.firstOrNull { it.name == r.tokenName } }
        val bindingPrefix = resolved?.bindingPrefix ?: ""
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
                if (selected is TokenPopupRow) replaceToken(project, editor, hit, selected.token, bindingPrefix)
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

    /** Multipliers offered by the helper-scale popup. Roughly aligned with
     *  Material / Tailwind spacing scales. */
    private val HELPER_SCALE_MULTIPLIERS = listOf(
        0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0,
    )

    private val HELPER_CALL_REGEX = Regex("^([A-Za-z_$][\\w$]*)\\((.*)\\)$")

    /**
     * Variant of [showPopup] specialised for callable helpers (`spacing(0.5)`).
     * The candidate list is **synthesised** from the helper's `functionUnit`
     * × every entry of [HELPER_SCALE_MULTIPLIERS]; the entry matching the
     * current argument is pre-selected so arrow-key navigation goes up/down
     * the scale naturally.
     */
    private fun showHelperScalePopup(
        project: Project,
        editor: Editor,
        hit: TokenLocator.Hit,
        helper: DesignToken,
        currentArg: String,
        anchorScreenLocation: Point?,
    ) {
        val unit = helper.functionUnit ?: return
        val currentMultiplier = currentArg.trim().toDoubleOrNull()
        val variants = HELPER_SCALE_MULTIPLIERS.map { m ->
            val produced = unit * m
            helper.copy(
                name = "${helper.name}(${formatScalarMultiplier(m)})",
                rawValue = formatScalarValue(produced),
                resolvedValue = formatScalarValue(produced),
            )
        }
        val pivot = variants.firstOrNull { v ->
            currentMultiplier != null &&
                HELPER_CALL_REGEX.matchEntire(v.name)?.groupValues?.get(2)
                    ?.toDoubleOrNull()?.let { kotlin.math.abs(it - currentMultiplier) < 1e-6 } == true
        }
        val rows = variants.map { TokenPopupRow(it) }
        val pivotRow = pivot?.let { p -> rows.firstOrNull { it.token == p } }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setRenderer(PopupRowRenderer())
            .setTitle("${helper.name} scale — current: ${hit.name}")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setNamerForFiltering { it.filterKey }
            .setItemChosenCallback { selected ->
                if (selected is TokenPopupRow) {
                    val replacement = TokenReference.expression(selected.token)
                    if (replacement != hit.name) {
                        WriteCommandAction.runWriteCommandAction(project, "Replace Helper Scale", null, {
                            editor.document.replaceString(hit.startOffset, hit.endOffset, replacement)
                        })
                    }
                }
            }
            .setSelectedValue(pivotRow, true)
            .setMinSize(JBUI.size(420, 320))
            .setDimensionServiceKey("DesignTokenSelector.HelperScalePopup")
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

    private fun formatScalarMultiplier(m: Double): String =
        if (m == m.toLong().toDouble()) m.toLong().toString() else m.toString()

    private fun formatScalarValue(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun replaceToken(
        project: Project,
        editor: Editor,
        hit: TokenLocator.Hit,
        replacement: DesignToken,
        bindingPrefix: String = "",
    ) {
        if (replacement.name == hit.name) return
        // The Hit range matches the full reference syntax (incl. `'{…}'` for JS
        // paths), so wrap the replacement in the form expected by the kind.
        // For JS tokens, the canonical name has had its mode segment AND a
        // possible binding-name prefix (`token.`) stripped at indexing time —
        // re-inject both from the original hit so the substitution stays
        // self-consistent.
        val replaceText = when (replacement.kind) {
            TokenKind.JS_OBJECT_PATH -> {
                // Compute mode index against the *stripped* hit so it aligns
                // with the canonical replacement name (which has no binding).
                val strippedHit = if (bindingPrefix.isNotEmpty())
                    hit.name.removePrefix(bindingPrefix) else hit.name
                val raw = fr.fsh.tokendesigner.scanner.TokenNameParser.rawModeSegmentOf(strippedHit)
                val idx = fr.fsh.tokendesigner.scanner.TokenNameParser.modeSegmentIndex(strippedHit)
                val withMode = if (raw != null && idx >= 0) {
                    fr.fsh.tokendesigner.scanner.TokenNameParser
                        .injectModeSegment(replacement.name, raw, idx)
                } else replacement.name
                "'{$bindingPrefix$withMode}'"
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
                val allTokens = runReadAction { TokenIndex.getInstance(project).get(file) }
                val tokens = allTokens.filter { it.kind in compatibleKinds(ext) }
                val text = runReadAction { editor.document.text }
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
        val replacement = TokenReference.expression(token)
        WriteCommandAction.runWriteCommandAction(project, "Replace Hardcoded Value with Token", null, {
            editor.document.replaceString(hit.replaceStart, hit.replaceEndExclusive, replacement)
        })
    }

    private fun isJsExt(ext: String?): Boolean =
        ext in setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")

    private fun compatibleKinds(ext: String?): Set<TokenKind> = when (ext) {
        "ts", "tsx", "js", "jsx", "mjs", "cjs" -> setOf(
            TokenKind.JS_OBJECT_PATH,
            TokenKind.JS_RUNTIME_PROPERTY,
            TokenKind.JS_RUNTIME_FUNCTION,
        )
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

    // ─── Value-based auto-popup ──────────────────────────────────────────

    /**
     * Shows the alternatives popup for a **partial value** the user is typing.
     *
     * Called by the [fr.fsh.tokendesigner.completion.ValueCompletionTypedHandler]
     * when the line matches `property: <partial>`.  Unlike [showForLiteral] this
     * method does *not* require a complete [LiteralFinder.Hit] — it searches all
     * tokens whose `resolvedValue` starts with [partialValue] (case-insensitive),
     * boosts the category that matches [propertyName], and shows the standard
     * categorised popup.
     *
     * @param replaceStart absolute offset of the first character of the value
     *                     being typed (used to replace it on selection).
     * @param replaceEnd   absolute offset just past the last typed character.
     */
    fun showForPartialValue(
        project: Project,
        editor: Editor,
        partialValue: String,
        propertyName: String,
        replaceStart: Int,
        replaceEnd: Int,
    ) {
        activePopup.getAndSet(null)?.takeIf { it.isVisible }?.cancel()

        object : Task.Backgroundable(project, "Looking up token suggestions", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
                val ext = file?.extension?.lowercase()
                val allTokens = runReadAction { TokenIndex.getInstance(project).get(file) }
                val tokens = allTokens.filter { it.kind in compatibleKinds(ext) }
                val expected = PropertyContext.categoryFor(propertyName)

                // Find tokens whose resolved value starts with the partial text.
                val pv = partialValue.trim().lowercase()
                val matching = if (pv.isEmpty()) {
                    // Empty value: show all tokens of the expected category (or all if unknown).
                    if (expected != null) tokens.filter { it.category == expected }
                    else tokens
                } else {
                    tokens.filter { token ->
                        val rv = token.resolvedValue.trim().lowercase()
                        rv.startsWith(pv) || rv == pv
                    }
                }
                if (matching.isEmpty()) {
                    // No matching tokens — don't show anything (avoid spamming).
                    return
                }
                val sorted = matching.sortedBy { it.name }
                val rows = RowGrouping.buildRows(sorted, null)
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    showPartialValuePopup(project, editor, rows, partialValue, expected, replaceStart, replaceEnd)
                }
            }
        }.queue()
    }

    private fun showPartialValuePopup(
        project: Project,
        editor: Editor,
        rows: List<PopupRow>,
        partialValue: String,
        expected: fr.fsh.tokendesigner.model.TokenCategory?,
        replaceStart: Int,
        replaceEnd: Int,
    ) {
        val title = buildString {
            append("Tokens matching \"$partialValue\"")
            expected?.let { append(" — ${it.name.lowercase()}") }
        }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setRenderer(PopupRowRenderer())
            .setTitle(title)
            .setMovable(true)
            .setResizable(true)
            // Critical for the as-you-type flow: keep the editor focused so
            // subsequent keystrokes land in the source, not in the popup's
            // filter field. The popup behaves as a passive hint — click a row
            // to apply, or press Escape to dismiss. Stealing focus on every
            // typed character would make `fontSize: 34` impossible to type
            // through (the `4` would end up in the popup's search).
            .setRequestFocus(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .setNamerForFiltering { it.filterKey }
            .setItemChosenCallback { selected ->
                if (selected is TokenPopupRow) {
                    val replacement = TokenReference.expression(selected.token)
                    WriteCommandAction.runWriteCommandAction(project, "Replace Value with Token", null, {
                        editor.document.replaceString(replaceStart, replaceEnd, replacement)
                    })
                }
            }
            .setMinSize(JBUI.size(580, 320))
            .setDimensionServiceKey("DesignTokenSelector.ValueCompletionPopup")
            .createPopup()

        activePopup.set(popup)
        popup.showInBestPositionFor(editor)
    }
}
