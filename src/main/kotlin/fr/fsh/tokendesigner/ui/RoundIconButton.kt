package fr.fsh.tokendesigner.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JButton

/**
 * Compact circular icon button used next to the dashboard search bar. Paints a
 * filled circle background that adapts to hover/press states, then layers the
 * supplied [icon] dead centre. Sized to match the search field's natural
 * height so the two sit flush.
 *
 * Click handler receives `this` so callers can anchor a popup to the button.
 */
class RoundIconButton(
    var currentIcon: Icon,
    tooltip: String,
    private val sizePx: Int = DEFAULT_SIZE,
    onClick: (RoundIconButton) -> Unit,
) : JButton() {

    constructor(currentIcon: Icon, tooltip: String, onClick: (RoundIconButton) -> Unit) :
        this(currentIcon, tooltip, DEFAULT_SIZE, onClick)

    init {
        toolTipText = tooltip
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = JBUI.insets(0)
        addActionListener { onClick(this) }
    }

    var isActive: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    override fun getPreferredSize(): Dimension {
        val side = JBUI.scale(sizePx)
        return Dimension(side, side)
    }

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = backgroundColor()
            val side = minOf(width, height)
            val x = (width - side) / 2
            val y = (height - side) / 2
            g2.fillOval(x, y, side - 1, side - 1)
            g2.color = JBColor.border()
            g2.drawOval(x, y, side - 1, side - 1)
            val ix = (width - currentIcon.iconWidth) / 2
            val iy = (height - currentIcon.iconHeight) / 2
            currentIcon.paintIcon(this, g2, ix, iy)
        } finally {
            g2.dispose()
        }
    }

    private fun backgroundColor(): Color = when {
        isActive -> ACTIVE_BG
        model.isPressed -> PRESSED_BG
        model.isRollover -> HOVER_BG
        else -> IDLE_BG
    }

    private companion object {
        const val DEFAULT_SIZE = 24
        val IDLE_BG = JBColor.namedColor(
            "Tag.background",
            JBColor(Color(0xEDEDED), Color(0x3D3F41)),
        )
        val HOVER_BG = JBColor.namedColor(
            "Tag.hoverBackground",
            JBColor(Color(0xDDDDDD), Color(0x4D4F51)),
        )
        val PRESSED_BG = JBColor.namedColor(
            "Component.focusColor",
            JBColor(Color(0x3574F0), Color(0x375FAD)),
        )
        val ACTIVE_BG = JBColor.namedColor(
            "Plugins.lightSelectionBackground",
            JBColor(Color(0xD0E1F9), Color(0x2D4366)),
        )
    }
}
