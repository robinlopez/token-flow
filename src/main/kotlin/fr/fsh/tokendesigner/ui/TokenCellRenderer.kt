package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.model.TokenKind
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

class TokenCellRenderer(
    private val showLocateProvider: ((Int) -> Boolean)? = null,
) : ListCellRenderer<DesignToken> {

    private companion object {
        const val CELL_HEIGHT = 28
        const val LOCATE_ICON_AREA = 28
    }

    private val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
    private val swatch = RoundSwatch(diameterPx = 18)
    private val nameLabel = JLabel()
    private val helperBadge = JLabel().apply {
        // "ƒ" is the conventional mark for a function in most IDEs and
        // immediately signals that the entry is callable rather than a flat
        // value. Drawn in a softer accent colour to stay subordinate to the
        // token name.
        text = "ƒ"
        foreground = JBColor.namedColor(
            "Plugins.tagForeground",
            JBColor(0x8B5CF6, 0xC4B5FD),
        )
        font = font.deriveFont(java.awt.Font.BOLD)
        border = JBUI.Borders.emptyRight(6)
        toolTipText = "Callable helper — inserts the function call expression"
    }
    private val variantBadge = JLabel().apply {
        foreground = JBColor.namedColor("Component.focusColor", JBColor.BLUE)
        font = font.deriveFont(font.size2D - 2f)
        border = JBUI.Borders.emptyLeft(6)
    }
    private val valueLabel = JLabel().apply {
        foreground = JBColor.GRAY
        horizontalAlignment = SwingConstants.RIGHT
    }
    private val locateLabel = JLabel(AllIcons.General.Locate).apply {
        toolTipText = "Open source file"
        border = JBUI.Borders.emptyLeft(6)
        preferredSize = Dimension(JBUI.scale(LOCATE_ICON_AREA), JBUI.scale(CELL_HEIGHT))
        horizontalAlignment = SwingConstants.CENTER
    }
    private val rightPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(valueLabel, BorderLayout.CENTER)
    }

    init {
        panel.border = JBUI.Borders.empty(4, 8)
        val nameRow = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            add(helperBadge, BorderLayout.WEST)
            add(nameLabel, BorderLayout.CENTER)
            add(variantBadge, BorderLayout.EAST)
        }
        val left = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(swatch, BorderLayout.WEST)
            add(nameRow, BorderLayout.CENTER)
        }
        panel.add(left, BorderLayout.CENTER)
        panel.add(rightPanel, BorderLayout.EAST)
        panel.preferredSize = Dimension(0, JBUI.scale(CELL_HEIGHT))
    }

    override fun getListCellRendererComponent(
        list: JList<out DesignToken>,
        token: DesignToken,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        nameLabel.text = token.name
        valueLabel.text = token.resolvedValue
        // Helper tokens (`spacing`, `radius(value)`) carry a ƒ badge so users
        // distinguish callable helpers from flat-value tokens at a glance.
        helperBadge.isVisible = token.kind == TokenKind.JS_RUNTIME_FUNCTION

        if (token.variants.isEmpty()) {
            variantBadge.text = ""
            variantBadge.toolTipText = null
        } else {
            variantBadge.text = "+${token.variants.size}"
            variantBadge.toolTipText = VariantTableHtml.build(token)
        }

        swatch.color = if (token.category == TokenCategory.COLOR) ColorParser.parse(token.resolvedValue) else null
        swatch.glyph = glyphFor(token.category)

        rightPanel.removeAll()
        rightPanel.add(valueLabel, BorderLayout.CENTER)
        if (showLocateProvider?.invoke(index) == true) {
            rightPanel.add(locateLabel, BorderLayout.EAST)
        }

        if (isSelected) {
            panel.background = list.selectionBackground
            nameLabel.foreground = list.selectionForeground
            valueLabel.foreground = list.selectionForeground
        } else {
            panel.background = list.background
            nameLabel.foreground = list.foreground
            valueLabel.foreground = JBColor.GRAY
        }
        panel.isOpaque = true
        return panel
    }

    private fun glyphFor(category: TokenCategory): String? = CategoryGlyphs.glyphFor(category)
}

internal const val LOCATE_ICON_HIT_AREA: Int = 32
