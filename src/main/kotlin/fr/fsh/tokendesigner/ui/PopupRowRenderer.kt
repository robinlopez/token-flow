package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * Dispatches between [TokenCellRenderer] for actual tokens and section headings
 * for [SeparatorPopupRow]. Three header flavours live here — category (level 0,
 * collapsible chevron + count on the right), family (level 1), and sub-family
 * (level 2, deepest indent and quietest emphasis).
 */
class PopupRowRenderer(
    showLocateProvider: ((Int) -> Boolean)? = null,
) : ListCellRenderer<PopupRow> {

    private val tokenRenderer = TokenCellRenderer(showLocateProvider)
    private val separatorRenderer = SeparatorRowComponent()

    override fun getListCellRendererComponent(
        list: JList<out PopupRow>,
        value: PopupRow,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component = when (value) {
        is TokenPopupRow -> {
            val component = tokenRenderer.getListCellRendererComponent(
                @Suppress("UNCHECKED_CAST") (list as JList<out fr.fsh.tokendesigner.model.DesignToken>),
                value.token,
                index,
                isSelected,
                cellHasFocus,
            ) as javax.swing.JComponent
            // Indent tokens to align with their family / sub-family header label.
            component.border = JBUI.Borders.empty(4, 8 + INDENT_PX * value.indentLevel, 4, 8)
            component
        }
        is SeparatorPopupRow -> separatorRenderer.configure(value, list.background, index == 0)
    }

    /**
     * Heading row. Three visual flavours selected by [SeparatorPopupRow.level]:
     *  - **0 (category)** : bold uppercase title left, optional chevron, count
     *    right-aligned, hairline divider on top.
     *  - **1 (family)**   : smaller title indented to the chevron column, no
     *    count, minimal top padding — bridges the category and sub-family.
     *  - **2 (sub-family)**: italic small caps, dim color, indented further so
     *    the tokens beneath line up under the label, not the chevron.
     */
    internal class SeparatorRowComponent : JPanel(BorderLayout()) {
        private val chevron = JLabel().apply {
            border = JBUI.Borders.empty(0, 8, 0, 4)
        }
        private val label = JLabel().apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        // Count badge: bolder + parenthesised + tinted with the accent foreground
        // so it doesn't blend with the resolved-value gray used on token rows.
        private val count = JLabel().apply {
            foreground = COUNT_FG
            font = JBFont.small().deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(0, 8, 0, 12)
            horizontalAlignment = SwingConstants.RIGHT
        }

        init {
            add(chevron, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
            add(count, BorderLayout.EAST)
        }

        fun configure(row: SeparatorPopupRow, listBg: java.awt.Color, isFirst: Boolean): SeparatorRowComponent {
            background = listBg
            return when (row.level) {
                0 -> configureCategory(row, isFirst)
                1 -> configureFamily(row)
                else -> configureSubfamily(row)
            }
        }

        private fun configureCategory(row: SeparatorPopupRow, isFirst: Boolean): SeparatorRowComponent {
            label.text = row.title.uppercase()
            // Slightly dimmed vs full foreground so it doesn't compete with
            // the token names (full-white in dark themes).
            label.foreground = CATEGORY_FG
            label.font = JBFont.label().deriveFont(Font.BOLD)
            label.border = JBUI.Borders.empty(6, 2, 4, 10)
            chevron.icon = when {
                !row.collapsible -> null
                row.collapsed -> AllIcons.General.ArrowRight
                else -> AllIcons.General.ArrowDown
            }
            border = if (isFirst) JBUI.Borders.empty() else JBUI.Borders.customLineTop(JBColor.border())
            count.isVisible = row.count != null
            // Parentheses + bold + accent color = reads as a count, not a value.
            count.text = row.count?.let { "($it)" }.orEmpty()
            preferredSize = Dimension(0, JBUI.scale(CATEGORY_HEIGHT))
            return this
        }

        private fun configureFamily(row: SeparatorPopupRow): SeparatorRowComponent {
            label.text = row.title.uppercase()
            label.foreground = FAMILY_FG
            label.font = JBFont.small().deriveFont(Font.BOLD)
            label.border = JBUI.Borders.empty(6, INDENT_PX, 2, 10)
            chevron.icon = null
            count.isVisible = false
            border = JBUI.Borders.empty()
            preferredSize = Dimension(0, JBUI.scale(FAMILY_HEIGHT))
            return this
        }

        private fun configureSubfamily(row: SeparatorPopupRow): SeparatorRowComponent {
            label.text = row.title.uppercase()
            label.foreground = SUBFAMILY_FG
            label.font = JBFont.small().deriveFont(Font.ITALIC, JBFont.small().size2D - 1f)
            label.border = JBUI.Borders.empty(4, INDENT_PX * 2, 1, 10)
            chevron.icon = null
            count.isVisible = false
            border = JBUI.Borders.empty()
            preferredSize = Dimension(0, JBUI.scale(SUBFAMILY_HEIGHT))
            return this
        }
    }

    internal companion object {
        const val CATEGORY_HEIGHT = 26
        const val FAMILY_HEIGHT = 22
        const val SUBFAMILY_HEIGHT = 18
        /** Width of one indent step (px) shared between headers and token rows. */
        const val INDENT_PX = 16

        // Header foregrounds chosen to step down in contrast as the level
        // deepens. Category is the most prominent, sub-family the quietest —
        // never as loud as the token names (full white in dark themes).
        private val CATEGORY_FG = JBColor(java.awt.Color(0x4A4A4A), java.awt.Color(0xC9CDD2))
        private val FAMILY_FG = JBColor(java.awt.Color(0x6B6B6B), java.awt.Color(0xA0A6AC))
        private val SUBFAMILY_FG = JBColor(java.awt.Color(0x9A9A9A), java.awt.Color(0x787E84))
        // Count tint = accent foreground used elsewhere for badges (variant
        // counter, helper ƒ glyph). Visually distinct from the resolved-value
        // gray so users don't read it as a token value.
        private val COUNT_FG = JBColor.namedColor(
            "Plugins.tagForeground",
            JBColor(java.awt.Color(0x8B5CF6), java.awt.Color(0xC4B5FD)),
        )
    }
}
