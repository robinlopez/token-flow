package fr.fsh.tokendesigner.ui.charts

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * Circular gauge that renders a 0..100 score plus a letter grade (A→F) in the
 * middle. Colour shifts from red → amber → green based on the score band.
 */
class ScoreGauge(diameterPx: Int = 140) : JComponent() {

    private val side = JBUI.scale(diameterPx)

    var score: Int = 0
        set(value) { field = value.coerceIn(0, 100); repaint() }

    var grade: String = "—"
        set(value) { field = value; repaint() }

    init {
        val d = Dimension(side, side)
        preferredSize = d
        minimumSize = d
        maximumSize = d
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Layouts (BorderLayout.WEST etc.) are free to stretch us
            // vertically; compute everything from a square box centered in the
            // current bounds so the arc + text stay visually centered no
            // matter the actual width/height we receive.
            val pad = JBUI.scale(8)
            val box = minOf(width, height)
            val originX = (width - box) / 2
            val originY = (height - box) / 2
            val arcSize = box - 2 * pad
            val arcX = originX + pad
            val arcY = originY + pad
            val centerX = originX + box / 2
            val centerY = originY + box / 2
            val stroke = JBUI.scale(10).toFloat()

            g2.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            // Track ring.
            g2.color = JBColor.namedColor("Component.borderColor", JBColor.LIGHT_GRAY)
            g2.drawArc(arcX, arcY, arcSize, arcSize, 90, -360)

            // Score arc.
            val sweep = -(360 * score / 100)
            g2.color = scoreColor()
            g2.drawArc(arcX, arcY, arcSize, arcSize, 90, sweep)

            // Grade letter — big and bold in the middle of the square box.
            g2.color = JBColor.foreground()
            g2.font = JBFont.label().deriveFont(java.awt.Font.BOLD, JBUI.scale(38).toFloat())
            val fmGrade = g2.fontMetrics
            val gradeBaselineY = centerY + (fmGrade.ascent - fmGrade.descent) / 2 - JBUI.scale(4)
            g2.drawString(grade, centerX - fmGrade.stringWidth(grade) / 2, gradeBaselineY)

            // Score number, smaller, just below.
            g2.font = JBFont.small()
            g2.color = JBColor.GRAY
            val scoreLabel = "$score / 100"
            val fmScore = g2.fontMetrics
            g2.drawString(
                scoreLabel,
                centerX - fmScore.stringWidth(scoreLabel) / 2,
                gradeBaselineY + JBUI.scale(16),
            )
        } finally {
            g2.dispose()
        }
    }

    private fun scoreColor(): Color = when {
        score >= 75 -> JBColor(Color(0x4CAF50), Color(0x6FCF73))
        score >= 50 -> JBColor(Color(0xF9A825), Color(0xFFB300))
        else -> JBColor(Color(0xE53935), Color(0xEF5350))
    }
}
