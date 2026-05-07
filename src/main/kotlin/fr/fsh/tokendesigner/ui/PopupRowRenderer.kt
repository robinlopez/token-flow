package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * Dispatches between [TokenCellRenderer] for actual tokens and a section heading
 * for [SeparatorPopupRow]. Collapsible separators get a chevron icon.
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
        is TokenPopupRow -> tokenRenderer.getListCellRendererComponent(
            @Suppress("UNCHECKED_CAST") (list as JList<out fr.fsh.tokendesigner.model.DesignToken>),
            value.token,
            index,
            isSelected,
            cellHasFocus,
        )
        is SeparatorPopupRow -> separatorRenderer.configure(value, list.background, index == 0)
    }

    /**
     * Heading row: caption with a thin divider on top, optional chevron icon
     * to mark collapsible sections (▶ collapsed / ▼ expanded).
     */
    private class SeparatorRowComponent : JPanel(BorderLayout()) {
        private val chevron = JLabel().apply {
            border = JBUI.Borders.empty(0, 8, 0, 4)
        }
        private val label = JLabel().apply {
            foreground = JBColor.GRAY
            font = JBFont.small()
            border = JBUI.Borders.empty(4, 2, 2, 10)
            horizontalAlignment = SwingConstants.LEFT
        }

        init {
            add(chevron, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
            // Width follows viewport; height is fixed.
            preferredSize = Dimension(0, JBUI.scale(SEPARATOR_HEIGHT))
        }

        fun configure(row: SeparatorPopupRow, listBg: java.awt.Color, isFirst: Boolean): SeparatorRowComponent {
            label.text = row.title.uppercase()
            background = listBg
            border = if (isFirst) JBUI.Borders.empty() else JBUI.Borders.customLineTop(JBColor.border())
            chevron.icon = when {
                !row.collapsible -> null
                row.collapsed -> AllIcons.General.ArrowRight
                else -> AllIcons.General.ArrowDown
            }
            return this
        }
    }

    private companion object {
        const val SEPARATOR_HEIGHT = 24
    }
}
