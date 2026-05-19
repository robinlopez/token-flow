package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import fr.fsh.tokendesigner.util.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import fr.fsh.tokendesigner.analyze.DesignSystemAnalyzer
import fr.fsh.tokendesigner.inspection.LiteralFinder
import fr.fsh.tokendesigner.inspection.PropertyContext
import fr.fsh.tokendesigner.inspection.SelectorContext
import fr.fsh.tokendesigner.inspection.SuggestionEngine
import fr.fsh.tokendesigner.inspection.TokenSuggestion
import fr.fsh.tokendesigner.inspection.TokenValueIndex
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.settings.TokenSelectorSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class HardcodedValuesPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val rowsContainer = object : JPanel() {
        // Track viewport width so child rows fit horizontally without
        // triggering a horizontal scrollbar.
        override fun getPreferredSize(): Dimension {
            val pref = super.getPreferredSize()
            val parentW = parent?.width ?: pref.width
            return Dimension(parentW, pref.height)
        }
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.background()
    }
    private val emptyState = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }
    private val statusLabel = JBLabel(" ").apply {
        border = JBUI.Borders.empty(6, 8)
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }
    private val centerCard = JPanel(BorderLayout())
    private val scroll = JBScrollPane(rowsContainer).apply {
        border = null
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 16
    }

    private val rowComponents = mutableListOf<HardcodedRowComponent>()
    private val replaceSelectedAction = ReplaceSelectedAction()
    private val collapsedSelectors = mutableSetOf<String>()
    private var lastRows: List<HardcodedRow> = emptyList()

    @Volatile private var currentFile: VirtualFile? = null

    private val scopeLabel = JBLabel("Scope: None").apply {
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        font = com.intellij.util.ui.JBFont.small()
    }

    init {
        setupToolbar()
        setupContent()
        subscribeToEditorChanges()
        scheduleRefresh()
    }

    // ─── Setup ────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        val refresh = object : AnAction("Refresh", "Re-scan the active file", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = scheduleRefresh()
        }
        val replaceAll = object : AnAction(
            "Replace All",
            "Apply the suggested token to every row that has a match",
            AllIcons.Actions.RunAll,
        ) {
            override fun actionPerformed(e: AnActionEvent) = applyRows(rowComponents.map { it.row })
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = rowComponents.any { it.row.suggestions.isNotEmpty() }
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val group = DefaultActionGroup(refresh, replaceSelectedAction, replaceAll)
        val toolbar = ActionManager.getInstance().createActionToolbar("DesignTokenHardcoded", group, true)
        toolbar.targetComponent = this

        val topToolbarRow = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            val scopeBox = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(scopeLabel)
                add(ScopeUIUtils.createScopeHelpButton(project))
            }
            // GridBagLayout vertically centres its single child — keeps the
            // scope chip aligned with the action-toolbar buttons on its left.
            val scopeCenter = JPanel(java.awt.GridBagLayout()).apply {
                isOpaque = false
                add(scopeBox)
            }
            add(scopeCenter, BorderLayout.EAST)
        }
        setToolbar(topToolbarRow)
    }

    private fun setupContent() {
        centerCard.add(scroll, BorderLayout.CENTER)
        val main = JPanel(BorderLayout()).apply {
            add(centerCard, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        setContent(main)
    }

    private fun subscribeToEditorChanges() {
        project.messageBus.connect(project).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = scheduleRefresh()
            },
        )
    }

    // ─── Refresh ──────────────────────────────────────────────────────────

    fun scheduleRefresh() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            showEmpty("Open a SCSS/CSS file to scan it for hardcoded values.")
            return
        }
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        currentFile = file
        if (file == null) {
            showEmpty("The active editor has no file backing.")
            return
        }
        val ext = file.extension?.lowercase()
        if (ext !in SUPPORTED_EXTS) {
            showEmpty(
                "Hardcoded value detection runs on .scss / .sass / .css / " +
                    ".ts / .tsx / .js / .jsx files.<br/>Active: ${file.name}"
            )
            return
        }

        val scopes = fr.fsh.tokendesigner.settings.ScopeResolver.activeScopesFor(project, file)
        val deepest = scopes.lastOrNull { !it.isCommon }
        scopeLabel.text = if (deepest != null) {
            "Scope: ${deepest.name.ifBlank { "(unnamed)" }}"
        } else if (scopes.isNotEmpty()) {
            "Scope: Common"
        } else {
            "Scope: None"
        }

        object : Task.Backgroundable(project, "Scanning hardcoded values", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val allTokens = readAction { TokenIndex.getInstance(project).get(file) }
                // Same kind-filter logic as the inspection: only suggest tokens
                // whose reference syntax fits the destination file. Without it
                // a TS file scan would surface CSS `var(--…)` matches that
                // can't be inserted there.
                val tokens = allTokens.filter { it.kind in compatibleKinds(ext) }
                val ignoredNames = DesignSystemAnalyzer.getInstance(project).collectIgnoredNames(file)
                val text = readAction { editor.document.text }
                val rows = computeRows(tokens, ignoredNames, text)
                ApplicationManager.getApplication().invokeLater {
                    showResult(rows, allTokens.isEmpty(), file)
                }

            }
        }.queue()
    }

    private fun compatibleKinds(ext: String?): Set<fr.fsh.tokendesigner.model.TokenKind> = when (ext) {
        "ts", "tsx", "js", "jsx", "mjs", "cjs" -> setOf(
            fr.fsh.tokendesigner.model.TokenKind.JS_OBJECT_PATH,
            fr.fsh.tokendesigner.model.TokenKind.JS_RUNTIME_PROPERTY,
            fr.fsh.tokendesigner.model.TokenKind.JS_RUNTIME_FUNCTION,
        )
        "scss", "sass" -> setOf(
            fr.fsh.tokendesigner.model.TokenKind.SCSS_VARIABLE,
            fr.fsh.tokendesigner.model.TokenKind.CSS_CUSTOM_PROPERTY,
        )
        "css" -> setOf(fr.fsh.tokendesigner.model.TokenKind.CSS_CUSTOM_PROPERTY)
        else -> fr.fsh.tokendesigner.model.TokenKind.entries.toSet()
    }

    private fun computeRows(tokens: List<DesignToken>, ignoredNames: Set<String>, text: String): List<HardcodedRow> {
        val valueIndex = TokenValueIndex(tokens)
        val out = mutableListOf<HardcodedRow>()
        val isJs = currentFile?.extension?.lowercase() in JS_EXTS
        val settings = TokenSelectorSettings.getInstance(project)
        val inspectVariableDeclarations = settings.inspectVariableDeclarations
        val tokenNames = tokens.map { it.name }.toSet()

        for (hit in LiteralFinder.findIn(text)) {
            if (hit.isDeclaration && (!inspectVariableDeclarations || (hit.declarationName != null && hit.declarationName in tokenNames))) continue
            if (hit.kind == LiteralFinder.Kind.NUMBER && !isJs) continue
            if (isJs && hit.insidePartialString) continue

            val isBrokenRef = hit.kind == LiteralFinder.Kind.REFERENCE && run {
                val name = DesignSystemAnalyzer.extractTokenName(hit.text)
                if (name == null || name.startsWith("$") || name in ignoredNames) {
                    false
                } else {
                    DesignSystemAnalyzer.resolveReferenceMatch(name, tokenNames, ignoredNames) == null
                }
            }

            // Only show references if they are broken
            if (hit.kind == LiteralFinder.Kind.REFERENCE && !isBrokenRef) continue

            val expected = PropertyContext.detectAt(text, hit.startOffset)
            val propertyName = PropertyContext.detectPropertyNameAt(text, hit.startOffset)
            val suggestions = SuggestionEngine.findSuggestions(hit, valueIndex, tokens, expected)
            val selector = SelectorContext.selectorAt(text, hit.startOffset)
            out += HardcodedRow(
                literal = hit.replaceText,
                startOffset = hit.replaceStart,
                endOffsetExclusive = hit.replaceEndExclusive,
                kind = hit.kind,
                suggestions = suggestions,
                selector = selector,
                category = expected,
                propertyName = propertyName,
                isBrokenReference = isBrokenRef
            )
        }
        return out
    }

    private fun showResult(rows: List<HardcodedRow>, noTokens: Boolean, file: VirtualFile) {
        if (rows.isEmpty()) {
            if (noTokens) {
                showEmpty(
                    "No design tokens are visible from <i>${file.name}</i>.<br/>" +
                        "Either add this file's folder to a scope's <b>root</b>, " +
                        "or configure a <b>common</b> scope (empty root) so its tokens apply everywhere.",
                )
            } else {
                showEmpty("No hardcoded values detected in <i>${file.name}</i>. ✓")
            }
            return
        }

        rebuildRows(rows)
        val matched = rows.count { it.suggestions.isNotEmpty() }
        val unmatched = rows.size - matched
        statusLabel.text = "${rows.size} value(s) — $matched with token suggestion, $unmatched without"
    }

    private fun rebuildRows(rows: List<HardcodedRow>) {
        lastRows = rows
        centerCard.removeAll()
        centerCard.add(scroll, BorderLayout.CENTER)
        rowsContainer.removeAll()
        rowComponents.clear()

        // Group preserving source order; rows from the same selector cluster together.
        val grouped = LinkedHashMap<String, MutableList<HardcodedRow>>()
        for (r in rows) grouped.getOrPut(r.selector) { mutableListOf() } += r

        for ((selector, group) in grouped) {
            val isCollapsed = selector in collapsedSelectors
            rowsContainer.add(SelectorHeader(selector, group.size, isCollapsed) { toggleSelector(selector) })
            if (isCollapsed) continue
            for (row in group) {
                val comp = HardcodedRowComponent(
                    row = row,
                    onCheckChanged = { updateSelectedAction() },
                    onLocate = { revealRow(it) },
                    onReplace = { applyRows(listOf(it)) },
                )
                comp.alignmentX = Component.LEFT_ALIGNMENT
                rowComponents += comp
                rowsContainer.add(comp)
            }
        }
        rowsContainer.add(javax.swing.Box.createVerticalGlue())
        rowsContainer.revalidate()
        rowsContainer.repaint()
        updateSelectedAction()
    }

    private fun toggleSelector(selector: String) {
        if (!collapsedSelectors.add(selector)) collapsedSelectors.remove(selector)
        rebuildRows(lastRows)
    }

    private fun showEmpty(html: String) {
        rowComponents.clear()
        rowsContainer.removeAll()
        emptyState.text = "<html><div style='text-align:center;padding:24px;'>$html</div></html>"
        centerCard.removeAll()
        centerCard.add(emptyState, BorderLayout.CENTER)
        centerCard.revalidate(); centerCard.repaint()
        statusLabel.text = " "
    }

    private fun updateSelectedAction() {
        val ticked = rowComponents.count { it.isChecked }
        replaceSelectedAction.tickedCount = ticked
    }

    // ─── Actions ──────────────────────────────────────────────────────────

    private fun applyRows(rows: List<HardcodedRow>) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val toApply = rows.mapNotNull { row ->
            row.suggestions.getOrNull(row.selectedIndex)?.let { row to it }
        }
        if (toApply.isEmpty()) return
        val sorted = toApply.sortedByDescending { it.first.startOffset }
        WriteCommandAction.runWriteCommandAction(project, "Replace Hardcoded Values with Tokens", null, {
            for ((row, suggestion) in sorted) {
                val replacement = textForInsertion(suggestion.token)
                editor.document.replaceString(row.startOffset, row.endOffsetExclusive, replacement)
            }
        })
        scheduleRefresh()
    }

    private fun revealRow(row: HardcodedRow) {
        val editor: Editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        editor.caretModel.moveToOffset(row.startOffset)
        editor.selectionModel.setSelection(row.startOffset, row.endOffsetExclusive)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        editor.contentComponent.requestFocusInWindow()
    }

    private fun textForInsertion(token: DesignToken): String =
        fr.fsh.tokendesigner.model.TokenReference.expression(token)

    // ─── Toolbar action: Replace Selected ────────────────────────────────

    private inner class ReplaceSelectedAction : AnAction(
        "Replace Selected",
        "Apply the suggested token to every checked row",
        AllIcons.Diff.ApplyNotConflicts,
    ) {
        var tickedCount: Int = 0

        override fun actionPerformed(e: AnActionEvent) {
            applyRows(rowComponents.filter { it.isChecked }.map { it.row })
        }

        override fun update(e: AnActionEvent) {
            val n = rowComponents.count { it.isChecked && it.row.suggestions.isNotEmpty() }
            e.presentation.isEnabled = n > 0
            e.presentation.text = if (n > 0) "Replace $n selected" else "Replace Selected"
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    // ─── Domain ──────────────────────────────────────────────────────────

    data class HardcodedRow(
        val literal: String,
        val startOffset: Int,
        val endOffsetExclusive: Int,
        val kind: LiteralFinder.Kind,
        val suggestions: List<TokenSuggestion>,
        val selector: String,
        val category: TokenCategory?,
        val propertyName: String? = null,
        var selectedIndex: Int = 0,
        val isBrokenReference: Boolean = false,
    )


    // ─── Visual components ───────────────────────────────────────────────

    /** Clickable section header with chevron + count badge. */
    private class SelectorHeader(
        label: String,
        count: Int,
        collapsed: Boolean,
        onToggle: () -> Unit,
    ) : JPanel(BorderLayout()) {
        init {
            background = JBColor.background()
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineTop(JBColor.border()),
                JBUI.Borders.empty(6, 10, 4, 10),
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))

            val chevron = JLabel(
                if (collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown,
            ).apply { border = JBUI.Borders.emptyRight(6) }
            val text = JBLabel("${label.uppercase()} · $count").apply {
                foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
                font = font.deriveFont(font.size2D - 1f).deriveFont(java.awt.Font.BOLD)
            }
            val left = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(chevron); add(text)
            }
            add(left, BorderLayout.WEST)

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onToggle()
            })
        }
    }

    /**
     * Single-row layout, GridBag for predictable column alignment:
     *
     *  [✓] [cat] [●] [literal────────────] → [●] [suggestion────────] [delta] [⌖] [↩]
     *   24   18   16    flex            18   18    flex             50   24   24
     */
    private class HardcodedRowComponent(
        val row: HardcodedRow,
        onCheckChanged: () -> Unit,
        onLocate: (HardcodedRow) -> Unit,
        onReplace: (HardcodedRow) -> Unit,
    ) : JPanel(GridBagLayout()) {

        private val checkbox = JBCheckBox().apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            addActionListener { onCheckChanged() }
        }
        val isChecked: Boolean get() = checkbox.isSelected

        init {
            isOpaque = false
            border = JBUI.Borders.empty(3, 8)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(38))

            val literalSwatch = swatchFor(row.kind, row.literal, row.category, row.isBrokenReference).apply {
                toolTipText = if (row.isBrokenReference) "Non-existent token: ${row.literal}" 
                              else row.propertyName?.let { "Used as: $it" }
                    ?: "Hardcoded ${row.kind.name.lowercase()} value"
            }
            // Pin the literal column to a fixed width so every row in every
            // selector group lines up its arrow / suggestion column at the
            // exact same x-coordinate.
            val literalLabel = JLabel(row.literal).apply {
                border = JBUI.Borders.emptyLeft(6)
                toolTipText = row.propertyName?.let { "Used as: $it" }
                preferredSize = Dimension(JBUI.scale(LITERAL_COL_WIDTH), JBUI.scale(22))
                minimumSize = preferredSize
            }
            val arrow = JLabel(AllIcons.General.ArrowRight).apply {
                horizontalAlignment = SwingConstants.CENTER
                preferredSize = Dimension(JBUI.scale(20), JBUI.scale(18))
            }
            val (suggestionSwatch, suggestionWidget) = buildSuggestionWidgets(row)

            val locateBtn = iconButton(AllIcons.General.Locate, "Reveal in editor") { onLocate(row) }
            val applyBtn = iconButton(AllIcons.Diff.ApplyNotConflicts, "Apply suggested token") {
                if (row.suggestions.isNotEmpty()) onReplace(row)
            }.apply { isEnabled = row.suggestions.isNotEmpty() }

            val baseGbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.NONE
                insets = Insets(0, 2, 0, 2)
                weighty = 1.0
            }
            var x = 0
            fun add(comp: JComponent, weight: Double = 0.0, fill: Int = GridBagConstraints.NONE, anchor: Int = GridBagConstraints.WEST) {
                val g = baseGbc.clone() as GridBagConstraints
                g.gridx = x++
                g.gridy = 0
                g.weightx = weight
                g.fill = fill
                g.anchor = anchor
                add(comp, g)
            }
            add(checkbox)
            add(literalSwatch)
            // No flex on literal: fixed width so the arrow column always starts
            // at the same x. Excess width goes entirely to the suggestion column.
            add(literalLabel, weight = 0.0, fill = GridBagConstraints.NONE)
            add(arrow, anchor = GridBagConstraints.CENTER)
            add(suggestionSwatch)
            add(suggestionWidget, weight = 1.0, fill = GridBagConstraints.HORIZONTAL)
            add(locateBtn)
            add(applyBtn)
        }

        private companion object {
            const val LITERAL_COL_WIDTH = 140
        }

        private fun swatchFor(kind: LiteralFinder.Kind, literal: String, category: TokenCategory?, isBroken: Boolean = false): RoundSwatch {
            val sw = RoundSwatch(diameterPx = 16)
            if (isBroken) {
                sw.color = JBColor(0xFFCC00, 0xFFCC00) // Bright yellow
                sw.glyph = "!"
                sw.glyphColor = Color.BLACK
                sw.toolTipText = "Non-existent token reference"
                return sw
            }


            sw.color = if (kind == LiteralFinder.Kind.COLOR) ColorParser.parse(literal) else null
            sw.glyph = if (kind == LiteralFinder.Kind.COLOR) null else CategoryGlyphs.glyphFor(category)
            return sw
        }


        private fun buildSuggestionWidgets(row: HardcodedRow): Pair<RoundSwatch, JComponent> {
            val swatch = RoundSwatch(diameterPx = 16)
            if (row.suggestions.isEmpty()) {
                swatch.color = null
                swatch.glyph = "?"
                swatch.toolTipText = "No matching token"
                val label = JBLabel("(no matching token)").apply {
                    foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
                    font = font.deriveFont(java.awt.Font.ITALIC)
                }
                return swatch to label
            }

            val current = row.suggestions[row.selectedIndex.coerceAtMost(row.suggestions.size - 1)]
            applySwatch(swatch, current)

            val widget: JComponent = if (row.suggestions.size == 1) {
                JLabel(current.token.name).apply {
                    font = font.deriveFont(font.size2D - 1f)
                    toolTipText = "Value: ${current.token.resolvedValue}"
                }
            } else {
                JComboBox<String>().apply {
                    row.suggestions.forEach { addItem(it.token.name) }
                    selectedIndex = row.selectedIndex
                    font = font.deriveFont(font.size2D - 1f)
                    // Slightly bigger so the descender of the font isn't clipped.
                    preferredSize = Dimension(JBUI.scale(240), JBUI.scale(26))
                    minimumSize = preferredSize
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))
                    addActionListener {
                        row.selectedIndex = selectedIndex.coerceAtLeast(0)
                        applySwatch(swatch, row.suggestions[row.selectedIndex])
                    }
                }
            }
            return swatch to widget
        }

        private fun applySwatch(swatch: RoundSwatch, suggestion: TokenSuggestion) {
            val token = suggestion.token
            if (token.category == TokenCategory.COLOR) {
                swatch.color = ColorParser.parse(token.resolvedValue)
                swatch.glyph = if (suggestion.exact) null else "≈"
            } else {
                swatch.color = null
                swatch.glyph = if (suggestion.exact) CategoryGlyphs.glyphFor(token.category) else "≈"
            }
            swatch.toolTipText = if (suggestion.exact) {
                "Exact match — ${token.resolvedValue}"
            } else {
                "Approximate match (≈${(suggestion.delta * 100).toInt()}% off) — ${token.resolvedValue}"
            }
        }

        private fun iconButton(icon: javax.swing.Icon, tooltip: String, onClick: () -> Unit): JLabel =
            JLabel(icon).apply {
                this.toolTipText = tooltip
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2, 6)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        if (isEnabled) onClick()
                    }
                })
            }
    }

    companion object {
        private val SUPPORTED_EXTS = setOf(
            "scss", "sass", "css",
            "ts", "tsx", "js", "jsx", "mjs", "cjs",
        )
        private val JS_EXTS = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")
    }
}

