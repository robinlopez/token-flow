package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
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
import fr.fsh.tokendesigner.analyze.Ambiguity
import fr.fsh.tokendesigner.analyze.AnalysisReport
import fr.fsh.tokendesigner.analyze.DesignSystemAnalyzer
import fr.fsh.tokendesigner.analyze.DuplicateCluster
import fr.fsh.tokendesigner.analyze.HardcodedCluster
import fr.fsh.tokendesigner.analyze.HardcodedOccurrence
import fr.fsh.tokendesigner.analyze.HardcodedValue
import fr.fsh.tokendesigner.analyze.Incoherence
import fr.fsh.tokendesigner.analyze.SubScore
import fr.fsh.tokendesigner.analyze.TokenSourceUsage
import fr.fsh.tokendesigner.model.DesignToken
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

    /** Files we watch for changes after an analysis: broken-ref locations + token-source files. */
    private val watchedPaths = mutableSetOf<String>()

    /** Banner shown when any watched file changes — invites the user to re-run. */
    private val staleBanner: JPanel = run {
        val bg = JBColor(java.awt.Color(0xFFF4C2), java.awt.Color(0x5C4A1E))
        val border = JBColor(java.awt.Color(0xE5B800), java.awt.Color(0x8A7028))
        val fg = JBColor(java.awt.Color(0x6B4F00), java.awt.Color(0xF0E0A0))
        JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isVisible = false
            isOpaque = true
            background = bg
            this.border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineBottom(border),
                JBUI.Borders.empty(8, 14),
            )
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JLabel(AllIcons.General.BalloonWarning))
                add(JBLabel(
                    "<html><b>Analysis is out of date.</b> &nbsp;" +
                        "<span style='color:#000000'>Files referenced in the report changed since the last run.</span></html>"
                ).apply {
                    foreground = fg
                })
            }
            val rerun = com.intellij.ui.components.ActionLink("Re-run analysis") { runAnalysis() }.apply {
                foreground = fg
                font = font.deriveFont(java.awt.Font.BOLD)
            }
            val dismiss = RoundIconButton(AllIcons.Actions.Close, "Dismiss", sizePx = 18) {
                staleBanner.isVisible = false
                staleBanner.revalidate()
                staleBanner.repaint()
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
                isOpaque = false
                add(rerun)
                add(dismiss)
            }
            add(left, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
    }

    /**
     * Choice of scope analysed. Sentinel `null` means "all configured scopes /
     * whole project" (we pass `null` to `TokenIndex.get`). Otherwise we pass a
     * representative `VirtualFile` inside the scope's `rootPath` so the
     * existing `ScopeResolver` resolves to that scope.
     */
    private data class ScopeChoice(val label: String, val representative: VirtualFile?)

    /**
     * Stable identifier for the currently-selected scope choice, used to
     * preserve the user's selection across combo rebuilds (e.g. when the user
     * edits scopes in settings).
     *  - "ALL"     → "All project"
     *  - "Scope: <name>" → a named configured scope
     */
    private var stickyScopeKey: String? = null

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
        addActionListener {
            // Capture the user's explicit pick so the next rebuild restores it.
            val choice = selectedItem as? ScopeChoice ?: return@addActionListener
            stickyScopeKey = scopeChoiceKey(choice)
        }
    }

    private fun scopeChoiceKey(choice: ScopeChoice): String = when {
        choice.label == "All project" -> "ALL"
        else -> choice.label  // scope names are stable
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
            watchedPaths.clear()
            staleBanner.isVisible = false
        }
    }

    /**
     * Tracks every [CollapsibleSection] added to the report, in display order.
     * Used by the sticky-header overlay to find the section currently
     * touching the top of the viewport and mirror its header there.
     */
    private val sectionRegistry = mutableListOf<CollapsibleSection>()

    /**
     * Floating banner painted above the scroll viewport. When a section's own
     * header scrolls past the top, we mirror its title/count here so the user
     * keeps a context anchor while reading long lists. Hidden when no section
     * is partially scrolled past.
     *
     * Declared before [init] because the constructor wires it into the
     * content stack — keeping it below would leave it `null` at that point.
     */
    private val stickyHeader: JPanel = JPanel(BorderLayout()).apply {
        isVisible = false
        isOpaque = true
        background = JBColor.namedColor("ToolWindow.HeaderBackground", JBColor(0xF5F5F5, 0x3C3F41))
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(8, 12 + 16, 8, 12 + 16),  // align with capWidth padding
        )
    }

    private var lastStickyKey: String? = null

    init {
        rebuildScopeCombo()
        setupToolbar()
        renderEmpty("Click <b>Run analysis</b> to compute the design-system report.")
        val scroll = JBScrollPane(content).apply {
            border = null
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        // Recompute the sticky pin every time the viewport scrolls. Cheap —
        // we only swap the displayed header when the active section actually
        // changes (see `lastStickyKey`).
        scroll.viewport.addChangeListener {
            javax.swing.SwingUtilities.invokeLater { updateStickyHeader() }
        }
        // Sticky banner lives directly above the scroll pane, on the same
        // NORTH stack as the stale banner. BorderLayout stacking keeps it
        // pinned even when the report grows past the viewport.
        val scrollColumn = JPanel(BorderLayout()).apply {
            add(stickyHeader, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(staleBanner, BorderLayout.NORTH)
            add(scrollColumn, BorderLayout.CENTER)
        })
        TokenSelectorSettings.getInstance(project).addScopesChangeListener(scopesListener)
        setupFileChangeTracking()
    }

    /**
     * Watch VFS changes on files that surfaced in the last report (broken-ref
     * locations + token-source files). When any of them changes on disk we
     * surface a banner inviting a re-run — without auto-launching a heavy
     * analysis while the user is still editing.
     */
    private fun setupFileChangeTracking() {
        // VFS_CHANGES is an application-level topic — subscribing via
        // `project.messageBus` would silently never fire. Tie the connection's
        // lifetime to the project so it's disposed alongside the tool window.
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : com.intellij.openapi.vfs.newvfs.BulkFileListener {
                override fun after(events: MutableList<out com.intellij.openapi.vfs.newvfs.events.VFileEvent>) {
                    if (watchedPaths.isEmpty() || lastReport == null) return
                    val touched = events.any { ev ->
                        val p = ev.path
                        p.isNotEmpty() && p in watchedPaths
                    }
                    if (touched) ApplicationManager.getApplication().invokeLater {
                        staleBanner.isVisible = true
                        staleBanner.revalidate()
                        staleBanner.repaint()
                    }
                }
            }
        )
    }
    
    private fun rebuildScopeCombo() {
        // Build the new list of items in the exact order configured in settings.
        // Swap the ComboBoxModel atomically so we don't race with Swing's own
        // selection/listener events between successive removeAllItems/addItem
        // calls (which used to leave the combo showing a stale order).
        //
        // The analyser scope is an explicit, user-driven choice — it does NOT
        // follow the active editor (issue #21). The Library and Hardcoded
        // panels keep their own active-editor follow-along behaviour.
        val items = mutableListOf<ScopeChoice>()
        items += ScopeChoice("All project", null)
        for (scope in TokenSelectorSettings.getInstance(project).scopes) {
            val rep = representativeFileFor(scope) ?: continue
            items += ScopeChoice("Scope: ${scope.name.ifBlank { "(unnamed)" }}", rep)
        }
        scopeCombo.model = javax.swing.DefaultComboBoxModel(items.toTypedArray())

        // Restore the user's last explicit pick across rebuilds; default to
        // "All project" on first build or when the picked scope is gone.
        val sticky = stickyScopeKey
        val matchIdx = if (sticky != null) items.indexOfFirst { scopeChoiceKey(it) == sticky } else -1
        scopeCombo.selectedIndex = if (matchIdx >= 0) matchIdx else 0
    }

    private fun representativeFileFor(scope: Scope): VirtualFile? {
        if (scope.isCommon) return null
        val abs = ScopeResolver.absolutize(project, scope.rootPath) ?: return null
        return LocalFileSystem.getInstance().findFileByPath(abs)
    }

    private fun setupToolbar() {
        // Single entry point: label flips to "Re-run analysis" once a report exists.
        // Prior "Refresh" button was redundant with this and confused the toolbar.
        val run = object : AnAction("Run analysis", "Compute the design-system report", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) = runAnalysis()
            override fun update(e: AnActionEvent) {
                e.presentation.text = if (lastReport != null) "Re-run analysis" else "Run analysis"
            }
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("DesignTokenAnalyze", DefaultActionGroup(run), true)
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
        staleBanner.isVisible = false
        val scopeFile = selectedScopeFile()
        val scopeLabel = (scopeCombo.selectedItem as? ScopeChoice)?.label ?: "All project"
        object : Task.Backgroundable(project, "Analysing design system", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val report = DesignSystemAnalyzer.getInstance(project).analyze(scopeFile)
                ApplicationManager.getApplication().invokeLater {
                    lastReport = report
                    rebuildWatchedPaths(report)
                    render(report, scopeLabel)
                }
            }
        }.queue()
    }

    /**
     * Files we'll watch for change events so the "stale" banner can pop up
     * when the user fixes a broken ref or edits a token catalog.
     */
    private fun rebuildWatchedPaths(report: AnalysisReport) {
        watchedPaths.clear()
        report.brokenReferences.forEach { watchedPaths.add(it.filePath) }
        report.coverage.sources.forEach { watchedPaths.add(it.filePath) }
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
        sectionRegistry.clear()
        // Order chosen for actionability: broken references first — those are
        // genuine bugs (missing tokens, typos). Then the two hardcoded sections
        // (cleanup opportunities). Structural / curiosity sections sit at the
        // bottom.
        addSection(CollapsibleSection(
            title = "Broken references",
            count = report.brokenReferences.size,
            helpText = BROKEN_REF_HELP,
            body = brokenReferencesBody(report.brokenReferences),
        ))
        addSection(CollapsibleSection(
            title = "Hardcoded values",
            count = report.hardcodedValues.size,
            helpText = HARDCODED_VALUES_HELP,
            body = hardcodedValuesBody(report.hardcodedValues),
        ))
        addSection(CollapsibleSection(
            title = "Hardcoded clusters",
            count = report.hardcodedClusters.size,
            helpText = HARDCODED_HELP,
            body = hardcodedBody(report.hardcodedClusters),
        ))
        addSection(CollapsibleSection(
            title = "Unused tokens",
            count = report.unusedTokens.size,
            helpText = UNUSED_HELP,
            body = unusedBody(report.unusedTokens),
            initiallyCollapsed = true,
        ))
        addSection(CollapsibleSection(
            title = "Duplicates",
            count = report.duplicateClusters.size,
            helpText = DUPLICATE_HELP,
            body = duplicateBody(report.duplicateClusters),
            initiallyCollapsed = true,
        ))
        addSection(CollapsibleSection(
            title = "Semantic incoherences",
            count = report.incoherences.size,
            helpText = INCOHERENCE_HELP,
            body = incoherenceBody(report.incoherences),
            initiallyCollapsed = true,
        ))
        addSection(CollapsibleSection(
            title = "Ambiguous tokens",
            count = report.ambiguities.size,
            helpText = AMBIGUITY_HELP,
            body = ambiguityBody(report.ambiguities),
            initiallyCollapsed = true,
        ))
        addSection(CollapsibleSection(
            title = "Token-source usage",
            count = report.coverage.sources.size,
            helpText = COVERAGE_HELP,
            body = coverageBody(report),
            initiallyCollapsed = true,
        ))
        content.add(verticalSpacer(12))
        content.revalidate()
        content.repaint()
        updateStickyHeader()
    }

    /**
     * Tracks every [CollapsibleSection] added to the report. Used by the
     * sticky-header overlay so it can find the section currently touching
     * the top of the viewport and mirror its header there.
     */
    private fun addSection(section: CollapsibleSection) {
        sectionRegistry += section
        content.add(capWidth(section, MAX_CONTENT_WIDTH))
        content.add(verticalSpacer(10))
        // Both the toggle (collapse/expand) and any later layout pass need to
        // recompute which section is currently pinned at the top.
        section.addStateChangeListener { updateStickyHeader() }
    }

    private fun updateStickyHeader() {
        val viewport = findViewport(content) ?: return
        val viewY = viewport.viewPosition.y
        var pinned: CollapsibleSection? = null
        for (sec in sectionRegistry) {
            if (!sec.isShowing) continue
            val secWrapper = sec.parent ?: continue
            val topInContent = try {
                javax.swing.SwingUtilities.convertPoint(secWrapper, 0, 0, content).y
            } catch (_: Exception) { continue }
            val headerH = sec.headerHeight
            val sectionBottom = topInContent + secWrapper.height
            // The section's own header has scrolled out, but the section
            // still intersects the viewport.
            if (viewY in (topInContent + 1) until sectionBottom &&
                viewY >= topInContent + headerH
            ) {
                pinned = sec
                break
            }
        }
        val newKey = pinned?.headerSignature
        if (newKey == lastStickyKey) return
        lastStickyKey = newKey
        stickyHeader.removeAll()
        if (pinned == null) {
            stickyHeader.isVisible = false
        } else {
            stickyHeader.add(pinned.buildHeaderClone(), BorderLayout.CENTER)
            stickyHeader.isVisible = true
        }
        stickyHeader.revalidate()
        stickyHeader.repaint()
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
        // `0` rows → GridLayout auto-rows to fit any number of cards in 2 cols.
        val grid = JPanel(GridLayout(0, 2, JBUI.scale(10), JBUI.scale(10))).apply {
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
        // Override getMaximumSize so the wrapper never stretches vertically
        // beyond the child's preferred height. Without this, BoxLayout-Y_AXIS
        // distributes spare vertical space across collapsed sections — making
        // the gap between consecutive collapsed headers balloon.
        val wrap = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(JBUI.scale(maxPx), preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(child, BorderLayout.CENTER)
        }
        return wrap
    }

    // ─── Section bodies ───────────────────────────────────────────────────

    private fun incoherenceBody(rows: List<Incoherence>): JComponent {
        if (rows.isEmpty()) return emptyState("No semantic incoherence detected.")
        val panel = verticalList()
        renderTruncatedList(panel, rows, limit = 50) { incoherenceRow(it) }
        return panel
    }


    private fun brokenReferencesBody(rows: List<fr.fsh.tokendesigner.analyze.BrokenReference>): JComponent {
        if (rows.isEmpty()) return emptyState("No broken token references detected.")
        val panel = verticalList()
        renderTruncatedList(panel, rows, limit = 50) { row ->
            val basename = row.filePath.substringAfterLast('/')
            val text = "<html><b><code style='color:#db5858'>${escape(row.name)}</code></b>" +
                "<br/><span style='color:#888;font-size:90%'>$basename:${row.line}</span></html>"
            rowPanel(text, locate = { navigateTo(row.filePath, row.offset) })
        }
        return panel
    }



    private fun incoherenceRow(row: Incoherence): JComponent {
        val text = "<html><b>${escape(row.token.name)}</b><br/>" +
            "<span style='color:#888'>${escape(row.rationale)}</span></html>"
        return rowPanel(text, locate = { navigateTo(row.token.filePath, row.token.offset) })
    }

    private fun ambiguityBody(rows: List<Ambiguity>): JComponent {
        if (rows.isEmpty()) return emptyState("No ambiguous token names detected.")
        val panel = verticalList()
        renderTruncatedList(panel, rows, limit = 50) { ambiguityRow(it) }
        return panel
    }

    private fun ambiguityRow(row: Ambiguity): JComponent {
        val text = "<html>" +
            "<b>${escape(row.token.name)}</b>" +
            "&nbsp;<span style='color:#888;font-size:90%'>= ${escape(row.token.resolvedValue.take(40))}</span>" +
            "<br/><span style='color:#888;font-style:italic'>${escape(row.reason)}</span>" +
            "<br/><span style='color:#1A73E8'>💡 ${escape(row.alternativeInterpretation)}</span>" +
            "</html>"
        val panel = JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(8, 4),
            )
            isOpaque = false
        }
        panel.add(JBLabel(text), BorderLayout.CENTER)
        panel.add(targetButton("Open declaration") { navigateTo(row.token.filePath, row.token.offset) }, BorderLayout.EAST)
        return panel
    }

    private fun unusedBody(tokens: List<DesignToken>): JComponent {
        if (tokens.isEmpty()) return emptyState("No unused tokens found.")
        val panel = verticalList()
        renderTruncatedList(panel, tokens, limit = 50) { unusedRow(it) }
        return panel
    }

    private fun unusedRow(token: DesignToken): JComponent {
        val text = "<html><b>${escape(token.name)}</b> &nbsp; " +
            "<span style='color:#888'>= ${escape(token.resolvedValue)}</span></html>"
        return rowPanel(text, locate = { navigateTo(token.filePath, token.offset) })
    }

    private fun duplicateBody(clusters: List<DuplicateCluster>): JComponent {
        if (clusters.isEmpty()) return emptyState("No duplicate tokens detected.")
        val panel = verticalList()
        renderTruncatedList(panel, clusters, limit = 30) { duplicateRow(it) }
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
        renderTruncatedList(panel, clusters, limit = 30) { hardcodedRow(it) }
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

    private fun hardcodedValuesBody(rows: List<HardcodedValue>): JComponent {
        if (rows.isEmpty()) return emptyState("No hardcoded values matching an existing token found.")
        val panel = verticalList()
        renderTruncatedList(panel, rows, limit = 50) { hardcodedValueRow(it) }
        return panel
    }

    /**
     * One collapsible row per (literal, category) group. Header carries the
     * value, its detected property family, occurrence count and the
     * highest-ranked existing token (via the Tier/Role suggestion engine).
     * Click expands the per-occurrence list — same pattern as the cluster
     * accordion above, kept visually consistent.
     */
    private fun hardcodedValueRow(row: HardcodedValue): JComponent {
        val container = JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            border = JBUI.Borders.customLineBottom(JBColor.border())
        }
        val chevron = JLabel(AllIcons.General.ArrowRight).apply {
            border = JBUI.Borders.empty(0, 4, 0, 8)
        }
        val categoryLabel = row.category?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Other"
        val suggestion = row.suggestedToken

        // Two-line header layout — keeps each line short so a long token name
        // never wraps over the chevron / button column regardless of tool-window
        // width. Top line: literal + category + count. Bottom line: suggestion
        // (gray). Tooltip carries the full unstyled text for accessibility.
        val titleText = "${row.literal}  —  [$categoryLabel]  ·  ${row.occurrences.size} occurrence(s)"
        val titleLabel = JBLabel(
            "<html><nobr><code>${escape(row.literal)}</code> &nbsp;—&nbsp; " +
                "<span style='color:#888'>[${escape(categoryLabel)}]</span> &nbsp;·&nbsp; " +
                "<b>${row.occurrences.size}</b> occurrence(s)</nobr></html>"
        ).apply { toolTipText = titleText }
        val suggestionLabel = suggestion?.let {
            val tip = "Suggested token: ${it.name}"
            JBLabel(
                "<html><nobr><span style='color:#1A73E8'>→ suggests <code>${escape(it.name)}</code></span></nobr></html>"
            ).apply {
                toolTipText = tip
                border = JBUI.Borders.emptyTop(2)
                font = JBFont.small()
            }
        }

        val textColumn = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentY = Component.CENTER_ALIGNMENT
            add(titleLabel)
            if (suggestionLabel != null) add(suggestionLabel)
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 4)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            add(chevron, BorderLayout.WEST)
            add(textColumn, BorderLayout.CENTER)
            if (suggestion != null) {
                add(targetButton("Open suggested token") {
                    navigateTo(suggestion.filePath, suggestion.offset)
                }, BorderLayout.EAST)
            }
        }

        val tableBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            border = JBUI.Borders.empty(0, 28, 8, 4)
            isVisible = false
            for (occ in row.occurrences) add(occurrenceTableRow(occ))
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
        // Caption: `filename:line · property`. We replaced the old "parent
        // folder" segment with the CSS / JS property the literal is bound to —
        // far more actionable (it answers "WHY did this row surface?") and
        // avoids visually repeating the filename across sibling rows.
        val propertyChunk = occ.propertyName
            ?.let { "<span style='color:#888'>· <i>${escape(it)}</i></span>" }
            .orEmpty()
        val text = JBLabel(
            "<html><nobr><code>${escape(basename)}</code>:${occ.line} $propertyChunk</nobr></html>"
        ).apply { toolTipText = occ.filePath }
        // Tooltip on the locate button used to echo "Open <basename>:<line>",
        // duplicating the visible caption. A generic verb is enough — the row
        // already labels what we'll open.
        val locate = targetButton("Open occurrence") {
            navigateTo(occ.filePath, occ.offset)
        }
        // No fixed-height maximumSize: a small icon button never grows the row
        // and a hard `28px` cap was clipping long captions on narrower tool
        // windows. Let the row size itself to its content.
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(3, 0)
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

    private fun <T> renderTruncatedList(panel: JPanel, items: List<T>, limit: Int, renderer: (T) -> JComponent) {
        for (item in items.take(limit)) {
            panel.add(renderer(item))
        }
        if (items.size > limit) {
            val remaining = items.drop(limit)
            val more = moreLabel(remaining.size)
            more.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    panel.remove(more)
                    for (item in remaining) {
                        panel.add(renderer(item))
                    }
                    panel.revalidate()
                    panel.repaint()
                }
            })
            panel.add(more)
        }
    }

    private fun moreLabel(extra: Int): JComponent =
        JBLabel("<html><a href=''>+ $extra more…</a></html>").apply {
            foreground = JBColor.GRAY
            font = JBFont.small()
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }


    private fun verticalSpacer(px: Int): Component = Box.createVerticalStrut(JBUI.scale(px))

    /** Walks up the Swing parent chain to find the enclosing [JViewport], if any. */
    private fun findViewport(c: Component): javax.swing.JViewport? {
        var p: Component? = c
        while (p != null) {
            if (p is javax.swing.JViewport) return p
            p = p.parent
        }
        return null
    }

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
        const val AMBIGUITY_HELP =
            "Tokens that are not necessarily wrong, but whose name is ambiguous: it could " +
                "plausibly refer to more than one type of value. These are informational notices " +
                "to help you improve naming clarity in your design system. " +
                "Click the target icon to jump to the declaration."
        const val DUPLICATE_HELP =
            "Tokens declared separately but resolving to the same value. " +
                "Suggestion: keep the shortest/most semantic name and alias the others."
        const val HARDCODED_HELP =
            "Literal values repeated across the codebase with NO matching token. " +
                "These are opportunities to create a new token in your design system. " +
                "Click a row to expand the per-occurrence table and jump to any hit."
        const val HARDCODED_VALUES_HELP =
            "Literal values whose equivalent already exists as a token. Pure " +
                "actionable debt — replace each hit with the suggested token. " +
                "Grouped by (value + property family) so the same value used for " +
                "two distinct properties (e.g. 12px padding vs 12px font-size) " +
                "shows up separately with its own suggestion."
        const val COVERAGE_HELP =
            "How much of each token-source file is actually referenced. Low " +
                "ratios = catalog bloat or dead tokens. Hover the filename for " +
                "the full path."
        const val UNUSED_HELP =
            "Tokens declared but never referenced anywhere in the project " +
                "(no `var(--…)`, `\$…` or `'{path}'` match found). Click a row " +
                "to open the declaration."
        const val BROKEN_REF_HELP =
            "References to tokens that do not exist in your design system. " +
                "This usually means a typo or a deleted token that is still being used."
    }
}
