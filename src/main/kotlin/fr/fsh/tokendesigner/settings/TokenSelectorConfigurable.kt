package fr.fsh.tokendesigner.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBUI
import fr.fsh.tokendesigner.analyze.DesignSystemAnalyzer
import fr.fsh.tokendesigner.inspection.LiteralFinder
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.ui.IconVariant
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets

import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JSpinner
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Settings UI for the Token Flow.
 *
 *  - Top half: master-detail editor for scopes. Each scope has a name, an
 *    optional project-relative root folder (empty = "common", always active),
 *    and its own list of source files/folders.
 *  - Bottom half: trigger options (hover toggle / delay / shortcut link).
 */
class TokenSelectorConfigurable(private val project: Project) : Configurable {

    // Scopes (master-detail)
    private val scopesListModel = DefaultListModel<ScopeRow>()
    private val scopesList = JBList(scopesListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ScopeListRenderer()
        emptyText.text = "No scope configured — scanning all .scss/.sass/.css files in the project."
    }

    private val scopeNameField = JBTextField().apply { minimumSize = Dimension(80, 0) }
    private val scopeRootField = JBTextField().apply { minimumSize = Dimension(80, 0) }
    private val scopeRootBrowse = HyperlinkLabel("Browse…")
    private val scopePathsModel = DefaultListModel<String>()
    private val scopePathsList = JBList(scopePathsModel).apply {
        emptyText.text = "Add files or folders that contain tokens for this scope."
    }
    private val scopeExcludedPathsModel = DefaultListModel<String>()
    private val scopeExcludedPathsList = JBList(scopeExcludedPathsModel).apply {
        emptyText.text = "Add files whose variables should be ignored (e.g. library vars)."
    }
    private val scopeAnalysisExcludedPathsModel = DefaultListModel<String>()
    private val scopeAnalysisExcludedPathsList = JBList(scopeAnalysisExcludedPathsModel).apply {
        emptyText.text = "Add folders/files inside the root to skip during analysis."
    }
    private val scopeExternalPrefixesModel = DefaultListModel<String>()
    private val scopeExternalPrefixesList = JBList(scopeExternalPrefixesModel).apply {
        emptyText.text = "Add CSS custom-property prefixes injected by external frameworks (e.g. --p-, --ion-)."
    }
    private val scopeDetailContainer = JPanel(BorderLayout())


