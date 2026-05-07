package fr.fsh.tokendesigner.ui.charts

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * Single horizontal progress bar with a label and a `value/max` caption. Used
 * inside sub-score cards and per-file coverage rows. Colour band tracks the
 * usual red/amber/green threshold so a quick glance reveals the state.
 */
class MiniBar(
    val label: String,
    var value: Int,
    var max: Int = 100,
    private val rightCaption: String? = null,
) : JComponent() {

    init {
        preferredSize = Dimension(JBUI.scale(220), JBUI.scale(34))
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            val barHeight = JBUI.scale(8)

            // Compact mode: no top label, the bar takes the whole height.
            val compact = label.isEmpty() && rightCaption == null
            val barY = if (compact) (height - barHeight) / 2 else JBUI.scale(16)

            if (!compact) {
                g2.font = JBFont.small()
                g2.color = JBColor.foreground()
                g2.drawString(label, 0, JBUI.scale(11))
                rightCaption?.let {
                    val fm = g2.fontMetrics
                    g2.color = JBColor.GRAY
                    g2.drawString(it, width - fm.stringWidth(it), JBUI.scale(11))
                }
            }

            g2.color = JBColor.namedColor("Component.borderColor", JBColor.LIGHT_GRAY)
            g2.fillRoundRect(0, barY, width, barHeight, arc, arc)

            val ratio = if (max <= 0) 0.0 else value.toDouble() / max
            val filled = (width * ratio).toInt().coerceAtLeast(0)
            g2.color = bandColor()
            g2.fillRoundRect(0, barY, filled, barHeight, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    private fun bandColor(): Color {
        val pct = if (max <= 0) 0 else (100 * value / max)
        return when {
            pct >= 75 -> JBColor(Color(0x4CAF50), Color(0x6FCF73))
            pct >= 50 -> JBColor(Color(0xF9A825), Color(0xFFB300))
            else -> JBColor(Color(0xE53935), Color(0xEF5350))
        }
    }
}
