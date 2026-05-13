package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.scanner.TokenNameParser
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent

class DesignTokenDashboardPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val listModel = DefaultListModel<PopupRow>()
    private var hoveredIndex: Int = -1
    private val list = object : JBList<PopupRow>(listModel) {
        override fun getScrollableTracksViewportWidth(): Boolean = true

        // Tooltips set on sub-components of a cell renderer never fire because
        // JList paints them as snapshots. Compute the tooltip directly from the
        // row under the mouse.
        override fun getToolTipText(event: java.awt.event.MouseEvent): String? {
            val idx = locationToIndex(event.point).takeIf { it >= 0 } ?: return null
            if (idx >= listModel.size()) return null
            val row = listModel.getElementAt(idx) as? TokenPopupRow ?: return null
            val token = row.token
            if (token.variants.isEmpty()) return null
            return VariantTableHtml.build(token)
        }
    }.apply {
        cellRenderer = PopupRowRenderer(showLocateProvider = { it == hoveredIndex })
        fixedCellHeight = JBUI.scale(28)
        dragEnabled = true
        transferHandler = TokenTransferHandler()
        javax.swing.ToolTipManager.sharedInstance().registerComponent(this)
    }

    private val searchField = SearchTextField()
    // Strip of source-file toggle chips (one per file containing tokens).
    // Sits below the search bar so users can quickly mute whole files.
    private val filesPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
        border = JBUI.Borders.empty(2, 0, 8, 0)
    }
    private val fileChips = mutableMapOf<String, FilterChip>()
    /** File paths the user has *de-selected* (their tokens are hidden). Default is empty = everything visible. */
    private val inactiveFiles = mutableSetOf<String>()
    /** Family names the user has *de-selected*. Empty = everything visible. */
    private val excludedFamilies = mutableSetOf<String>()
    private val collapsedCategories = mutableSetOf<String>()
    private var allTokens: List<DesignToken> = emptyList()
    private val clearFiltersAction = ClearFiltersAction()

    init {
        setupSearch()
        setupListInteractions()
        setupToolbar()
        setupContent()
        setupEditorTracking()
        scheduleRefresh()
    }

    /**
     * Refresh the list whenever the user switches to a different editor —
     * tokens shown must reflect the active file's scope, not a stale union.
     */
    private fun setupEditorTracking() {
        project.messageBus.connect(project)
            .subscribe(
                com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                    override fun selectionChanged(
                        event: com.intellij.openapi.fileEditor.FileEditorManagerEvent
                    ) {
                        scheduleRefresh()
                    }
                },
            )
    }

    // ─── Setup ────────────────────────────────────────────────────────────

    private fun setupSearch() {
        searchField.textEditor.emptyText.text = "Search tokens (name or value)…"
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildModel()
        })
    }

    private fun setupListInteractions() {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = list.locationToIndex(e.point).takeIf { it >= 0 } ?: return
                val row = listModel.get(idx) ?: return
                when (row) {
                    is SeparatorPopupRow -> {
                        if (row.collapsible) toggleCollapse(row.groupKey)
                    }
                    is TokenPopupRow -> {
                        if (isOverLocateIcon(idx, e)) {
                            revealInSource(row.token)
                        } else if (e.clickCount == 2) {
                            insertAtCaret(row.token)
                        }
                    }
                }
            }

            override fun mouseExited(e: MouseEvent) = updateHover(-1)
        })
        list.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = list.locationToIndex(e.point).takeIf { it >= 0 } ?: -1
                val rowIsToken = idx >= 0 && (listModel.get(idx) is TokenPopupRow)
                updateHover(if (rowIsToken) idx else -1)
            }
        })
        list.componentPopupMenu = buildContextMenu()
    }

    private fun updateHover(newIndex: Int) {
        if (hoveredIndex == newIndex) return
        val previous = hoveredIndex
        hoveredIndex = newIndex
        list.cursor = if (newIndex >= 0) {
            java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        } else {
            java.awt.Cursor.getDefaultCursor()
        }
        // Only repaint the affected rows to avoid flicker.
        if (previous >= 0) list.repaint(list.getCellBounds(previous, previous) ?: return)
        if (newIndex >= 0) list.repaint(list.getCellBounds(newIndex, newIndex) ?: return)
    }

    private fun isOverLocateIcon(index: Int, e: MouseEvent): Boolean {
        val cell = list.getCellBounds(index, index) ?: return false
        val iconWidth = JBUI.scale(LOCATE_ICON_HIT_AREA)
        val iconLeft = cell.x + cell.width - iconWidth
        return e.x in iconLeft..(cell.x + cell.width)
    }

    private fun toggleCollapse(groupKey: String) {
        if (!collapsedCategories.add(groupKey)) collapsedCategories.remove(groupKey)
        rebuildModel()
    }

    private fun buildContextMenu(): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem("Insert at caret").apply {
            addActionListener {
                (list.selectedValue as? TokenPopupRow)?.let { insertAtCaret(it.token) }
            }
        })
        add(JMenuItem("Open source file").apply {
            addActionListener {
                (list.selectedValue as? TokenPopupRow)?.let { revealInSource(it.token) }
            }
        })
        add(JMenuItem("Copy token name").apply {
            addActionListener {
                (list.selectedValue as? TokenPopupRow)?.let { row ->
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(row.token.name), null)
                }
            }
        })
    }

    private fun setupToolbar() {
        val refresh = object : AnAction("Refresh", "Re-scan design tokens", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                TokenIndex.getInstance(project).invalidate()
                scheduleRefresh()
            }
        }
        val expandAll = object : AnAction(
            "Expand All Sections",
            "Expand every category section",
            AllIcons.Actions.Expandall,
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                collapsedCategories.clear()
                rebuildModel()
            }
        }
        val collapseAll = object : AnAction(
            "Collapse All Sections",
            "Collapse every category section",
            AllIcons.Actions.Collapseall,
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                allTokens.map { it.category.name }.toSet().forEach(collapsedCategories::add)
                rebuildModel()
            }
        }
        val group = DefaultActionGroup(refresh, expandAll, collapseAll, clearFiltersAction)
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("DesignTokenDashboard", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
    }

    private fun setupContent() {
        val filesScroll = JBScrollPane(filesPanel).apply {
            border = null
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER
            preferredSize = Dimension(JBUI.scale(100), JBUI.scale(52))
        }
        // The search bar is paired with a small filter button to its right; the
        // button opens the family-filter popup so that strip below stays
        // dedicated to file-source chips.
        val filterButton = RoundIconButton(AllIcons.General.Filter, "Filter token families…") {
            showFamilyFilterPopup(it)
        }
        val searchRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(filterButton, BorderLayout.EAST)
        }
        val north = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 4, 0, 4)
            add(searchRow, BorderLayout.NORTH)
            add(filesScroll, BorderLayout.SOUTH)
        }
        val main = JPanel(BorderLayout()).apply {
            add(north, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        setContent(main)
    }

    // ─── Data ─────────────────────────────────────────────────────────────

    private fun scheduleRefresh() {
        // Resolve the currently focused editor's file *before* dispatching to
        // the background, so the Library view shows tokens scoped to that file
        // rather than the union of every scope. Falls back to all tokens when
        // no editor is selected (e.g. on plugin startup before any file is open).
        val activeFile = FileEditorManager.getInstance(project).selectedEditor?.file
        object : Task.Backgroundable(project, "Loading design tokens", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val tokens = runReadAction { TokenIndex.getInstance(project).get(activeFile) }
                ApplicationManager.getApplication().invokeLater {
                    allTokens = tokens
                    rebuildFileChips()
                    rebuildModel()
                }
            }
        }.queue()
    }

    private fun rebuildFileChips() {
        val detected = allTokens.map { it.filePath }.toSortedSet()
        val obsolete = fileChips.keys - detected
        obsolete.forEach { path ->
            fileChips.remove(path)?.also(filesPanel::remove)
            inactiveFiles.remove(path)
        }
        // Sort by display name, not full path, so the strip reads naturally.
        val sorted = detected.sortedBy { it.substringAfterLast('/').lowercase() }
        for (path in sorted) {
            if (fileChips.containsKey(path)) continue
            val basename = path.substringAfterLast('/')
            val chip = FilterChip(basename).apply {
                isSelected = path !in inactiveFiles
                toolTipText = path
                addActionListener {
                    if (isSelected) inactiveFiles.remove(path) else inactiveFiles.add(path)
                    rebuildModel()
                }
            }
            fileChips[path] = chip
            filesPanel.add(chip)
        }
        // Re-order: dropped chips may have left holes in the panel. Strip and re-add.
        filesPanel.removeAll()
        sorted.forEach { fileChips[it]?.let(filesPanel::add) }
        filesPanel.revalidate()
        filesPanel.repaint()
    }

    private fun rebuildModel() {
        val needle = searchField.text.trim()
        val filtered = allTokens.asSequence()
            .filter { it.filePath !in inactiveFiles }
            .filter { familyOf(it) !in excludedFamilies }
            .filter {
                needle.isEmpty() ||
                    it.name.contains(needle, true) ||
                    it.resolvedValue.contains(needle, true)
            }
            .toList()
        listModel.clear()
        RowGrouping.byCategory(filtered, collapsedCategories).forEach(listModel::addElement)
    }

    /**
     * Maps a fine-grained [TokenCategory] into one of three high-level buckets
     * — Colors / Metrics / Effects — used as group headers in the family filter
     * popup. Categories that don't fit (`OTHER`) fall under a generic "Other"
     * group at the bottom.
     */
    private fun groupOf(category: fr.fsh.tokendesigner.model.TokenCategory): String =
        when (category) {
            fr.fsh.tokendesigner.model.TokenCategory.COLOR -> "Colors"
            fr.fsh.tokendesigner.model.TokenCategory.SHADOW -> "Effects"
            fr.fsh.tokendesigner.model.TokenCategory.SPACING,
            fr.fsh.tokendesigner.model.TokenCategory.RADIUS,
            fr.fsh.tokendesigner.model.TokenCategory.TYPOGRAPHY,
            fr.fsh.tokendesigner.model.TokenCategory.DURATION,
            fr.fsh.tokendesigner.model.TokenCategory.Z_INDEX -> "Metrics"
            fr.fsh.tokendesigner.model.TokenCategory.OTHER -> "Other"
        }

    private val groupOrder = listOf("Colors", "Metrics", "Effects", "Other")

    private fun showFamilyFilterPopup(invoker: javax.swing.JComponent) {
        val byGroup = LinkedHashMap<String, MutableMap<String, Int>>()
        for (token in allTokens) {
            val grp = groupOf(token.category)
            val fam = familyOf(token)
            byGroup.getOrPut(grp) { LinkedHashMap() }
                .merge(fam, 1, Int::plus)
        }
        val content = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12)
        }
        val checkboxes = mutableListOf<Pair<String, javax.swing.JCheckBox>>()
        for (group in groupOrder) {
            val families = byGroup[group] ?: continue
            content.add(javax.swing.JLabel("<html><b>$group</b></html>").apply {
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                foreground = com.intellij.ui.JBColor.GRAY
                border = JBUI.Borders.empty(8, 0, 4, 0)
            })
            for ((family, count) in families.toSortedMap()) {
                val cb = javax.swing.JCheckBox("$family  ($count)").apply {
                    isSelected = family !in excludedFamilies
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    addActionListener {
                        if (isSelected) excludedFamilies.remove(family)
                        else excludedFamilies.add(family)
                        rebuildModel()
                    }
                }
                checkboxes += family to cb
                content.add(cb)
            }
        }
        if (checkboxes.isEmpty()) {
            content.add(javax.swing.JLabel("No families to filter."))
        }
        val resetButton = javax.swing.JButton("Reset").apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            addActionListener {
                excludedFamilies.clear()
                checkboxes.forEach { (_, cb) -> cb.isSelected = true }
                rebuildModel()
            }
        }
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
        content.add(javax.swing.JSeparator().apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        content.add(resetButton)

        val scroll = JBScrollPane(content).apply {
            border = null
            preferredSize = Dimension(JBUI.scale(280), JBUI.scale(360))
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, scroll)
            .setRequestFocus(true)
            .setMovable(true)
            .setResizable(true)
            .setTitle("Family filters")
            .createPopup()
            .show(com.intellij.ui.awt.RelativePoint(invoker, java.awt.Point(0, invoker.height)))
    }

    private fun familyOf(token: DesignToken): String {
        val struct = TokenNameParser.parse(token.name)
        val segs = struct.segments
        if (segs.isEmpty()) return "other"
        val head = segs[0].lowercase()
        return if (head == "token" && segs.size >= 2) segs[1].lowercase() else head
    }

    // ─── Actions on a token ───────────────────────────────────────────────

    private fun insertAtCaret(token: DesignToken) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val toInsert = textForInsertion(token)
        WriteCommandAction.runWriteCommandAction(project, "Insert Design Token", null, {
            val offset = editor.caretModel.offset
            editor.document.insertString(offset, toInsert)
            editor.caretModel.moveToOffset(offset + toInsert.length)
        })
    }

    private fun revealInSource(token: DesignToken) {
        val vf = LocalFileSystem.getInstance().findFileByPath(token.filePath) ?: return
        OpenFileDescriptor(project, vf, token.offset).navigate(true)
    }

    private fun textForInsertion(token: DesignToken): String =
        fr.fsh.tokendesigner.model.TokenReference.expression(token)

    // ─── DnD ──────────────────────────────────────────────────────────────

    private inner class TokenTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent): Int = COPY

        override fun createTransferable(c: JComponent): Transferable? {
            val selected = list.selectedValue as? TokenPopupRow ?: return null
            return StringSelection(textForInsertion(selected.token))
        }
    }

    // ─── Toolbar action: Clear Filters ────────────────────────────────────

    private inner class ClearFiltersAction : AnAction(
        "Clear Filters",
        "Re-enable every file & family, clear search text",
        AllIcons.Actions.Cancel,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = inactiveFiles.isNotEmpty() ||
                excludedFamilies.isNotEmpty() ||
                searchField.text.isNotBlank()
        }

        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            inactiveFiles.clear()
            excludedFamilies.clear()
            fileChips.values.forEach { it.isSelected = true }
            searchField.text = ""
            rebuildModel()
        }
    }
}