    // Trigger options
    private val hoverCheckBox = JBCheckBox("Show token info (resolved value & variants) on hover")
    private val hoverDelaySpinner = JSpinner(SpinnerNumberModel(700, 100, 5000, 100))
    private val autocompleteCheckBox = JBCheckBox("Suggest design tokens in code completion (var(--…) and \$…)")
    private val valueCompletionCheckBox = JBCheckBox("Suggest matching tokens when typing a value (e.g. padding: 4…)")
    private val valueCompletionTriggerCombo = javax.swing.JComboBox(ValueCompletionTrigger.entries.toTypedArray())
    private val inspectVariableDeclarationsCheckBox = JBCheckBox("Detect hardcoded values in variable declarations (e.g. \$color: #fff)")
    private val detectRuntimeInjectedCssVarsCheckBox = JBCheckBox("Recognise runtime-injected CSS variables (Angular [style.--x], React/Vue inline styles, setProperty…)")
    private val iconVariantCombo = javax.swing.JComboBox(IconVariant.entries.toTypedArray()).apply {
        // List rows show a 48px preview; the closed combo (index == -1) keeps a
        // compact 16px size so it doesn't blow up the form layout.
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val v = value as? IconVariant
                if (v != null && c is javax.swing.JLabel) {
                    val expanded = index >= 0
                    c.text = v.displayName
                    c.icon = if (expanded) v.loadScaled(20) else v.loadScaled(14)
                    c.iconTextGap = JBUI.scale(8)
                    c.border = JBUI.Borders.empty(if (expanded) 3 else 0, 4)
                }
                return c
            }
        }
        maximumRowCount = IconVariant.entries.size
    }

    private var rootPanel: JPanel? = null
    private var suppressDetailListeners = false
    /**
     * Index of the row currently mirrored by the detail fields. We can't read it
     * from `scopesList.selectedIndex` inside the selection listener because at
     * that point selection has *already* moved to the new row — committing then
     * would copy the still-stale field text onto the new selection. Tracking the
     * previous index explicitly avoids that cross-contamination.
     */
    private var detailBoundIndex: Int = -1

    override fun getDisplayName(): String = "Token Flow"

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    override fun createComponent(): JComponent {
        reset()

        wireScopeMaster()
        wireScopeDetail()

        val tabs = com.intellij.ui.components.JBTabbedPane()
        tabs.addTab("Scopes", buildScopesTab())
        tabs.addTab("Triggers", buildTriggerSection())
        tabs.addTab("Analyser", buildAnalyserTab())

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(tabs, BorderLayout.CENTER)
            rootPanel = this
        }
    }

    private fun buildScopesTab(): JComponent {
        val masterDetail = OnePixelSplitter(false, 0.30f).apply {
            firstComponent = buildMasterPanel()
            secondComponent = scopeDetailContainer
            // Without this, very narrow tool windows keep one side at its
            // preferred width and the splitter pushes the other off-screen.
            setHonorComponentsMinimumSize(true)
        }
        val intro = htmlMultiLine(
            "Each <b>scope</b> owns a list of source-of-truth files/folders. " +
                "When you edit a file inside a scope's <i>root</i>, only that scope's tokens " +
                "(plus any common scopes) are proposed. " +
                "Empty list = the plugin scans every <code>.scss</code>/<code>.sass</code>/" +
                "<code>.css</code> file in the project."
        ).apply { border = JBUI.Borders.emptyBottom(10) }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10, 4, 4, 4)
            // Hard floor on width so child panels can shrink the whole tab when
            // the user narrows the Settings dialog.
            minimumSize = Dimension(220, 0)
            add(intro, BorderLayout.NORTH)
            add(masterDetail, BorderLayout.CENTER)
        }
    }

    /**
     * `JBLabel` with HTML content has a single-line `preferredSize` (it never
     * wraps), which forces the parent to grow horizontally and triggers a
     * horizontal scroll. A read-only `JEditorPane` honors paragraph wrapping
     * inside the available width — exactly what we need for intro hints.
     *
     * We also override `getPreferredSize()` to claim width=0: the Settings
     * dialog wraps each `Configurable` in a `JScrollPane`, and `JEditorPane`'s
     * default preferred width is the longest unwrapped line — so the scope tab
     * was reporting a preferred width of ~700px and triggering a horizontal
     * scrollbar. Returning width=0 lets BorderLayout still grant the component
     * the full container width at render time (so the HTML wraps), while
     * keeping the parent free to shrink horizontally with the dialog.
     */
    private fun htmlMultiLine(html: String): JComponent =
        object : JEditorPane("text/html", "<html>$html</html>") {
            override fun getPreferredSize(): Dimension {
                val w = parent?.width ?: 0
                if (w > 0) setSize(w, Short.MAX_VALUE.toInt())
                val pref = super.getPreferredSize()
                return Dimension(0, pref.height)
            }
        }.apply {
            isEditable = false
            isOpaque = false
            border = null
            font = JBLabel().font
            putClientProperty("JEditorPane.honorDisplayProperties", true)
            minimumSize = Dimension(0, 0)
        }

    override fun isModified(): Boolean {
        commitDetailToList()
        val saved = TokenSelectorSettings.getInstance(project)
        if (saved.openOnHover != hoverCheckBox.isSelected) return true
        if (saved.hoverDelayMs != hoverDelaySpinner.value as Int) return true
        if (saved.autocompleteEnabled != autocompleteCheckBox.isSelected) return true
        if (saved.valueCompletionEnabled != valueCompletionCheckBox.isSelected) return true
        if (saved.valueCompletionMinChars != (valueCompletionTriggerCombo.selectedItem as ValueCompletionTrigger).minChars) return true
        if (saved.iconVariantName != (iconVariantCombo.selectedItem as IconVariant).name) return true
        if (saved.inspectVariableDeclarations != inspectVariableDeclarationsCheckBox.isSelected) return true
        if (saved.detectRuntimeInjectedCssVars != detectRuntimeInjectedCssVarsCheckBox.isSelected) return true
        
        val current = currentScopes()
        if (saved.scopes.size != current.size) return true
        return !saved.scopes.zip(current).all { (s1, s2) ->
            s1.name == s2.name && s1.rootPath == s2.rootPath &&
            s1.sourcePaths == s2.sourcePaths && s1.excludedPaths == s2.excludedPaths &&
            s1.analysisExcludedPaths == s2.analysisExcludedPaths &&
            s1.externalPrefixes == s2.externalPrefixes
        }
    }


    override fun apply() {
        commitDetailToList()
        val s = TokenSelectorSettings.getInstance(project)
        val newScopes = currentScopes()
        val scopesChanged = !sameScopes(s.scopes, newScopes)
        s.scopes = newScopes
        s.openOnHover = hoverCheckBox.isSelected
        s.hoverDelayMs = hoverDelaySpinner.value as Int
        s.autocompleteEnabled = autocompleteCheckBox.isSelected
        s.valueCompletionEnabled = valueCompletionCheckBox.isSelected
        s.valueCompletionMinChars = (valueCompletionTriggerCombo.selectedItem as ValueCompletionTrigger).minChars
        val newIcon = (iconVariantCombo.selectedItem as IconVariant).name
        val iconChanged = s.iconVariantName != newIcon
        s.iconVariantName = newIcon
        s.inspectVariableDeclarations = inspectVariableDeclarationsCheckBox.isSelected
        s.detectRuntimeInjectedCssVars = detectRuntimeInjectedCssVarsCheckBox.isSelected
        if (iconChanged) s.fireIconChanged()
        TokenIndex.getInstance(project).invalidate()
        // Fire AFTER the index has been invalidated so listeners that re-fetch
        // tokens (Analyze combo, dashboard refresh) read a fresh state.
        if (scopesChanged) s.fireScopesChanged()
    }

    override fun reset() {
        val s = TokenSelectorSettings.getInstance(project)
        scopesListModel.clear()
        s.scopes.forEach { scopesListModel.addElement(ScopeRow.from(it)) }
        detailBoundIndex = -1
        if (!scopesListModel.isEmpty) scopesList.selectedIndex = 0
        showDetail(scopesList.selectedValue)

        hoverCheckBox.isSelected = s.openOnHover
        hoverDelaySpinner.value = s.hoverDelayMs
        hoverDelaySpinner.isEnabled = s.openOnHover
        autocompleteCheckBox.isSelected = s.autocompleteEnabled
        valueCompletionCheckBox.isSelected = s.valueCompletionEnabled
        valueCompletionTriggerCombo.selectedItem = ValueCompletionTrigger.fromMinChars(s.valueCompletionMinChars)
        valueCompletionTriggerCombo.isEnabled = s.valueCompletionEnabled
        iconVariantCombo.selectedItem = IconVariant.fromName(s.iconVariantName)
        inspectVariableDeclarationsCheckBox.isSelected = s.inspectVariableDeclarations
        detectRuntimeInjectedCssVarsCheckBox.isSelected = s.detectRuntimeInjectedCssVars
    }

    override fun disposeUIResources() {
        rootPanel = null
    }

    // ─── Master panel ─────────────────────────────────────────────────────

    private fun buildMasterPanel(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(scopesList)
            .setAddAction {
                val newRow = ScopeRow.empty()
                scopesListModel.addElement(newRow)
                scopesList.selectedIndex = scopesListModel.size - 1
            }
            .setRemoveAction {
                val idx = scopesList.selectedIndex
                if (idx >= 0) {
                    scopesListModel.remove(idx)
                    if (!scopesListModel.isEmpty) {
                        scopesList.selectedIndex = (idx - 1).coerceAtLeast(0)
                    }
                }
            }
            .setMoveUpAction { moveSelectedScope(-1) }
            .setMoveDownAction { moveSelectedScope(+1) }
            .createPanel()

        val importLink = HyperlinkLabel("Import…").apply {
            toolTipText = "Load scopes from a JSON file (replace or merge with the current list)."
            addHyperlinkListener { importScopesFromFile() }
        }
        val exportLink = HyperlinkLabel("Export…").apply {
            toolTipText = "Save the current scopes to a JSON file you can share or back up."
            addHyperlinkListener { exportScopesToFile() }
        }
        val headerRow = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
            add(JBLabel("Scopes"), BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(importLink)
                add(exportLink)
            }, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyRight(8)
            add(headerRow, BorderLayout.NORTH)
            add(decorated, BorderLayout.CENTER)
        }
    }

    // ─── Import / Export ──────────────────────────────────────────────────

    private fun exportScopesToFile() {
        commitDetailToList()
        val scopes = currentScopes()
        if (scopes.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "There is no scope to export. Add one first.",
                "Export Token Flow Config",
            )
            return
        }
        // `FileSaverDescriptor` removed every public constructor that doesn't
        // take title/description in 2024.3+ — the no-arg ctor + chainable
        // `withTitle()` / `withDescription()` only became available past our
        // `sinceBuild = 242` baseline, and both string-arg overloads are now
        // flagged by the marketplace verifier as deprecated. We keep the
        // 3-arg form (vs. dropping the `"json"` extension hint) because the
        // verifier complaint count is identical either way and the save
        // dialog is materially more helpful with the extension filter.
        @Suppress("DEPRECATION")
        val descriptor = FileSaverDescriptor(
            "Export Token Flow Config",
            "Save scopes as a JSON file.",
            "json",
        )
        val baseDir = project.guessProjectDir()
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = saver.save(baseDir, "token-flow-scopes.json") ?: return
        val file = wrapper.file
        try {
            file.writeText(ScopeConfigIO.export(scopes))
            VfsUtil.findFileByIoFile(file, true)
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Could not write ${file.name}: ${e.message ?: e.javaClass.simpleName}",
                "Export Token Flow Config",
            )
        }
    }

    private fun importScopesFromFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Import Token Flow Config")
            .withDescription("Pick a JSON file previously exported from Token Flow.")
        FileChooser.chooseFile(descriptor, project, project.guessProjectDir()) { vf ->
            val incoming = try {
                ScopeConfigIO.import(String(vf.contentsToByteArray(), Charsets.UTF_8))
            } catch (e: ScopeConfigIO.ImportException) {
                Messages.showErrorDialog(project, e.message ?: "Invalid file.", "Import Token Flow Config")
                return@chooseFile
            } catch (e: Exception) {
                Messages.showErrorDialog(
                    project,
                    "Could not read ${vf.name}: ${e.message ?: e.javaClass.simpleName}",
                    "Import Token Flow Config",
                )
                return@chooseFile
            }
            applyImportedScopes(incoming, sourceName = vf.name)
        }
    }

    private fun applyImportedScopes(incoming: List<Scope>, sourceName: String) {
        commitDetailToList()
        val current = currentScopes()
        val mode = if (current.isEmpty()) {
            0 // replace, no question needed
        } else {
            Messages.showDialog(
                project,
                "Found ${incoming.size} scope(s) in $sourceName.\n\n" +
                    "Replace clears the current list. Merge keeps existing scopes and " +
                    "overwrites only those whose name matches (case-insensitive).",
                "Import Token Flow Config",
                arrayOf("Replace", "Merge", "Cancel"),
                0,
                Messages.getQuestionIcon(),
            )
        }
        val next = when (mode) {
            0 -> incoming
            1 -> mergeScopes(current, incoming)
            else -> return
        }
        scopesListModel.clear()
        next.forEach { scopesListModel.addElement(ScopeRow.from(it)) }
        detailBoundIndex = -1
        if (!scopesListModel.isEmpty) scopesList.selectedIndex = 0
        showDetail(scopesList.selectedValue)
    }

    private fun wireScopeMaster() {
        scopesList.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            commitDetailToList()
            showDetail(scopesList.selectedValue)
        }
    }

    // ─── Detail panel ─────────────────────────────────────────────────────

    private fun wireScopeDetail() {
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onDetailEdited()
            override fun removeUpdate(e: DocumentEvent) = onDetailEdited()
            override fun changedUpdate(e: DocumentEvent) = onDetailEdited()
        }
        scopeNameField.document.addDocumentListener(docListener)
        scopeRootField.document.addDocumentListener(docListener)

        scopeRootBrowse.addHyperlinkListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Scope Root Folder")
            FileChooser.chooseFile(descriptor, project, project.guessProjectDir()) { vf ->
                scopeRootField.text = relativeToProject(vf) ?: vf.path
            }
        }
    }

    private fun showDetail(row: ScopeRow?) {
        scopeDetailContainer.removeAll()
        if (row == null) {
            detailBoundIndex = -1
            scopeDetailContainer.add(JBLabel("Select or add a scope on the left.").apply {
                border = JBUI.Borders.empty(20)
            }, BorderLayout.CENTER)
        } else {
            scopeDetailContainer.add(buildDetailFor(row), BorderLayout.CENTER)
            detailBoundIndex = scopesList.selectedIndex
        }
        scopeDetailContainer.revalidate()
        scopeDetailContainer.repaint()
    }

    private fun buildDetailFor(row: ScopeRow): JComponent {
        suppressDetailListeners = true
        scopeNameField.text = row.name
        scopeRootField.text = row.rootPath
        scopePathsModel.clear()
        row.sourcePaths.forEach(scopePathsModel::addElement)
        scopeExcludedPathsModel.clear()
        row.excludedPaths.forEach(scopeExcludedPathsModel::addElement)
        scopeAnalysisExcludedPathsModel.clear()
        row.analysisExcludedPaths.forEach(scopeAnalysisExcludedPathsModel::addElement)
        scopeExternalPrefixesModel.clear()
        row.externalPrefixes.forEach(scopeExternalPrefixesModel::addElement)
        suppressDetailListeners = false


        val pathsDecorated = ToolbarDecorator.createDecorator(scopePathsList)
            .setAddAction { addPath(scopePathsModel) }
            .setRemoveAction { removeSelectedPaths(scopePathsList, scopePathsModel) }
            .disableUpDownActions()
            .createPanel()

        val excludedDecorated = ToolbarDecorator.createDecorator(scopeExcludedPathsList)
            .setAddAction { addPath(scopeExcludedPathsModel) }
            .setRemoveAction { removeSelectedPaths(scopeExcludedPathsList, scopeExcludedPathsModel) }
            .disableUpDownActions()
            .createPanel()

        val analysisExcludedDecorated = ToolbarDecorator.createDecorator(scopeAnalysisExcludedPathsList)
            .setAddAction { addPath(scopeAnalysisExcludedPathsModel) }
            .setRemoveAction { removeSelectedPaths(scopeAnalysisExcludedPathsList, scopeAnalysisExcludedPathsModel) }
            .disableUpDownActions()
            .createPanel()

        val externalDecorated = ToolbarDecorator.createDecorator(scopeExternalPrefixesList)
            .setAddAction { addExternalPrefix() }
            .setRemoveAction { removeSelectedPaths(scopeExternalPrefixesList, scopeExternalPrefixesModel) }
            .disableUpDownActions()
            .createPanel()


        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 0, 2, 6)
        }
        // Name
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        form.add(JBLabel("Name:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        form.add(scopeNameField, gbc)
        // Root
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        form.add(JBLabel("Root folder:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        val rootRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(scopeRootField, BorderLayout.CENTER)
            add(scopeRootBrowse, BorderLayout.EAST)
        }
        form.add(rootRow, gbc)
        // Hint
        gbc.gridx = 1; gbc.gridy = 2
        form.add(htmlMultiLine("<span style='color:gray'>Empty = common scope (always active).</span>"), gbc)

        fun tabBody(hint: String, listPanel: JComponent): JComponent =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(8, 4, 4, 4)
                add(htmlMultiLine("<span style='color:gray; font-size: 90%'>$hint</span>").apply {
                    border = JBUI.Borders.emptyBottom(6)
                }, BorderLayout.NORTH)
                add(listPanel, BorderLayout.CENTER)
            }

        val tabs = com.intellij.ui.components.JBTabbedPane().apply {
            addTab(
                "Sources",
                tabBody(
                    "Files and folders containing the tokens (Source of Truth).",
                    pathsDecorated,
                ),
            )
            addTab(
                "Whitelist",
                tabBody(
                    "Files whose variables are external/known — won't be flagged as broken refs.",
                    excludedDecorated,
                ),
            )
            addTab(
                "Excludes",
                tabBody(
                    "Folders/files inside the root to skip during analysis (e.g. unrelated sub-modules).",
                    analysisExcludedDecorated,
                ),
            )
            addTab(
                "External",
                externalTabBody(externalDecorated),
            )
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyLeft(8)
            add(form, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }


    }

    private fun onDetailEdited() {
        if (suppressDetailListeners) return
        val idx = detailBoundIndex
        if (idx < 0 || idx >= scopesListModel.size) return
        val row = scopesListModel.get(idx)
        row.name = scopeNameField.text.trim()
        row.rootPath = scopeRootField.text.trim()
        // Repaint the master list cell to reflect the new name immediately.
        scopesList.repaint(scopesList.getCellBounds(idx, idx) ?: return)
    }

    private fun commitDetailToList() {
        val idx = detailBoundIndex
        if (idx < 0 || idx >= scopesListModel.size) return
        val row = scopesListModel.get(idx)
        row.rootPath = scopeRootField.text.trim()
        row.sourcePaths.clear()
        for (i in 0 until scopePathsModel.size) row.sourcePaths.add(scopePathsModel.get(i))
        row.excludedPaths.clear()
        for (i in 0 until scopeExcludedPathsModel.size) row.excludedPaths.add(scopeExcludedPathsModel.get(i))
        row.analysisExcludedPaths.clear()
        for (i in 0 until scopeAnalysisExcludedPathsModel.size) row.analysisExcludedPaths.add(scopeAnalysisExcludedPathsModel.get(i))
        row.externalPrefixes.clear()
        for (i in 0 until scopeExternalPrefixesModel.size) row.externalPrefixes.add(scopeExternalPrefixesModel.get(i))
    }

    // ─── External prefixes (per-scope) ───────────────────────────────────

    /**
     * Asks the user for a prefix string. Normalises it so common typos —
     * forgetting the leading `--`, accidental trailing whitespace — still
     * produce a working entry. Duplicates inside the same scope are silently
     * dropped.
     */
    private fun addExternalPrefix() {
        val raw = Messages.showInputDialog(
            project,
            "Prefix to treat as a known external token (will match every var(--prefix-*) reference):",
            "Add External Prefix",
            Messages.getQuestionIcon(),
            "--",
            null,
        )?.trim() ?: return
        if (raw.isEmpty()) return
        val normalised = if (raw.startsWith("--")) raw else "--$raw"
        val existing = (0 until scopeExternalPrefixesModel.size)
            .map { scopeExternalPrefixesModel.get(it) }
            .toSet()
        if (normalised in existing) return
        scopeExternalPrefixesModel.addElement(normalised)
    }

    /**
     * "External" tab body: small explainer at the top + the path-list panel +
     * an auto-detect button that scans `package.json` for known frameworks
     * (PrimeNG, Ionic, Angular Material, …) and one-clicks their prefix into
     * the list. Plus a hint pointing at the Sources tab for the validation
     * path (frameworks shipping their CSS vars as a static stylesheet).
     */
    private fun externalTabBody(listPanel: JComponent): JComponent {
        val intro = htmlMultiLine(
            "<span style='color:gray; font-size: 90%'>" +
                "CSS custom-property prefixes injected at runtime by an external framework " +
                "(PrimeNG <code>--p-</code>, Ionic <code>--ion-</code>, Angular Material " +
                "<code>--mat-</code>, …). References matching one of these prefixes are " +
                "treated as known-external and won't be flagged as broken in the Analyser or " +
                "the Hardcoded Values panel. Use the <b>Sources</b> tab instead if you want " +
                "Token Flow to <i>validate</i> the variable names (works when the framework " +
                "ships its CSS vars as a static stylesheet — Ionic, Bootstrap, etc.)." +
                "</span>",
        ).apply {
            border = JBUI.Borders.emptyBottom(6)
        }

        val detectBtn = HyperlinkLabel("Auto-detect from package.json…").apply {
            toolTipText = "Scan the project for known frameworks (PrimeNG, Ionic, Material, …) " +
                "and add their CSS-var prefixes to this scope."
            addHyperlinkListener { runAutoDetectIntoCurrentScope() }
        }
        val detectRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(detectBtn)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 4, 4, 4)
            add(intro, BorderLayout.NORTH)
            add(listPanel, BorderLayout.CENTER)
            add(detectRow, BorderLayout.SOUTH)
        }
    }

    /**
     * Runs the framework detector and, for every framework whose prefix
     * isn't already in this scope's list, appends it. Surfaces a small
     * popup summarising what was added (or nothing-found state) so the
     * action never feels silent.
     */
    private fun runAutoDetectIntoCurrentScope() {
        val detections = FrameworkPrefixDetector.detect(project)
        if (detections.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No known framework detected in any package.json (looked for PrimeNG, Ionic, " +
                    "Angular Material, Material WC, Vuetify, Bootstrap, Quasar, Element Plus, " +
                    "Mantine, Carbon).",
                "Auto-detect External Prefixes",
            )
            return
        }
        val existing = (0 until scopeExternalPrefixesModel.size)
            .map { scopeExternalPrefixesModel.get(it) }
            .toSet()
        val added = mutableListOf<String>()
        for (det in detections) {
            if (det.framework.prefix in existing) continue
            scopeExternalPrefixesModel.addElement(det.framework.prefix)
            added += "${det.framework.displayName} (${det.framework.prefix})"
        }
        val msg = if (added.isEmpty()) {
            "All detected prefixes were already present:\n\n" +
                detections.joinToString("\n") { "• ${it.framework.displayName} (${it.framework.prefix})" }
        } else {
            "Added:\n\n" + added.joinToString("\n") { "• $it" }
        }
        Messages.showInfoMessage(project, msg, "Auto-detect External Prefixes")
    }

    // ─── Reorder ──────────────────────────────────────────────────────────

    /** Swap the selected scope with its neighbour ([delta] = -1 up, +1 down). */
    private fun moveSelectedScope(delta: Int) {
        val from = scopesList.selectedIndex
        val to = from + delta
        if (from < 0 || to < 0 || to >= scopesListModel.size) return
        // Persist edits from the detail fields before reordering so we don't
        // lose what the user just typed into the row about to move.
        commitDetailToList()
        val row = scopesListModel.get(from)
        scopesListModel.remove(from)
        scopesListModel.add(to, row)
        scopesList.selectedIndex = to
        detailBoundIndex = to
    }


    private fun addPath(model: DefaultListModel<String>) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
            .withTitle("Add Path")
        FileChooser.chooseFile(descriptor, project, project.guessProjectDir()) { vf ->
            val rel = relativeToProject(vf) ?: vf.path
            if ((0 until model.size).none { model.get(it) == rel }) {
                model.addElement(rel)
            }
        }
    }

    private fun removeSelectedPaths(list: JBList<String>, model: DefaultListModel<String>) {
        list.selectedValuesList.toList().forEach(model::removeElement)
    }


    // ─── Trigger section ──────────────────────────────────────────────────

    private fun buildTriggerSection(): JComponent {
        hoverCheckBox.addActionListener { hoverDelaySpinner.isEnabled = hoverCheckBox.isSelected }
        val hoverDelayRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            border = JBEmptyBorder(0, JBUI.scale(20), 0, 0)
            add(JBLabel("Delay:"))
            add(hoverDelaySpinner)
            add(JBLabel("ms"))
        }
        valueCompletionCheckBox.addActionListener {
            valueCompletionTriggerCombo.isEnabled = valueCompletionCheckBox.isSelected
        }
        val valueCompletionTriggerRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            border = JBEmptyBorder(0, JBUI.scale(20), 0, 0)
            add(JBLabel("Show suggestions:"))
            add(valueCompletionTriggerCombo)
        }
        val alternativesShortcutLink = HyperlinkLabel(
            "Customize \"Show Token Alternatives\" shortcut (Alt+T by default)…"
        ).apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            addHyperlinkListener { openKeymapFor("DesignTokenSelector.ShowAlternatives") }
        }
        val gotoShortcutLink = HyperlinkLabel(
            "Customize \"Go to Token Declaration\" shortcut (Alt+Shift+T / Alt+Shift+Click by default)…"
        ).apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            addHyperlinkListener { openKeymapFor("DesignTokenSelector.GoToDeclaration") }
        }

        val sectionAlternatives = sectionLabel("Hover info popup")
        val sectionCompletion = sectionLabel("Code completion")
        val sectionShortcut = sectionLabel("Keyboard")
        val sectionAppearance = sectionLabel("Appearance")
        val iconRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            add(JBLabel("Tool window icon:"))
            add(iconVariantCombo)
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(14, 4, 4, 4)
            add(sectionAlternatives)
            add(hoverCheckBox.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
            add(hoverDelayRow)
            add(verticalSpacer())
            add(sectionCompletion)
            add(autocompleteCheckBox.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
            add(valueCompletionCheckBox.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
            add(valueCompletionTriggerRow)
            add(verticalSpacer())
            add(sectionShortcut)
            add(alternativesShortcutLink)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
            add(gotoShortcutLink)
            add(verticalSpacer())
            add(sectionAppearance)
            add(iconRow)
            add(JPanel().apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })  // glue
        }
    }

    private fun buildAnalyserTab(): JComponent {
        val sectionHardcoded = sectionLabel("Hardcoded values detection")
        val sectionBrokenRefs = sectionLabel("Broken references")

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(14, 14, 4, 4)

            add(sectionHardcoded)
            add(inspectVariableDeclarationsCheckBox.apply {
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                toolTipText = "When unchecked (default), literal values assigned to variables are ignored, as they are usually the tokens' own definitions."
            })
            add(htmlMultiLine("<span style='color:gray'>Uncheck this to avoid flagging your own token definitions in SCSS/CSS variable files.</span>").apply {
                border = JBUI.Borders.emptyLeft(26)
            })

            add(verticalSpacer())
            add(sectionBrokenRefs)
            add(detectRuntimeInjectedCssVarsCheckBox.apply {
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                toolTipText = "When checked (default), scans .ts/.tsx/.js/.jsx/.html/.vue files for CSS variables declared at runtime (Angular [style.--x], React/Vue inline styles, setProperty calls) and stops flagging matching var(--x) references as broken. A fallback expression alone (var(--x, inherit)) is not enough — a missing variable is still flagged even when a default is supplied."
            })
            add(htmlMultiLine("<span style='color:gray'>Covers Angular host bindings, React/Vue inline styles and vanilla <code>setProperty</code>. A <code>var(--x, fallback)</code> with no matching declaration anywhere is still reported.</span>").apply {
                border = JBUI.Borders.emptyLeft(26)
            })

            add(verticalSpacer())
            add(JPanel().apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT }) // glue
        }
    }

    private fun sectionLabel(text: String): JComponent = JBLabel(
        "<html><b>$text</b></html>"
    ).apply {
        border = JBUI.Borders.empty(0, 0, 6, 0)
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
    }

    private fun verticalSpacer(): java.awt.Component = javax.swing.Box.createVerticalStrut(JBUI.scale(14))

    /**
     * Opens the IDE Keymap dialog and pre-filters it on [actionId] so the
     * user lands directly on the row they want to edit.
     */
    private fun openKeymapFor(actionId: String) {
        try {
            val panel = com.intellij.openapi.keymap.impl.ui.KeymapPanel()
            ShowSettingsUtil.getInstance().editConfigurable(project, panel) {
                panel.selectAction(actionId)
            }
            return
        } catch (_: Throwable) {
            // Fall back to the generic Keymap pane if the internal API is
            // missing on this IDE build.
            openKeymapSettings()
        }
    }

    private fun openKeymapSettings() {
        val util = ShowSettingsUtil.getInstance()
        for (id in listOf("preferences.keymap", "Keymap", "preferences.keymap.shortcuts")) {
            try {
                util.showSettingsDialog(project, id)
                return
            } catch (_: Throwable) {
                // try next
            }
        }
        util.showSettingsDialog(project, "Keymap")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun currentScopes(): List<Scope> =
        (0 until scopesListModel.size).map { scopesListModel.get(it).toScope() }

    private fun sameScopes(a: List<Scope>, b: List<Scope>): Boolean {
        if (a.size != b.size) return false
        return a.zip(b).all { (x, y) ->
            x.name == y.name && x.rootPath == y.rootPath &&
                x.sourcePaths == y.sourcePaths && x.excludedPaths == y.excludedPaths &&
                x.analysisExcludedPaths == y.analysisExcludedPaths &&
                x.externalPrefixes == y.externalPrefixes
        }
    }

    private fun relativeToProject(vf: VirtualFile): String? {
        val basePath = project.basePath ?: return null
        val path = vf.path
        if (!path.startsWith(basePath)) return null
        return path.substring(basePath.length).trimStart('/')
    }
}

