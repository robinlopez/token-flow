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
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
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

    /**
     * Properties (case-insensitive name) currently excluded from display.
     * `null` is represented by [UNKNOWN_PROPERTY_KEY] so the popup can show
     * an explicit *Other* bucket for hits without a detectable CSS property
     * (e.g. literals in JS object literals).
     */
    private val excludedProperties = mutableSetOf<String>()
    private val searchField = SearchTextField()
    private var filterButtonRef: RoundIconButton? = null

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
        // The "hide rows without a suggestion" toggle moved out of the toolbar
        // and into the filter popup so all view-narrowing controls live in the
        // same place — see [showFilterPopup].
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

        // Search field + filter button. Mirrors the layout used in the Library
        // tab: typing in the field filters the visible rows; the filter button
        // opens a popup with detected-property checkboxes and the
        // "Hide rows without a suggestion" toggle.
        searchField.textEditor.emptyText.text =
            "Search hardcoded values (literal, selector, suggested token)…"
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = rebuildRows(lastRows)
        })

        val filterButton = RoundIconButton(AllIcons.General.Filter, "Filter by detected property…") {
            showFilterPopup(it)
        }
        filterButton.isActive = isAnyFilterActive()
        this.filterButtonRef = filterButton

        // GridBagLayout centres the button vertically against the slightly
        // taller search field — same trick the Library uses.
        val actionsCenter = JPanel(java.awt.GridBagLayout()).apply {
            isOpaque = false
            add(filterButton)
        }
        val searchRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(4, 4, 4, 4)
            add(searchField, BorderLayout.CENTER)
            add(actionsCenter, BorderLayout.EAST)
        }

        val main = JPanel(BorderLayout()).apply {
            add(searchRow, BorderLayout.NORTH)
            add(centerCard, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        setContent(main)
    }

    private fun isAnyFilterActive(): Boolean =
        excludedProperties.isNotEmpty() ||
            TokenSelectorSettings.getInstance(project).hardcodedHideUnmatched ||
            searchField.text.isNotBlank()

    private fun refreshFilterButtonState() {
        filterButtonRef?.isActive = isAnyFilterActive()
        filterButtonRef?.repaint()
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
                "Hardcoded value detection runs on .scss / .sass / .css / .vue / " +
                    ".ts / .tsx / .js / .jsx files.<br/>Active: ${file.name}"
            )
            return
        }

        // Token-source files (declared in any scope's `sourcePaths`) define
        // values — flagging them as hardcoded would be pure noise. Show an
        // explanatory empty state and bail out before the scan.
        if (fr.fsh.tokendesigner.settings.ScopeResolver.isTokenSourceFile(project, file)) {
            showEmpty(
                "<i>${file.name}</i> is declared as a <b>token source</b> in the active scope.<br/>" +
                    "Hardcoded value detection is disabled here — every literal in this file " +
                    "is part of a token definition.<br/>" +
                    "<span style='color:#888'>Edit the scope's sources in Settings → Token Flow if " +
                    "you want to include / exclude this file.</span>"
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
                val externalPrefixes = fr.fsh.tokendesigner.settings.ScopeResolver
                    .activeScopesFor(project, file)
                    .flatMap { it.externalPrefixes }
                    .distinct()
                val text = readAction { editor.document.text }
                val rows = computeRows(tokens, ignoredNames, externalPrefixes, text)
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
        // Vue SFCs can hold both `<style>` and `<style lang="scss">` blocks
        // — accept the union so a single file may produce both kinds.
        "vue" -> setOf(
            fr.fsh.tokendesigner.model.TokenKind.SCSS_VARIABLE,
            fr.fsh.tokendesigner.model.TokenKind.CSS_CUSTOM_PROPERTY,
        )
        "css" -> setOf(fr.fsh.tokendesigner.model.TokenKind.CSS_CUSTOM_PROPERTY)
        else -> fr.fsh.tokendesigner.model.TokenKind.entries.toSet()
    }

    private fun computeRows(
        tokens: List<DesignToken>,
        ignoredNames: Set<String>,
        externalPrefixes: List<String>,
        text: String,
    ): List<HardcodedRow> {
        val valueIndex = TokenValueIndex(tokens)
        val out = mutableListOf<HardcodedRow>()
        val ext = currentFile?.extension?.lowercase()
        val isJs = ext in JS_EXTS
        val settings = TokenSelectorSettings.getInstance(project)
        val inspectVariableDeclarations = settings.inspectVariableDeclarations
        val tokenNames = tokens.map { it.name }.toSet()
        // Confine Vue scanning to its `<style>` blocks; literal hits in
        // `<template>` or `<script>` would otherwise pollute the report.
        val styleRanges: List<IntRange>? = if (ext == "vue") {
            fr.fsh.tokendesigner.scanner.VueStyleBlockExtractor.styleRanges(text)
        } else {
            null
        }

        for (hit in LiteralFinder.findIn(text)) {
            if (styleRanges != null && styleRanges.none { hit.startOffset in it }) continue
            if (hit.isDeclaration && (!inspectVariableDeclarations || (hit.declarationName != null && hit.declarationName in tokenNames))) continue
            // SCSS-map values (`"key": #001a70,`) are token definitions, not
            // hardcoded usages — drop them regardless of the declaration toggle.
            if (hit.insideTokenMap) continue
            if (hit.kind == LiteralFinder.Kind.NUMBER && !isJs) continue
            if (isJs && hit.insidePartialString) continue

            val isBrokenRef = hit.kind == LiteralFinder.Kind.REFERENCE && run {
                val name = DesignSystemAnalyzer.extractTokenName(hit.text)
                if (name == null || name.startsWith("$") || name in ignoredNames) {
                    false
                } else {
                    // Pass the configured external prefixes so framework-injected
                    // CSS vars (PrimeNG `--p-…`, Ionic `--ion-…`) don't flag as
                    // broken — there's no declaration in the tree, but they are
                    // valid at runtime.
                    DesignSystemAnalyzer.resolveReferenceMatch(
                        name, tokenNames, ignoredNames, externalPrefixes,
                    ) == null
                }
            }

            // Only show references if they are broken
            if (hit.kind == LiteralFinder.Kind.REFERENCE && !isBrokenRef) continue

            val expected = PropertyContext.detectAt(text, hit.startOffset)
            val expectedRole = PropertyContext.detectRoleAt(text, hit.startOffset)
            val propertyName = PropertyContext.detectPropertyNameAt(text, hit.startOffset)
            val suggestions = SuggestionEngine.findSuggestions(hit, valueIndex, tokens, expected, expectedRole)
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

        // rebuildRows owns the status line — it knows whether the unmatched
        // filter is on and produces a tally that reflects the visible subset.
        rebuildRows(rows)
    }

    private fun rebuildRows(rows: List<HardcodedRow>) {
        lastRows = rows
        centerCard.removeAll()
        centerCard.add(scroll, BorderLayout.CENTER)
        rowsContainer.removeAll()
        rowComponents.clear()

        val hideUnmatched = TokenSelectorSettings.getInstance(project).hardcodedHideUnmatched
        val needle = searchField.text.trim().lowercase()
        val terms = if (needle.isEmpty()) emptyList()
                    else needle.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val visibleRows = rows.filter { row ->
            // Broken references are kept even without a suggestion — those are
            // actionable (the user needs to fix the dangling token name), and
            // suppressing them would defeat the broken-ref UX.
            if (hideUnmatched && row.suggestions.isEmpty() && !row.isBrokenReference) return@filter false
            val propKey = row.propertyName?.lowercase() ?: UNKNOWN_PROPERTY_KEY
            if (propKey in excludedProperties) return@filter false
            if (terms.isNotEmpty() && !terms.all { rowMatchesTerm(row, it) }) return@filter false
            true
        }

        // Group preserving source order; rows from the same selector cluster together.
        val grouped = LinkedHashMap<String, MutableList<HardcodedRow>>()
        for (r in visibleRows) grouped.getOrPut(r.selector) { mutableListOf() } += r

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

        // Status line: keep the "matched / unmatched" tally based on the full
        // result so the user always sees how many were hidden by the filter.
        val total = rows.size
        val matched = rows.count { it.suggestions.isNotEmpty() }
        val unmatched = total - matched
        val hidden = total - visibleRows.size
        statusLabel.text = when {
            total == 0 -> " "
            hidden > 0 ->
                "$total value(s) — $matched with token suggestion · $hidden hidden by filters"
            else -> "$total value(s) — $matched with token suggestion, $unmatched without"
        }
        refreshFilterButtonState()
    }

    /**
     * AND-of-OR over a single needle term: a row matches if the term appears in
     * the literal, the selector, the property name, or any suggested token's
     * name / resolved value. Search is case-insensitive.
     */
    private fun rowMatchesTerm(row: HardcodedRow, term: String): Boolean {
        if (row.literal.lowercase().contains(term)) return true
        if (row.selector.lowercase().contains(term)) return true
        row.propertyName?.lowercase()?.let { if (it.contains(term)) return true }
        for (s in row.suggestions) {
            if (s.token.name.lowercase().contains(term)) return true
            if (s.token.resolvedValue.lowercase().contains(term)) return true
        }
        return false
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

    // ─── Filter popup ────────────────────────────────────────────────────

    /**
     * Filter popup, mirroring the Library's "kind & family" popup. Top section
     * is the "Hide rows without a token suggestion" toggle (relocated from the
     * toolbar). Bottom section lists every detected CSS property in the
     * current scan, grouped by [TokenCategory] (Spacing, Sizing, Color, …),
     * with a count badge per property. Toggling unticks/excludes that
     * property; the *Reset* button at the bottom clears every filter at once
     * (hide-unmatched + property excludes).
     */
    private fun showFilterPopup(invoker: JComponent) {
        // 1. Bucketise the current rows' properties by category. Use a
        //    LinkedHashMap keyed on a stable group order so the popup layout
        //    stays predictable across scans.
        val byGroup = LinkedHashMap<String, MutableMap<String, Int>>()
        PROPERTY_GROUP_ORDER.forEach { byGroup[it] = LinkedHashMap() }
        for (row in lastRows) {
            val prop = row.propertyName?.lowercase() ?: UNKNOWN_PROPERTY_KEY
            val group = if (prop == UNKNOWN_PROPERTY_KEY) "Other"
                        else groupLabelFor(prop)
            byGroup.getOrPut(group) { LinkedHashMap() }
                .merge(prop, 1, Int::plus)
        }

        val settings = TokenSelectorSettings.getInstance(project)
        val propertyCheckboxes = mutableListOf<Pair<String, javax.swing.JCheckBox>>()

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12)
        }

        // — Hide unmatched toggle (top of popup) ————————————————————————
        content.add(JLabel("<html><b>View</b></html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(0, 0, 4, 0)
        })
        val hideUnmatchedCb = JBCheckBox(
            "Hide rows without a token suggestion",
            settings.hardcodedHideUnmatched,
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = "Broken references stay visible even when this is on — they are actionable."
            addActionListener {
                settings.hardcodedHideUnmatched = isSelected
                rebuildRows(lastRows)
            }
        }
        content.add(hideUnmatchedCb)

        // — Properties section ——————————————————————————————————————
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
        content.add(javax.swing.JSeparator().apply { alignmentX = Component.LEFT_ALIGNMENT })
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
        content.add(JLabel("<html><b>Detected properties</b></html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(0, 0, 2, 0)
        })

        val anyProperty = byGroup.values.any { it.isNotEmpty() }
        // Mini search field that filters the visible checkboxes (and the group
        // headers that go with them) as the user types. Substring match,
        // case-insensitive. Hidden when there's nothing to filter to begin
        // with so the popup stays compact for sparse scans.
        val propertySearch = SearchTextField().apply {
            textEditor.emptyText.text = "Search a property…"
            isVisible = anyProperty
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }
        content.add(propertySearch)

        // Track every group header JLabel + its checkbox children so the
        // search listener can flip their visibility without rebuilding the
        // popup. Each group becomes (headerLabel, [propName → checkbox]).
        val groupVisibility = mutableListOf<Pair<JLabel, List<Pair<String, javax.swing.JCheckBox>>>>()
        if (!anyProperty) {
            content.add(JLabel("No properties to filter."))
        } else {
            for ((group, props) in byGroup) {
                if (props.isEmpty()) continue
                val header = JLabel("<html><i>$group</i></html>").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.empty(6, 0, 2, 0)
                }
                content.add(header)
                val cbsForGroup = mutableListOf<Pair<String, javax.swing.JCheckBox>>()
                for ((prop, count) in props.toSortedMap()) {
                    val display = if (prop == UNKNOWN_PROPERTY_KEY) "(unknown)" else prop
                    val cb = JBCheckBox("$display  ($count)").apply {
                        isSelected = prop !in excludedProperties
                        alignmentX = Component.LEFT_ALIGNMENT
                        addActionListener {
                            if (isSelected) excludedProperties.remove(prop)
                            else excludedProperties.add(prop)
                            rebuildRows(lastRows)
                        }
                    }
                    propertyCheckboxes += prop to cb
                    cbsForGroup += prop to cb
                    content.add(cb)
                }
                groupVisibility += header to cbsForGroup
            }
        }

        // No-match placeholder (added once, toggled on/off by the listener
        // depending on whether anything survives the search filter).
        val noMatchLabel = JLabel("<html><i>No property matches.</i></html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(6, 0, 2, 0)
            isVisible = false
        }
        if (anyProperty) content.add(noMatchLabel)

        propertySearch.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                val needle = propertySearch.text.trim().lowercase()
                var anyVisible = false
                for ((header, cbs) in groupVisibility) {
                    var groupHasMatch = false
                    for ((prop, cb) in cbs) {
                        val show = needle.isEmpty() || prop.contains(needle)
                        cb.isVisible = show
                        if (show) groupHasMatch = true
                    }
                    header.isVisible = groupHasMatch
                    if (groupHasMatch) anyVisible = true
                }
                noMatchLabel.isVisible = !anyVisible && needle.isNotEmpty()
                content.revalidate()
                content.repaint()
            }
        })

        // — Reset ————————————————————————————————————————————————
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
        content.add(javax.swing.JSeparator().apply { alignmentX = Component.LEFT_ALIGNMENT })
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        val resetBtn = javax.swing.JButton("Reset").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener {
                excludedProperties.clear()
                settings.hardcodedHideUnmatched = false
                hideUnmatchedCb.isSelected = false
                propertyCheckboxes.forEach { (_, cb) -> cb.isSelected = true }
                rebuildRows(lastRows)
            }
        }
        content.add(resetBtn)

        val scrollContent = JBScrollPane(content).apply {
            border = null
            preferredSize = Dimension(JBUI.scale(300), JBUI.scale(360))
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollContent, scrollContent)
            .setRequestFocus(true)
            .setMovable(true)
            .setResizable(true)
            .setTitle("Hardcoded values filters")
            .createPopup()
            .show(com.intellij.ui.awt.RelativePoint(invoker, java.awt.Point(0, invoker.height)))
    }

    /**
     * Maps a CSS property name to a human-readable category label used as a
     * popup section header. Falls back to "Other" for properties the
     * inspection can't tie to a known [TokenCategory].
     */
    private fun groupLabelFor(propertyName: String): String {
        val cat = PropertyContext.categoryFor(propertyName) ?: return "Other"
        return when (cat) {
            TokenCategory.SPACING -> "Spacing"
            TokenCategory.SIZING -> "Sizing"
            TokenCategory.TYPOGRAPHY -> "Typography"
            TokenCategory.COLOR -> "Color"
            TokenCategory.RADIUS -> "Radius"
            TokenCategory.SHADOW -> "Shadow"
            TokenCategory.DURATION -> "Duration"
            TokenCategory.EFFECTS -> "Effects"
            TokenCategory.LAYOUT -> "Layout"
            TokenCategory.Z_INDEX -> "Z-index"
            TokenCategory.BORDER -> "Border"
            TokenCategory.OPACITY -> "Opacity"
            TokenCategory.ICON -> "Icon"
            TokenCategory.OTHER -> "Other"
        }
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
            // Rows indent a hair to the right of the selector header chevron
            // (8 + 14 ≈ chevron x-coord) so they nest visually under their
            // group instead of starting flush left — matches the indentation
            // hierarchy users expect from a tree-like list. Right padding is
            // generous so the apply / locate icons don't crowd the edge.
            border = JBUI.Borders.empty(4, 22, 4, 8)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(40))

            val literalSwatch = swatchFor(row.kind, row.literal, row.category, row.isBrokenReference).apply {
                toolTipText = if (row.isBrokenReference) "Non-existent token: ${row.literal}" 
                              else row.propertyName?.let { "Used as: $it" }
                    ?: "Hardcoded ${row.kind.name.lowercase()} value"
            }
            // Pin the literal column to a fixed width so every row in every
            // selector group lines up its arrow / suggestion column at the
            // exact same x-coordinate. Narrower than before — most literals
            // are 4-8 chars (`12px`, `#fff`, `1.5rem`) and the previous 140 px
            // wasted half a row's horizontal real-estate.
            val literalLabel = JLabel(row.literal).apply {
                border = JBUI.Borders.emptyLeft(4)
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
            const val LITERAL_COL_WIDTH = 110
            // Combo height tuned so the selected value (which uses the
            // default JComboBox renderer) is fully visible in the closed
            // editor — 26 px was clipping descenders on most platforms.
            const val COMBO_HEIGHT = 30
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
                    preferredSize = Dimension(JBUI.scale(240), JBUI.scale(COMBO_HEIGHT))
                    minimumSize = preferredSize
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(COMBO_HEIGHT))
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
            "scss", "sass", "css", "vue",
            "ts", "tsx", "js", "jsx", "mjs", "cjs",
        )
        private val JS_EXTS = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")

        /** Sentinel for rows whose surrounding CSS property couldn't be detected. */
        private const val UNKNOWN_PROPERTY_KEY = "__unknown_property__"

        /**
         * Stable display order for property groups in the filter popup. Order
         * mirrors the most common author intent — spacing & sizing first, then
         * typography, color, etc. Categories that don't have any property in
         * the current scan get pruned at render time.
         */
        private val PROPERTY_GROUP_ORDER = listOf(
            "Spacing", "Sizing", "Typography", "Color", "Radius",
            "Shadow", "Border", "Effects", "Duration", "Layout",
            "Z-index", "Opacity", "Icon", "Other",
        )
    }
}

