package fr.fsh.tokendesigner.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JToggleButton

/**
 * Pill-shaped toggle button used as a filter chip in the dashboard.
 *
 *  - Idle: subtle muted background, regular foreground
 *  - Hover (rollover): slightly tinted background
 *  - Active (selected): accent background with high-contrast foreground
 */
class FilterChip(label: String) : JToggleButton(label) {

    init {
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        margin = JBUI.insets(0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(font.size2D - 1f)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(16)
            val bg = backgroundColor()
            g2.color = bg
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            // 1px border for definition (especially in idle state on dark themes)
            g2.color = borderColor()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = foregroundColor()
            val fm = g2.fontMetrics
            val tx = (width - fm.stringWidth(text)) / 2
            val ty = (height + fm.ascent - fm.descent) / 2 - 1
            g2.drawString(text, tx, ty)
        } finally {
            g2.dispose()
        }
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val w = fm.stringWidth(text) + JBUI.scale(22)
        val h = fm.height + JBUI.scale(8)
        return Dimension(w, h)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    private fun backgroundColor(): Color = when {
        isSelected -> ACTIVE_BG
        model.isRollover -> HOVER_BG
        else -> IDLE_BG
    }

    private fun foregroundColor(): Color = when {
        isSelected -> ACTIVE_FG
        else -> JBColor.foreground()
    }

    private fun borderColor(): Color = when {
        isSelected -> ACTIVE_BG
        else -> JBColor.border()
    }

    companion object {
        private val ACTIVE_BG = JBColor.namedColor(
            "Component.focusColor",
            JBColor(Color(0x3574F0), Color(0x375FAD)),
        )
        private val ACTIVE_FG = JBColor(Color.WHITE, Color.WHITE)
        private val IDLE_BG = JBColor.namedColor(
            "Tag.background",
            JBColor(Color(0xEDEDED), Color(0x3D3F41)),
        )
        private val HOVER_BG = JBColor.namedColor(
            "Tag.hoverBackground",
            JBColor(Color(0xDDDDDD), Color(0x4D4F51)),
        )
    }
}