// ─── Mutable mirror of `Scope` used by the UI ────────────────────────────

internal class ScopeRow(
    var name: String,
    var rootPath: String,
    var sourcePaths: MutableList<String>,
    var excludedPaths: MutableList<String> = mutableListOf(),
    var analysisExcludedPaths: MutableList<String> = mutableListOf(),
    var externalPrefixes: MutableList<String> = mutableListOf(),
) {
    fun toScope(): Scope = Scope(
        name = name,
        rootPath = rootPath,
        sourcePaths = sourcePaths.toList(),
        excludedPaths = excludedPaths.toList(),
        analysisExcludedPaths = analysisExcludedPaths.toList(),
        externalPrefixes = externalPrefixes.toList(),
    )

    companion object {
        fun from(scope: Scope): ScopeRow = ScopeRow(
            scope.name,
            scope.rootPath,
            scope.sourcePaths.toMutableList(),
            scope.excludedPaths.toMutableList(),
            scope.analysisExcludedPaths.toMutableList(),
            scope.externalPrefixes.toMutableList(),
        )

        fun empty(): ScopeRow = ScopeRow(
            "New scope", "", mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf(),
        )
    }
}


private class ScopeListRenderer : javax.swing.ListCellRenderer<ScopeRow> {
    private val component = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }
    override fun getListCellRendererComponent(
        list: javax.swing.JList<out ScopeRow>,
        value: ScopeRow,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): java.awt.Component {
        val display = if (value.name.isBlank()) "(unnamed scope)" else value.name
        val root = if (value.rootPath.isBlank()) "common" else value.rootPath
        component.text = "<html>$display <span style='color:gray'>· $root</span></html>"
        component.background = if (isSelected) list.selectionBackground else list.background
        component.foreground = if (isSelected) list.selectionForeground else list.foreground
        component.isOpaque = true
        return component
    }
}
