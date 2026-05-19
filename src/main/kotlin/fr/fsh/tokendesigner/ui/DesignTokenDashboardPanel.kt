package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import fr.fsh.tokendesigner.util.readAction
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
import fr.fsh.tokendesigner.settings.TokenSelectorSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Box
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
        // Variable row height: tokens at 28 px, but category / family /
        // sub-family separators have their own shorter preferredSize so they
        // read as section breaks rather than full rows.
        fixedCellHeight = -1
        dragEnabled = true
        transferHandler = TokenTransferHandler()
        javax.swing.ToolTipManager.sharedInstance().registerComponent(this)
    }

    private val stickyHeaderRenderer = PopupRowRenderer.SeparatorRowComponent()
    private val stickyHeaderPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border())
        add(stickyHeaderRenderer, BorderLayout.CENTER)
        // Background follows `list.background` (set in rebuildModel) so the
        // sticky header blends with the rows below it across every theme;
        // the default JPanel bg can diverge from List.background on some
        // light themes and produce a dark band at the top of the panel.
        isOpaque = true
        isVisible = false
    }
    /** groupKey of the category currently rendered in the sticky header, if any. */
    private var stickyCategoryGroupKey: String? = null

    /**
     * Walks back from the top-most visible row to the nearest level-0 category
     * separator and mirrors it in the column-header view. Hidden when no
     * category sits above the viewport (e.g. truly empty list, or when the
     * category's own header row is already pinned at y == 0).
     */
    private fun updateStickyHeader(viewport: javax.swing.JViewport) {
        if (listModel.isEmpty) {
            stickyHeaderPanel.isVisible = false
            stickyCategoryGroupKey = null
            return
        }
        val viewY = viewport.viewPosition.y
        if (viewY <= 0) {
            stickyHeaderPanel.isVisible = false
            stickyCategoryGroupKey = null
            return
        }
        val firstIdx = list.locationToIndex(java.awt.Point(0, viewY)).coerceAtLeast(0)
        var found: SeparatorPopupRow? = null
        for (i in firstIdx downTo 0) {
            val row = listModel.get(i)
            if (row is SeparatorPopupRow && row.level == 0) {
                found = row
                break
            }
        }
        if (found == null) {
            stickyHeaderPanel.isVisible = false
            stickyCategoryGroupKey = null
            return
        }
        stickyHeaderRenderer.configure(found, list.background, true)
        stickyCategoryGroupKey = found.groupKey
        stickyHeaderPanel.isVisible = true
        stickyHeaderPanel.revalidate()
        stickyHeaderPanel.repaint()
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
    /** Token-kind groups the user has *de-selected*. Empty = every kind visible. */
    private val excludedKindGroups = mutableSetOf<TokenKindGroup>()
    private val collapsedCategories = mutableSetOf<String>()
    private var allTokens: List<DesignToken> = emptyList()
    private var filterButtonRef: RoundIconButton? = null
    private val clearFiltersAction = ClearFiltersAction()
    
    private val scopeLabel = com.intellij.ui.components.JBLabel("Scope: All project").apply {
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        font = com.intellij.util.ui.JBFont.small()
    }

    private val gridContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty()
        // Force the grid panel's background to track the JBList **at paint
        // time** — separator headers reuse `list.background`, and the JPanel
        // default LAF bg can diverge from List.background on light themes
        // (producing dark bands behind family / sub-family headings). A
        // snapshot `bg = list.background` here would freeze whichever value
        // is current at construction; the dynamic JBColor below re-reads
        // list.background on every repaint and follows live theme switches.
        isOpaque = true
    }
    private val mainContentCards = CardLayout()
    private val mainContentPanel = JPanel(mainContentCards)

    init {
        // Tie every Library surface to **Panel.background** rather than the
        // LAF's `List.background`. Some themes (and the user's case in
        // particular) intentionally diverge — panel = cream / light, list =
        // editor-dark — so following `list.background` reproduced the dark
        // band behind separator headers even after we made the lookup
        // dynamic. Anchoring on Panel.background plus an explicit override of
        // the JBList itself makes the whole panel honour the IDE theme as a
        // single visual unit, irrespective of the editor colour scheme.
        // `JBColor.lazy(Supplier<Color>)` is the non-deprecated factory
        // replacement for the `JBColor(NotNullProducer)` ctor flagged by the
        // plugin verifier.
        val panelBg = com.intellij.ui.JBColor.lazy {
            com.intellij.util.ui.UIUtil.getPanelBackground()
        }
        val panelFg = com.intellij.ui.JBColor.lazy {
            com.intellij.util.ui.UIUtil.getLabelForeground()
        }
        list.background = panelBg
        list.foreground = panelFg
        gridContainer.background = panelBg
        stickyHeaderPanel.background = panelBg

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
            override fun mouseReleased(e: MouseEvent) {
                handlePopup(e)
            }

            override fun mousePressed(e: MouseEvent) {
                handlePopup(e)
            }

            private fun handlePopup(e: MouseEvent) {
                if (e.isPopupTrigger || javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    val idx = list.locationToIndex(e.point)
                    if (idx >= 0) list.selectedIndex = idx
                    
                    if (e.isPopupTrigger) {
                        buildContextMenu().show(list, e.x, e.y)
                    }
                }
            }

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

    private fun showTokenContextMenu(token: DesignToken, component: java.awt.Component, x: Int, y: Int) {
        val menu = JPopupMenu().apply {
            add(JMenuItem("Insert at caret").apply {
                addActionListener { insertAtCaret(token) }
            })
            add(JMenuItem("Open source file").apply {
                addActionListener { revealInSource(token) }
            })
            add(JMenuItem("Copy token").apply {
                addActionListener {
                    com.intellij.openapi.ide.CopyPasteManager.getInstance()
                        .setContents(fr.fsh.tokendesigner.actions.TokenDragTransferable.forToken(token))
                }
            })
        }
        menu.show(component, x, y)
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
        add(JMenuItem("Copy token").apply {
            addActionListener {
                (list.selectedValue as? TokenPopupRow)?.let { row ->
                    com.intellij.openapi.ide.CopyPasteManager.getInstance()
                        .setContents(fr.fsh.tokendesigner.actions.TokenDragTransferable.forToken(row.token))
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
        
        val topToolbarRow = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            val scopeBox = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(scopeLabel)
                add(ScopeUIUtils.createScopeHelpButton(project))
            }
            // Center the scope chip vertically against the action toolbar
            // (toolbar buttons are taller than a plain JLabel, so without this
            // wrapper the text sits top-aligned).
            val scopeCenter = JPanel(java.awt.GridBagLayout()).apply {
                isOpaque = false
                add(scopeBox)
            }
            add(scopeCenter, BorderLayout.EAST)
        }
        setToolbar(topToolbarRow)
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
        val filterButton = RoundIconButton(AllIcons.General.Filter, "Filter by token kind & family…") {
            showFamilyFilterPopup(it)
        }
        // Active background highlights when any kind/family is excluded — gives a
        // visual cue that filters are narrowing the list without rummaging
        // through the popup.
        filterButton.isActive = excludedFamilies.isNotEmpty() || excludedKindGroups.isNotEmpty()
        this.filterButtonRef = filterButton

        // `ModuleGroup` is a folder-with-stacked-items glyph — the closest
        // built-in icon to the "group buckets of related things" idea this
        // toggle expresses. Use the platform's disabled variant for OFF so the
        // visual delta between states is unmistakable.
        val subfamilyIconOn = AllIcons.Nodes.ModuleGroup
        val subfamilyIconOff = IconLoader.getDisabledIcon(subfamilyIconOn)
        val subfamilyBtn = RoundIconButton(
            subfamilyIconOn,
            "Group by sub-family (auto-detected from token names)",
        ) {}
        fun updateSubfamilyBtn() {
            val on = TokenSelectorSettings.getInstance(project).librarySubfamilyGrouping
            subfamilyBtn.isActive = on
            subfamilyBtn.currentIcon = if (on) subfamilyIconOn else subfamilyIconOff
            subfamilyBtn.toolTipText = if (on) {
                "Sub-family grouping: ON — click to flatten categories."
            } else {
                "Sub-family grouping: OFF — click to group similar tokens inside each category."
            }
            subfamilyBtn.repaint()
        }
        subfamilyBtn.addActionListener {
            val s = TokenSelectorSettings.getInstance(project)
            s.librarySubfamilyGrouping = !s.librarySubfamilyGrouping
            updateSubfamilyBtn()
            rebuildModel()
        }
        updateSubfamilyBtn()

        val viewModeBtn = RoundIconButton(AllIcons.Actions.ListFiles, "View Mode") {}
        fun updateViewModeBtn() {
            val mode = TokenSelectorSettings.getInstance(project).dashboardViewMode
            if (mode == "LIST") {
                viewModeBtn.currentIcon = AllIcons.Actions.ListFiles
                viewModeBtn.toolTipText = "Current: List View (Click to switch to Grid)"
            } else {
                viewModeBtn.currentIcon = GridIcon(16)
                viewModeBtn.toolTipText = "Current: Grid View (Click to switch to List)"
            }
            viewModeBtn.repaint()
        }

        viewModeBtn.addActionListener {
            val mode = TokenSelectorSettings.getInstance(project).dashboardViewMode
            setViewMode(if (mode == "LIST") "GRID" else "LIST")
            updateViewModeBtn()
        }
        updateViewModeBtn()

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(subfamilyBtn)
            add(viewModeBtn)
            add(filterButton)
        }
        // FlowLayout sits the buttons at the top of the row; the search field
        // is ~4 px taller so they read as misaligned. Wrapping the actions in
        // a GridBagLayout (which centers a single child both axes) pulls them
        // to the search field's vertical midline.
        val actionsCenter = JPanel(java.awt.GridBagLayout()).apply {
            isOpaque = false
            add(actionsPanel)
        }
        val searchRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(2, 0, 0, 0)
            add(searchField, BorderLayout.CENTER)
            add(actionsCenter, BorderLayout.EAST)
        }
        val north = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 4, 0, 4)
            add(searchRow, BorderLayout.CENTER)
            add(filesScroll, BorderLayout.SOUTH)
        }
        
        val scrollList = JBScrollPane(list).apply {
            border = null
            setColumnHeaderView(stickyHeaderPanel)
        }
        // Repaint the floating category header whenever the viewport moves or
        // the model rebuilds. Click on it = toggle that category's collapse —
        // mirrors the in-list chevron behaviour so the sticky header is fully
        // interactive, not decorative.
        scrollList.viewport.addChangeListener { updateStickyHeader(scrollList.viewport) }
        stickyHeaderPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        stickyHeaderPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                stickyCategoryGroupKey?.let { toggleCollapse(it) }
            }
        })
        val scrollGrid = JBScrollPane(gridContainer).apply {
            border = null
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            // Viewport mirrors the JBList dynamically (see the JBColor lazy
            // bound in `init`) so the empty strip beside the grid follows
            // theme switches instead of freezing on the value live at
            // construction time.
            viewport.isOpaque = true
            // Same anchor as the JBList — Panel.background, not List —
            // through the non-deprecated `JBColor.lazy` factory.
            viewport.background = com.intellij.ui.JBColor.lazy {
                com.intellij.util.ui.UIUtil.getPanelBackground()
            }
        }
        mainContentPanel.add(scrollList, "LIST")
        mainContentPanel.add(scrollGrid, "GRID")

        val main = JPanel(BorderLayout()).apply {
            add(north, BorderLayout.NORTH)
            add(mainContentPanel, BorderLayout.CENTER)
        }
        
        val initialMode = TokenSelectorSettings.getInstance(project).dashboardViewMode
        mainContentCards.show(mainContentPanel, initialMode)
        
        setContent(main)
    }

    private fun setViewMode(mode: String) {
        TokenSelectorSettings.getInstance(project).dashboardViewMode = mode
        mainContentCards.show(mainContentPanel, mode)
    }

    // ─── Data ─────────────────────────────────────────────────────────────

    private fun scheduleRefresh() {
        // Resolve the currently focused editor's file *before* dispatching to
        // the background, so the Library view shows tokens scoped to that file
        // rather than the union of every scope. Falls back to all tokens when
        // no editor is selected (e.g. on plugin startup before any file is open).
        val activeFile = FileEditorManager.getInstance(project).selectedEditor?.file
        
        val configuredScopes = TokenSelectorSettings.getInstance(project).scopes
        if (configuredScopes.isEmpty()) {
            scopeLabel.text = "Scope: All project"
            scopeLabel.toolTipText = "No scopes configured"
        } else {
            val active = fr.fsh.tokendesigner.settings.ScopeResolver.activeScopesFor(project, activeFile)
            if (active.isEmpty()) {
                scopeLabel.text = "Scope: None"
                scopeLabel.toolTipText = "No tokens available for this file"
            } else {
                val names = active.map { if (it.isCommon) "Common" else it.name.ifBlank { "Unnamed" } }
                scopeLabel.text = "Scope: ${names.joinToString(", ")}"
                scopeLabel.toolTipText = "Tokens filtered for the current file"
            }
        }
        
        object : Task.Backgroundable(project, "Loading design tokens", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val tokens = readAction { TokenIndex.getInstance(project).get(activeFile) }
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
                    val kind = kindOfFile(path)
                    if (isSelected) {
                        inactiveFiles.remove(path)
                        // Re-checking a file implicitly re-enables its kind so
                        // a user doesn't have to chase ghost state in the popup.
                        if (kind != null) excludedKindGroups.remove(kind)
                    } else {
                        inactiveFiles.add(path)
                        // Last file of its kind muted ⇒ uncheck the kind too
                        // so the popup mirrors the strip without manual work.
                        if (kind != null && filesOfKind(kind).all { it in inactiveFiles }) {
                            excludedKindGroups.add(kind)
                        }
                    }
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
        // Split needle on whitespace/dash/underscore so "informative content" matches
        // `--token-informative-highlight-content-hover`. All terms must hit, against
        // either the name or the resolved value. Empty needle = match-all.
        val terms = needle
            .split(Regex("[\\s\\-_]+"))
            .filter { it.isNotEmpty() }
        val filtered = allTokens.asSequence()
            .filter { it.filePath !in inactiveFiles }
            .filter { familyOf(it) !in excludedFamilies }
            .filter { it.kindGroup() !in excludedKindGroups }
            .filter { token ->
                terms.isEmpty() || terms.all { term ->
                    token.name.contains(term, true) ||
                        token.resolvedValue.contains(term, true)
                }
            }
            .toList()
        filterButtonRef?.isActive = excludedFamilies.isNotEmpty() || excludedKindGroups.isNotEmpty()
        listModel.clear()
        gridContainer.removeAll()
        
        val subfamilyOn = TokenSelectorSettings.getInstance(project).librarySubfamilyGrouping
        val grouped = RowGrouping.byCategory(filtered, collapsedCategories, subfamilyOn)
        grouped.forEach(listModel::addElement)

        var currentWrap: JPanel? = null
        var categoryCollapsed = false
        for (row in grouped) {
            when (row) {
                is SeparatorPopupRow -> {
                    val header = PopupRowRenderer().getListCellRendererComponent(list, row, 0, false, false) as JComponent
                    header.maximumSize = Dimension(Int.MAX_VALUE, header.preferredSize.height)
                    header.alignmentX = java.awt.Component.LEFT_ALIGNMENT

                    if (row.level == 0) {
                        // Category header: chevron toggles collapse, new grid wrap starts below.
                        categoryCollapsed = row.collapsed
                        if (row.collapsible) {
                            header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            header.addMouseListener(object : MouseAdapter() {
                                override fun mouseClicked(e: MouseEvent) {
                                    toggleCollapse(row.groupKey)
                                }
                            })
                        }
                        gridContainer.add(header)
                        currentWrap = if (!row.collapsed) {
                            JPanel(CssGridLayout(JBUI.scale(180), JBUI.scale(90), JBUI.scale(12), JBUI.scale(12))).apply {
                                border = JBUI.Borders.empty(4, 8, 12, 8)
                                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                            }.also { gridContainer.add(it) }
                        } else {
                            null
                        }
                    } else if (!categoryCollapsed) {
                        // Family (level 1) gets extra top spacing so successive
                        // families read as distinct blocks; sub-family (level 2)
                        // sits tight under its family. Card wraps indent left
                        // to match the header label's left padding, so cards
                        // line up under their heading instead of the chevron.
                        val indentStep = JBUI.scale(PopupRowRenderer.INDENT_PX)
                        val isFamily = row.level == 1
                        if (isFamily) {
                            gridContainer.add(Box.createVerticalStrut(JBUI.scale(8)))
                        }
                        gridContainer.add(header)
                        val leftPad = JBUI.scale(8) + indentStep * row.level
                        currentWrap = JPanel(CssGridLayout(JBUI.scale(180), JBUI.scale(90), JBUI.scale(12), JBUI.scale(12))).apply {
                            border = JBUI.Borders.empty(if (isFamily) 2 else 0, leftPad, if (isFamily) 10 else 6, JBUI.scale(8))
                            alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        }.also { gridContainer.add(it) }
                    }
                }
                is TokenPopupRow -> {
                    currentWrap?.add(TokenCardPanel(
                        row.token,
                        onDoubleClick = { insertAtCaret(it) },
                        onRightClick = { token, e -> showTokenContextMenu(token, e.component, e.x, e.y) }
                    ))
                }
            }
        }
        gridContainer.revalidate()
        gridContainer.repaint()
        // Sticky header tracks scroll position; rebuilding the model resets row
        // indices so refresh it now to avoid pinning a stale category.
        (list.parent as? javax.swing.JViewport)?.let { updateStickyHeader(it) }
    }

    /**
     * Maps a [TokenCategory] to a readable group header in the family filter popup.
     */
    private fun groupOf(category: fr.fsh.tokendesigner.model.TokenCategory): String =
        category.name.lowercase().replaceFirstChar { it.titlecase() }

    private val groupOrder = fr.fsh.tokendesigner.model.TokenCategory.values().map { 
        it.name.lowercase().replaceFirstChar { it.titlecase() }
    }

    private fun showFamilyFilterPopup(invoker: javax.swing.JComponent) {
        val byGroup = LinkedHashMap<String, MutableMap<String, Int>>()
        val kindCounts = LinkedHashMap<TokenKindGroup, Int>().also {
            TokenKindGroup.entries.forEach { k -> it[k] = 0 }
        }
        for (token in allTokens) {
            val grp = groupOf(token.category)
            val fam = familyOf(token)
            byGroup.getOrPut(grp) { LinkedHashMap() }
                .merge(fam, 1, Int::plus)
            kindCounts[token.kindGroup()] = (kindCounts[token.kindGroup()] ?: 0) + 1
        }
        val content = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12)
        }
        val kindCheckboxes = mutableListOf<Pair<TokenKindGroup, javax.swing.JCheckBox>>()
        content.add(javax.swing.JLabel("<html><b>Token kind</b></html>").apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(0, 0, 4, 0)
        })
        for ((kind, count) in kindCounts) {
            if (count == 0) continue
            val cb = javax.swing.JCheckBox("${kind.label}  ($count)").apply {
                isSelected = kind !in excludedKindGroups
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                addActionListener {
                    val files = filesOfKind(kind)
                    if (isSelected) {
                        excludedKindGroups.remove(kind)
                        inactiveFiles.removeAll(files.toSet())
                    } else {
                        excludedKindGroups.add(kind)
                        inactiveFiles.addAll(files)
                    }
                    // Reflect the new state on the file-chip strip below the
                    // search bar so the two filter surfaces stay in sync.
                    files.forEach { path ->
                        fileChips[path]?.isSelected = path !in inactiveFiles
                    }
                    rebuildModel()
                }
            }
            kindCheckboxes += kind to cb
            content.add(cb)
        }
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        content.add(javax.swing.JSeparator().apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
        content.add(javax.swing.JLabel("<html><b>Families</b></html>").apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(0, 0, 2, 0)
        })
        val checkboxes = mutableListOf<Pair<String, javax.swing.JCheckBox>>()
        for (group in groupOrder) {
            val families = byGroup[group] ?: continue
            content.add(javax.swing.JLabel("<html><i>$group</i></html>").apply {
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(6, 0, 2, 0)
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
                excludedKindGroups.clear()
                checkboxes.forEach { (_, cb) -> cb.isSelected = true }
                kindCheckboxes.forEach { (_, cb) -> cb.isSelected = true }
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
            .setTitle("Library filters")
            .createPopup()
            .show(com.intellij.ui.awt.RelativePoint(invoker, java.awt.Point(0, invoker.height)))
    }

    /**
     * Maps a project file path to the [TokenKindGroup] of the tokens it
     * contributes. In practice one file = one kind (SCSS → SCSS, .ts → JS),
     * so the first matching token is enough.
     */
    private fun kindOfFile(path: String): TokenKindGroup? =
        allTokens.firstOrNull { it.filePath == path }?.kindGroup()

    /** All distinct file paths producing tokens of the given [kind]. */
    private fun filesOfKind(kind: TokenKindGroup): List<String> =
        allTokens.asSequence()
            .filter { it.kindGroup() == kind }
            .map { it.filePath }
            .distinct()
            .toList()

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
        val ext = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(editor.document)?.extension
        WriteCommandAction.runWriteCommandAction(project, "Insert Design Token", null, {
            val offset = editor.caretModel.offset
            val toInsert = fr.fsh.tokendesigner.util.TokenInsertion.expressionFor(
                token, ext, editor.document.charsSequence, offset,
            )
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
            return fr.fsh.tokendesigner.actions.TokenDragTransferable.forToken(selected.token)
        }
    }

    // ─── Toolbar action: Clear Filters ────────────────────────────────────

    private inner class ClearFiltersAction : AnAction(
        "Clear Filters",
        "Re-enable every file, family & token kind, clear search text",
        AllIcons.Actions.Cancel,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = inactiveFiles.isNotEmpty() ||
                excludedFamilies.isNotEmpty() ||
                excludedKindGroups.isNotEmpty() ||
                searchField.text.isNotBlank()
        }

        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            inactiveFiles.clear()
            excludedFamilies.clear()
            excludedKindGroups.clear()
            fileChips.values.forEach { it.isSelected = true }
            searchField.text = ""
            rebuildModel()
        }
    }

    override fun uiDataSnapshot(sink: com.intellij.openapi.actionSystem.DataSink) {
        super.uiDataSnapshot(sink)
        sink[com.intellij.openapi.actionSystem.PlatformDataKeys.COPY_PROVIDER] = object : com.intellij.ide.CopyProvider {
            override fun performCopy(dataContext: com.intellij.openapi.actionSystem.DataContext) {
                val selectedToken = (list.selectedValue as? TokenPopupRow)?.token
                if (selectedToken != null) {
                    com.intellij.openapi.ide.CopyPasteManager.getInstance()
                        .setContents(fr.fsh.tokendesigner.actions.TokenDragTransferable.forToken(selectedToken))
                }
            }
            override fun isCopyEnabled(dataContext: com.intellij.openapi.actionSystem.DataContext): Boolean = list.selectedValue is TokenPopupRow
            override fun isCopyVisible(dataContext: com.intellij.openapi.actionSystem.DataContext): Boolean = true
            override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread =
                com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        }
    }
}

/**
 * Coarse-grained grouping of [TokenKind] for the library kind filter. CSS and
 * SCSS keep their own bucket because the user often deals with them
 * separately (e.g. `--token` SCSS file vs `$variable` SCSS partial), while
 * the three JS/TS flavours are merged since the user-facing distinction is
 * "this comes from a JSON / TS preset", not the underlying access pattern.
 */
internal enum class TokenKindGroup(val label: String) {
    CSS("CSS"),
    SCSS("SCSS"),
    JS("JS / JSON"),
}

internal fun fr.fsh.tokendesigner.model.DesignToken.kindGroup(): TokenKindGroup = when (kind) {
    fr.fsh.tokendesigner.model.TokenKind.CSS_CUSTOM_PROPERTY -> TokenKindGroup.CSS
    fr.fsh.tokendesigner.model.TokenKind.SCSS_VARIABLE -> TokenKindGroup.SCSS
    fr.fsh.tokendesigner.model.TokenKind.JS_OBJECT_PATH,
    fr.fsh.tokendesigner.model.TokenKind.JS_RUNTIME_PROPERTY,
    fr.fsh.tokendesigner.model.TokenKind.JS_RUNTIME_FUNCTION -> TokenKindGroup.JS
}
