package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import fr.fsh.tokendesigner.scanner.CssVarOccurrence
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Popup that lists every place a CSS custom property is *declared*
 * outside the registered token sources — Angular host bindings, React /
 * Vue inline styles, vanilla `setProperty`, contextual CSS overrides.
 *
 * Surfaces under Alt+T when the caret sits on a `var(--x)` whose `--x`
 * has no canonical token entry but is declared at least once by the
 * codebase. Replaces the historical "No alternatives found." message
 * with an actionable list: each row shows
 *  `[swatch] selector · value · file:line`
 * and clicking it navigates to the declaration site.
 */
object CssVarDeclarationsPopup {

    fun show(
        project: Project,
        varName: String,
        occurrences: List<CssVarOccurrence>,
        anchorScreenLocation: Point?,
        editor: com.intellij.openapi.editor.Editor,
    ): JBPopup {
        // Stable sort: static CSS declarations first (most actionable —
        // they expose the configured value), runtime injections after.
        // Then by file, then by line.
        val sorted = occurrences.sortedWith(
            compareBy({ it.isRuntime }, { it.filePath }, { it.line }),
        )

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(sorted)
            .setRenderer(DeclarationRenderer(project))
            .setTitle("Declarations of $varName — ${sorted.size} site(s)")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setNamerForFiltering { row ->
                listOfNotNull(
                    row.value,
                    row.selector,
                    relativePath(project, row.filePath),
                ).joinToString(" ")
            }
            .setItemChosenCallback { occ -> navigate(project, occ) }
            .setMinSize(JBUI.size(580, 280))
            .setDimensionServiceKey("DesignTokenSelector.CssVarDeclarationsPopup")
            .createPopup()

        if (anchorScreenLocation != null) {
            val component = editor.contentComponent
            val local = Point(anchorScreenLocation).also {
                javax.swing.SwingUtilities.convertPointFromScreen(it, component)
            }
            popup.show(RelativePoint(component, local))
        } else {
            popup.showInBestPositionFor(editor)
        }
        return popup
    }

    private fun navigate(project: Project, occ: CssVarOccurrence) {
        val vf = LocalFileSystem.getInstance().findFileByPath(occ.filePath) ?: return
        OpenFileDescriptor(project, vf, occ.offset).navigate(true)
    }

    private fun relativePath(project: Project, absPath: String): String {
        val base = project.basePath ?: return absPath
        return if (absPath.startsWith("$base/")) absPath.removePrefix("$base/") else absPath
    }

    /**
     * One row = one declaration site. Layout :
     *  ┌────┬────────────────────────────────────────────────┬───────┐
     *  │ ●  │ <selector> · <value>                           │ file  │
     *  │    │                                                │ :line │
     *  └────┴────────────────────────────────────────────────┴───────┘
     *
     *  - swatch on the left for color values (shape mirrors the Library
     *    panel's `RoundSwatch`),
     *  - center column: selector (bold) when known + raw value,
     *  - right column: `path/to/file.scss:42` in muted text.
     *
     * The renderer reuses one instance — Swing's `ListCellRenderer`
     * contract — and reconfigures its labels on every call.
     */
    private class DeclarationRenderer(private val project: Project) : ListCellRenderer<CssVarOccurrence> {

        private val swatch = RoundSwatch(14)
        private val swatchHolder = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            add(swatch)
        }
        private val selectorLabel = JLabel().apply {
            font = JBFont.label().deriveFont(Font.BOLD)
        }
        private val valueLabel = JLabel().apply {
            font = JBFont.label()
            border = JBUI.Borders.emptyLeft(8)
        }
        private val locationLabel = JLabel().apply {
            font = JBFont.small()
            foreground = MUTED_FG
            horizontalAlignment = JLabel.RIGHT
            border = JBUI.Borders.emptyLeft(12)
            icon = AllIcons.Nodes.Folder
            iconTextGap = JBUI.scale(4)
        }

        private val centerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(selectorLabel, BorderLayout.WEST)
            add(valueLabel, BorderLayout.CENTER)
        }

        private val root = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 10)
            add(swatchHolder, BorderLayout.WEST)
            add(centerPanel, BorderLayout.CENTER)
            add(locationLabel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out CssVarOccurrence>,
            value: CssVarOccurrence,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            root.background = if (isSelected) list.selectionBackground else list.background
            val fg = if (isSelected) list.selectionForeground else list.foreground
            selectorLabel.foreground = fg
            valueLabel.foreground = fg
            locationLabel.foreground = if (isSelected) list.selectionForeground else MUTED_FG
            root.isOpaque = true

            // Selector column. Runtime occurrences don't carry one — we
            // fall back to a short tag so the row still reads as
            // "something declares this here", not "blank".
            selectorLabel.text = when {
                !value.selector.isNullOrBlank() -> value.selector
                value.isRuntime -> RUNTIME_TAG
                else -> NO_SELECTOR_TAG
            }

            // Value column. Empty when the runtime form carries no
            // parseable literal (bare host-binding key); display a hint.
            val displayedValue = value.value.ifBlank { if (value.isRuntime) "<runtime binding>" else "" }
            valueLabel.text = if (displayedValue.isNotEmpty()) "· $displayedValue" else ""

            // Location column. Project-relative path + 1-based line.
            locationLabel.text = "${relativePath(project, value.filePath)}:${value.line}"

            // Swatch — only relevant when the value parses as a color.
            // We do the parse on every render which is cheap (regex on a
            // short string); caching would only matter for very large
            // popup lists.
            val color = ColorParser.parse(value.value)
            if (color != null) {
                swatch.color = color
                swatch.glyph = null
                swatch.isVisible = true
            } else {
                // Keep the column reserved so columns line up even when
                // most rows have no swatch.
                swatch.color = TRANSPARENT
                swatch.glyph = null
                swatch.isVisible = true
            }
            return root
        }

        companion object {
            private const val RUNTIME_TAG = "[runtime]"
            private const val NO_SELECTOR_TAG = "<root>"
            private val MUTED_FG = JBColor(Color(0x6C6C6C), Color(0x8B9097))
            private val TRANSPARENT: Color = Color(0, 0, 0, 0)
        }
    }
}
