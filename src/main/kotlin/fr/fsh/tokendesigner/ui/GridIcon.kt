package fr.fsh.tokendesigner.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

class GridIcon(private val size: Int = 16) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        // Standard action icon color
        g2.color = JBColor.namedColor("MenuItem.foreground", JBColor(0x6E6E6E, 0xAFB1B3))
        
        val w = getIconWidth()
        val h = getIconHeight()
        
        val boxSize = JBUI.scale(6)
        val arc = JBUI.scale(3)
        
        val gapX = (w - boxSize * 2) / 3
        val gapY = (h - boxSize * 2) / 3
        
        // Top-left
        g2.fillRoundRect(x + gapX, y + gapY, boxSize, boxSize, arc, arc)
        // Top-right
        g2.fillRoundRect(x + gapX * 2 + boxSize, y + gapY, boxSize, boxSize, arc, arc)
        // Bottom-left
        g2.fillRoundRect(x + gapX, y + gapY * 2 + boxSize, boxSize, boxSize, arc, arc)
        // Bottom-right
        g2.fillRoundRect(x + gapX * 2 + boxSize, y + gapY * 2 + boxSize, boxSize, boxSize, arc, arc)
        
        g2.dispose()
    }

    override fun getIconWidth() = JBUI.scale(size)
    override fun getIconHeight() = JBUI.scale(size)
}
