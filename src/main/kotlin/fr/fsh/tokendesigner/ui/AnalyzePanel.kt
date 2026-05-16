package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import fr.fsh.tokendesigner.analyze.AnalysisReport
import fr.fsh.tokendesigner.analyze.DesignSystemAnalyzer
import fr.fsh.tokendesigner.analyze.DuplicateCluster
import fr.fsh.tokendesigner.analyze.HardcodedCluster
import fr.fsh.tokendesigner.analyze.HardcodedOccurrence
import fr.fsh.tokendesigner.analyze.Incoherence
import fr.fsh.tokendesigner.analyze.SubScore
import fr.fsh.tokendesigner.analyze.TokenSourceUsage
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.settings.Scope
import fr.fsh.tokendesigner.settings.ScopeResolver
import fr.fsh.tokendesigner.settings.TokenSelectorSettings
import fr.fsh.tokendesigner.ui.charts.MiniBar
import fr.fsh.tokendesigner.ui.charts.ScoreGauge
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * "Analyser" tab in the dashboard tool window. Renders a [AnalysisReport] as a
 * scrollable dossier with collapsible sections.
 */
class AnalyzePanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    /**
     * Content panel implementing [javax.swing.Scrollable] so its preferred
     * width tracks the viewport — that kills the horizontal scrollbar and
     * makes inner rows reflow naturally instead of pushing the layout wider.
     */
    private val content = object : JPanel(), javax.swing.Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(
            visibleRect: java.awt.Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(
            visibleRect: java.awt.Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = visibleRect.height
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean {
            val vp = parent as? javax.swing.JViewport ?: return false
            return vp.height > preferredSize.height
        }
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12, 16)
    }
    private var lastReport: AnalysisReport? = null

    /**
     * Choice of scope analysed. Sentinel `null` means "all configured scopes /
     * whole project" (we pass `null` to `TokenIndex.get`). Otherwise we pass a
     * representative `VirtualFile` inside the scope's `rootPath` so the
     * existing `ScopeResolver` resolves to that scope.
     */
    private data class ScopeChoice(val label: String, val representative: VirtualFile?)

    private val scopeCombo = JComboBox<ScopeChoice>().apply {
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is ScopeChoice && c is JLabel) c.text = value.label
                return c
            }
        }
        addActionListener { /* selection persisted at run-time */ }
    }

    /**
     * Subscribed to [TokenSelectorSettings.fireScopesChanged] so the combo
     * mirrors the configured scopes the moment the user clicks **Apply** in
     * settings — no need to switch tabs, no need to restart the IDE.
     */
    private val scopesListener: () -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            rebuildScopeCombo()
            renderEmpty(
                "Scopes changed — click <b>Run analysis</b> to recompute the report " +
                    "for the selected scope."
            )
            lastReport = null
        }
    }

    init {
        rebuildScopeCombo()
        setupToolbar()
        renderEmpty("Click <b>Run analysis</b> to compute the design-system report.")
        setContent(JBScrollPane(content).apply {
            border = null
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        })
        TokenSelectorSettings.getInstance(project).addScopesChangeListener(scopesListener)
        setupEditorTracking()
    }
    
    private fun setupEditorTracking() {
        project.messageBus.connect(project).subscribe(
            com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                    rebuildScopeCombo()
                }
            }
        )
    }

    private fun rebuildScopeCombo() {
        scopeCombo.removeAllItems()
        scopeCombo.addItem(ScopeChoice("All project", null))
        val activeFile = FileEditorManager.getInstance(project).selectedEditor?.file
        if (activeFile != null) {
            scopeCombo.addItem(ScopeChoice("Active editor (${activeFile.name})", activeFile))
        }
        for (scope in TokenSelectorSettings.getInstance(project).scopes) {
            val rep = representativeFileFor(scope) ?: continue
            scopeCombo.addItem(ScopeChoice("Scope: ${scope.name.ifBlank { "(unnamed)" }}", rep))
        }
        
        if (activeFile != null) {
            val activeScopes = ScopeResolver.activeScopesFor(project, activeFile)
            val deepest = activeScopes.lastOrNull { !it.isCommon }
            if (deepest != null) {
                val targetName = "Scope: ${deepest.name.ifBlank { "(unnamed)" }}"
                var found = false
                for (i in 0 until scopeCombo.itemCount) {
                    if (scopeCombo.getItemAt(i).label == targetName) {
                        scopeCombo.selectedIndex = i
                        found = true
                        break
                    }
                }
                if (!found && scopeCombo.itemCount > 1) scopeCombo.selectedIndex = 1
            } else {
                scopeCombo.selectedIndex = 0
            }
        }
    }

    private fun representativeFileFor(scope: Scope): VirtualFile? {
        if (scope.isCommon) return null
        val abs = ScopeResolver.absolutize(project, scope.rootPath) ?: return null
        return LocalFileSystem.getInstance().findFileByPath(abs)
    }

    private fun setupToolbar() {
        val run = object : AnAction("Run analysis", "Compute the design-system report", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) = runAnalysis()
        }
        val refresh = object : AnAction("Refresh", "Re-run the analysis", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                rebuildScopeCombo()
                runAnalysis()
            }
        }
        // Hard re-sync: drop every cached token list and rebuild the combo
        // from scratch. Useful when the user just edited scopes in settings,
        // or when the VFS listener somehow missed a token-file change. The
        // automatic listener above covers the common case; this action is the
        // explicit fallback the user can trigger when the panel "looks stuck".
        val resync = object : AnAction(
            "Re-sync scopes",
            "Drop token caches and re-read scope settings",
            AllIcons.Actions.ForceRefresh,
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                TokenIndex.getInstance(project).invalidate()
                rebuildScopeCombo()
                lastReport = null
                renderEmpty("Caches cleared — click <b>Run analysis</b> to recompute.")
            }
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("DesignTokenAnalyze", DefaultActionGroup(run, refresh, resync), true)
        toolbar.targetComponent = this

        val toolbarRow = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            val rightSide = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                add(JBLabel("Scope:").apply { foreground = JBColor.GRAY })
                add(scopeCombo)
                add(ScopeUIUtils.createScopeHelpButton(project))
            }
            add(rightSide, BorderLayout.EAST)
        }
        setToolbar(toolbarRow)
    }

    private fun selectedScopeFile(): VirtualFile? =
        (scopeCombo.selectedItem as? ScopeChoice)?.representative

    private fun runAnalysis() {
        renderEmpty("Analysing — scanning project files…", showSpinner = true)
        val scopeFile = selectedScopeFile()
        val scopeLabel = (scopeCombo.selectedItem as? ScopeChoice)?.label ?: "All project"
        object : Task.Backgroundable(project, "Analysing design system", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val report = DesignSystemAnalyzer.getInstance(project).analyze(scopeFile)
                ApplicationManager.getApplication().invokeLater {
                    lastReport = report
                    render(report, scopeLabel)
                }
            }
        }.queue()
    }

    // ─── Rendering ────────────────────────────────────────────────────────

    private fun renderEmpty(html: String, showSpinner: Boolean = false) {
        content.removeAll()
        val label = JBLabel("<html><div style='text-align:center;'>$html</div></html>").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            foreground = JBColor.GRAY
        }
        
        content.add(Box.createVerticalGlue())
        if (showSpinner) {
            val spinner = com.intellij.util.ui.AsyncProcessIcon("AnalysisSpinner").apply {
                alignmentX = Component.CENTER_ALIGNMENT
            }
            content.add(spinner)
            content.add(Box.createVerticalStrut(JBUI.scale(16)))
        }
        content.add(label)
        content.add(Box.createVerticalGlue())
        
        content.revalidate()
        content.repaint()
    }

    private fun render(report: AnalysisReport, scopeLabel: String) {
        content.removeAll()
        content.add(capWidth(headerSection(report, scopeLabel), MAX_CONTENT_WIDTH))
        content.add(verticalSpacer(14))
        content.add(subScoresGrid(report.subScores))
        content.add(verticalSpacer(18))
        // Order chosen for actionability: noisy/dirty stuff (hardcoded, unused,
        // duplicates) bubble up first so the user sees what to clean up;
        // structural/curiosity sections (semantic mismatches, source usage) sit
        // at the bottom.
        content.add(capWidth(CollapsibleSection(
            title = "Hardcoded clusters",
            count = report.hardcodedClusters.size,
            helpText = HARDCODED_HELP,
            body = hardcodedBody(report.hardcodedClusters),
        ), MAX_CONTENT_WIDTH))
        content.add(verticalSpacer(10))
        content.add(capWidth(CollapsibleSection(
            title = "Unused tokens",
            count = report.unusedTokens.size,
            helpText = UNUSED_HELP,
            body = unusedBody(report.unusedTokens),
            initiallyCollapsed = true,
        ), MAX_CONTENT_WIDTH))
        content.add(verticalSpacer(10))
        content.add(capWidth(CollapsibleSection(
            title = "Duplicates",
            count = report.duplicateClusters.size,
            helpText = DUPLICATE_HELP,
            body = duplicateBody(report.duplicateClusters),
            initiallyCollapsed = true,
        ), MAX_CONTENT_WIDTH))
        content.add(verticalSpacer(10))
        content.add(capWidth(CollapsibleSection(
            title = "Semantic incoherences",
            count = report.incoherences.size,
            helpText = INCOHERENCE_HELP,
            body = incoherenceBody(report.incoherences),
            initiallyCollapsed = true,
        ), MAX_CONTENT_WIDTH))
        content.add(verticalSpacer(10))
        content.add(capWidth(CollapsibleSection(
            title = "Token-source usage",
            count = report.coverage.sources.size,
            helpText = COVERAGE_HELP,
            body = coverageBody(report),
            initiallyCollapsed = true,
        ), MAX_CONTENT_WIDTH))
        content.add(verticalSpacer(12))
        content.revalidate()
        content.repaint()
    }

    private fun headerSection(report: AnalysisReport, scopeLabel: String): JComponent {
        val gauge = ScoreGauge(120).apply {
            score = report.score
            grade = report.grade
        }
        val summary = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(20)
            alignmentY = Component.TOP_ALIGNMENT
            add(JBLabel("<html><b>Design System health</b></html>").apply {
                font = font.deriveFont(font.size2D + 2f)
            })
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(JBLabel("<html>Analysing scope: <b>${escape(scopeLabel)}</b></html>").apply {
                foreground = JBColor.GRAY
                font = JBFont.small()
            })
            add(JBLabel("${report.totalTokens} tokens · ${report.scannedFiles} files scanned · " +
                "${report.tookMs} ms").apply {
                foreground = JBColor.GRAY
                font = JBFont.small()
            })
        }
        return horizontalRow(gauge, summary)
    }

    /**
     * 2×2 grid of sub-score cards. We wrap the grid inside a `BorderLayout`
     * with a fixed-max-width centre so the cards don't blow up when the tool
     * window is wide — anything wider than `MAX_GRID_WIDTH` ends up as
     * trailing whitespace on the right.
     */
    private fun subScoresGrid(subs: List<SubScore>): JComponent {
        val grid = JPanel(GridLayout(2, 2, JBUI.scale(10), JBUI.scale(10))).apply {
            isOpaque = false
        }
        for (sub in subs) grid.add(subScoreCard(sub))
        return capWidth(grid, MAX_GRID_WIDTH)
    }

    private fun subScoreCard(sub: SubScore): JComponent {
        val bar = MiniBar(sub.axis.displayName, sub.score, max = 100, rightCaption = "${sub.score}/100")
        val caption = JBLabel("<html><div style='width:240px'>${escape(sub.caption)}</div></html>").apply {
            foreground = JBColor.GRAY
            font = JBFont.small()
            border = JBUI.Borders.emptyTop(6)
        }
        return cardPanel().apply {
            // Keep cards compact: GridLayout cells have equal size, but the
            // outer width cap (see [subScoresGrid]) prevents over-stretching.
            minimumSize = Dimension(JBUI.scale(220), JBUI.scale(82))
            preferredSize = Dimension(JBUI.scale(260), JBUI.scale(82))
            add(bar, BorderLayout.NORTH)
            add(caption, BorderLayout.CENTER)
        }
    }

    /**
     * Wraps [child] in a panel that exposes [maxPx] as its maximum width.
     * Combined with the BoxLayout-y_AXIS parent, this prevents children from
     * growing beyond a comfortable reading width on wide tool windows.
     */
    private fun capWidth(child: JComponent, maxPx: Int): JComponent {
        val wrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(child, BorderLayout.CENTER)
        }
        wrap.maximumSize = Dimension(JBUI.scale(maxPx), Int.MAX_VALUE)
        return wrap
    }

    // ─── Section bodies ───────────────────────────────────────────────────

    private fun incoherenceBody(rows: List<Incoherence>): JComponent {
        if (rows.isEmpty()) return emptyState("No semantic incoherence detected.")
        val panel = verticalList()
        for (row in rows.take(50)) panel.add(incoherenceRow(row))
        if (rows.size > 50) panel.add(moreLabel(rows.size - 50))
        return panel
    }

    private fun incoherenceRow(row: Incoherence): JComponent {
        val text = "<html><b>${escape(row.token.name)}</b><br/>" +
            "<span style='color:#888'>${escape(row.rationale)}</span></html>"
        return rowPanel(text, locate = { navigateTo(row.token.filePath, row.token.offset) })
    }

    private fun duplicateBody(clusters: List<DuplicateCluster>): JComponent {
        if (clusters.isEmpty()) return emptyState("No duplicate tokens detected.")
        val panel = verticalList()
        for (cluster in clusters.take(30)) panel.add(duplicateRow(cluster))
        if (clusters.size > 30) panel.add(moreLabel(clusters.size - 30))
        return panel
    }

    private fun duplicateRow(cluster: DuplicateCluster): JComponent {
        val tokenLinks = cluster.tokens.joinToString(", ") { escape(it.name) }
        val text = "<html><code>${escape(cluster.resolvedValue)}</code> — " +
            "<b>${cluster.tokens.size}</b> tokens (canonical: <code>${escape(cluster.suggestedCanonical.name)}</code>)" +
            "<br/><span style='color:#888;font-size:90%'>$tokenLinks</span></html>"
        return rowPanel(text, locate = {
            navigateTo(cluster.suggestedCanonical.filePath, cluster.suggestedCanonical.offset)
        })
    }

    private fun hardcodedBody(clusters: List<HardcodedCluster>): JComponent {
        if (clusters.isEmpty()) return emptyState("No notable hardcoded value found.")
        val panel = verticalList()
        for (cluster in clusters.take(30)) panel.add(hardcodedRow(cluster))
        if (clusters.size > 30) panel.add(moreLabel(clusters.size - 30))
        return panel
    }

    /**
     * Each cluster row is itself collapsible: the header shows the literal +
     * count + match info, click expands a small table with one row per
     * occurrence (`filename:line` + locate button). Default collapsed so the
     * outer section stays scannable when many clusters are present.
     */
    private fun hardcodedRow(cluster: HardcodedCluster): JComponent {
        val container = JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            border = JBUI.Borders.customLineBottom(JBColor.border())
        }
        val chevron = JLabel(AllIcons.General.ArrowRight).apply {
            border = JBUI.Borders.empty(0, 4, 0, 8)
        }
        val match = cluster.matchingTokenName?.let {
            " · <span style='color:#5fa970'>matches token <code>${escape(it)}</code></span>"
        }.orEmpty()
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 4)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(chevron)
                add(JBLabel(
                    "<html><code>${escape(cluster.literal)}</code> &nbsp;—&nbsp; " +
                        "<b>${cluster.occurrences.size}</b> occurrence(s)$match</html>"
                ))
            }
            add(left, BorderLayout.CENTER)
        }

        val tableBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            border = JBUI.Borders.empty(0, 28, 8, 4)
            isVisible = false
            for (occ in cluster.occurrences) add(occurrenceTableRow(occ))
        }

        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                tableBody.isVisible = !tableBody.isVisible
                chevron.icon = if (tableBody.isVisible) AllIcons.General.ArrowDown
                else AllIcons.General.ArrowRight
                container.revalidate()
                container.repaint()
            }
        })

        container.add(header, BorderLayout.NORTH)
        container.add(tableBody, BorderLayout.CENTER)
        return container
    }

    private fun occurrenceTableRow(occ: HardcodedOccurrence): JComponent {
        val basename = occ.filePath.substringAfterLast('/')
        val parent = occ.filePath.substringBeforeLast('/').substringAfterLast('/')
        val text = JBLabel(
            "<html><code>$basename</code>:${occ.line} " +
                "<span style='color:#888'>· $parent</span></html>"
        ).apply { toolTipText = occ.filePath }
        val locate = targetButton("Open ${basename}:${occ.line}") {
            navigateTo(occ.filePath, occ.offset)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(3, 0)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            add(text, BorderLayout.CENTER)
            add(locate, BorderLayout.EAST)
        }
    }

    /**
     * Token-source usage section: each declared file (catalog) gets a row with
     * its declared/used counts and a progress bar showing the share of its
     * tokens actually referenced elsewhere in the project. The lower the bar,
     * the more dead-weight the catalog carries.
     */
    private fun coverageBody(report: AnalysisReport): JComponent {
        val cov = report.coverage
        val total = cov.tokenisedAssignments + cov.literalAssignments
        val ratioPct = (cov.ratio * 100).toInt()
        val column = verticalList()
        column.add(JBLabel(
            "<html><b>$ratioPct%</b> global coverage &nbsp; " +
                "<span style='color:#888'>(${cov.tokenisedAssignments} token refs · " +
                "${cov.literalAssignments} literals · $total total)</span></html>"
        ).apply {
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        if (cov.sources.isEmpty()) {
            column.add(emptyState("No token-source file in the selected scope."))
            return column
        }
        column.add(JBLabel(
            "<html><i>Usage rate per <b>token-source file</b> " +
                "(catalog → consumers):</i></html>"
        ).apply {
            foreground = JBColor.GRAY
            font = JBFont.small()
            border = JBUI.Borders.emptyBottom(6)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        for (src in cov.sources) column.add(sourceUsageRow(src))
        return column
    }

    private fun sourceUsageRow(src: TokenSourceUsage): JComponent {
        val basename = src.filePath.substringAfterLast('/')
        val pct = (src.ratio * 100).toInt()
        val nameLabel = JBLabel(basename).apply { toolTipText = src.filePath }
        val rightCaption = JBLabel("$pct%  (${src.used}/${src.declared} tokens used)").apply {
            foreground = JBColor.GRAY
            font = JBFont.small()
        }
        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(nameLabel, BorderLayout.WEST)
            add(rightCaption, BorderLayout.EAST)
        }
        val bar = MiniBar(label = "", value = pct, max = 100, rightCaption = null).apply {
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(12))
        }
        val locate = targetButton("Open ${src.filePath}") { navigateTo(src.filePath, 0) }
        val barRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(bar, BorderLayout.CENTER)
            add(locate, BorderLayout.EAST)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(8, 4),
            )
            add(topRow)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(barRow)
        }
    }

    /** Tokens declared but never referenced. Each row links to the declaration. */
    private fun unusedBody(unused: List<fr.fsh.tokendesigner.model.DesignToken>): JComponent {
        if (unused.isEmpty()) return emptyState("Every token is referenced at least once.")
        val panel = verticalList()
        for (token in unused.take(80)) {
            val text = "<html><b>${escape(token.name)}</b> &nbsp; " +
                "<span style='color:#888'>= ${escape(token.resolvedValue)}</span></html>"
            panel.add(rowPanel(text, locate = { navigateTo(token.filePath, token.offset) }))
        }
        if (unused.size > 80) panel.add(moreLabel(unused.size - 80))
        return panel
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun rowPanel(html: String, locate: (() -> Unit)?): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(8, 4),
            )
            isOpaque = false
        }
        panel.add(JBLabel(html), BorderLayout.CENTER)
        if (locate != null) panel.add(targetButton("Open declaration") { locate() }, BorderLayout.EAST)
        return panel
    }

    /** Round, always-visible target button. */
    private fun targetButton(tooltip: String, onClick: () -> Unit): JComponent =
        RoundIconButton(AllIcons.General.Locate, tooltip, sizePx = 22) { onClick() }

    private fun cardPanel(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border()),
            JBUI.Borders.empty(10, 12),
        )
        background = JBColor.background()
    }

    private fun horizontalRow(left: JComponent, right: JComponent): JComponent =
        JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.CENTER)
        }

    private fun verticalList(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
    }

    private fun emptyState(text: String): JComponent = JBLabel("<html><i>$text</i></html>").apply {
        alignmentX = Component.LEFT_ALIGNMENT
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(8, 4)
    }

    private fun moreLabel(extra: Int): JComponent =
        JBLabel("<html><i>+ $extra more…</i></html>").apply {
            foreground = JBColor.GRAY
            font = JBFont.small()
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4)
        }

    private fun verticalSpacer(px: Int): Component = Box.createVerticalStrut(JBUI.scale(px))

    private fun navigateTo(filePath: String, offset: Int) {
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        OpenFileDescriptor(project, vf, offset).navigate(true)
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        /** Comfortable max reading width for the whole report. Wider tool windows
         *  leave whitespace on the right rather than stretching every section. */
        const val MAX_CONTENT_WIDTH = 720
        const val MAX_GRID_WIDTH = 600

        const val INCOHERENCE_HELP =
            "Tokens whose name suggests one category but whose value implies another. " +
                "Click the target icon to jump to the declaration."
        const val DUPLICATE_HELP =
            "Tokens declared separately but resolving to the same value. " +
                "Suggestion: keep the shortest/most semantic name and alias the others."
        const val HARDCODED_HELP =
            "Literal values repeated across the codebase. Click a row to expand " +
                "the per-occurrence table and jump to any hit."
        const val COVERAGE_HELP =
            "How much of each token-source file is actually referenced. Low " +
                "ratios = catalog bloat or dead tokens. Hover the filename for " +
                "the full path."
        const val UNUSED_HELP =
            "Tokens declared but never referenced anywhere in the project " +
                "(no `var(--…)`, `\$…` or `'{path}'` match found). Click a row " +
                "to open the declaration."
    }
}
