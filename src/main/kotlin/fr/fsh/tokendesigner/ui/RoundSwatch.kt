package fr.fsh.tokendesigner.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

/**
 * Round colour swatch with a fixed (locked) size. Always paints a perfect circle
 * centered in the component, regardless of how layout managers might want to
 * stretch it. Used in:
 *  - [TokenCellRenderer] (token rows in the picker / dashboard)
 *  - [HardcodedValuesPanel] (literal + suggestion columns)
 *
 * `glyph` lets non-colour categories show a small text marker (↔, T, ⏱, …)
 * instead of an empty circle.
 */
class RoundSwatch(diameterPx: Int = 16) : JPanel() {

    var color: Color? = null
        set(value) { field = value; repaint() }

    var glyph: String? = null
        set(value) { field = value; repaint() }

    var glyphColor: Color? = null
        set(value) { field = value; repaint() }


    init {
        val d = JBUI.scale(diameterPx)
        val size = Dimension(d, d)
        preferredSize = size
        minimumSize = size
        maximumSize = size
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            val side = minOf(width, height) - 2
            val x = (width - side) / 2
            val y = (height - side) / 2

            color?.let {
                g2.color = it
                g2.fillOval(x, y, side, side)
            }
            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1f)
            g2.drawOval(x, y, side - 1, side - 1)

            glyph?.let { text ->
                g2.color = glyphColor ?: JBColor.foreground()

                g2.font = font.deriveFont(JBUI.scale(10).toFloat())
                val fm = g2.fontMetrics
                val tx = (width - fm.stringWidth(text)) / 2
                val ty = (height + fm.ascent - fm.descent) / 2 - 1
                g2.drawString(text, tx, ty)
            }
        } finally {
            g2.dispose()
        }
    }
}
