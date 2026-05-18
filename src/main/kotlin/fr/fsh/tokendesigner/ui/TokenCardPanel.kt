package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.TransferHandler
import kotlin.math.roundToInt

class TokenCardPanel(
    val token: DesignToken,
    private val onDoubleClick: (DesignToken) -> Unit,
    private val onRightClick: (DesignToken, MouseEvent) -> Unit
) : JPanel(BorderLayout()) {

    private val defaultBg = JBColor.namedColor("Plugins.lightSelectionBackground", JBColor(0xFAFAFA, 0x2B2D30))
    private val hoverBg = JBColor.namedColor("Plugins.hoverBackground", JBColor(0xF0F0F0, 0x393B40))
    private val defaultBorderColor = JBColor.namedColor("Borders.color", JBColor(0xEBECF0, 0x43454A))
    private val hoverBorderColor = JBColor.namedColor("Component.focusColor", JBColor.BLUE)
    
    private var isHovered = false

    init {
        preferredSize = Dimension(JBUI.scale(180), JBUI.scale(90))
        isOpaque = false
        border = JBUI.Borders.empty(8, 10, 10, 10)

        val topPanel = JPanel(BorderLayout()).apply { isOpaque = false }
        val previewPanel = createPreviewPanel()

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(RoundIconButton(AllIcons.Actions.Copy, "Copy value") { btn ->
                CopyPasteManager.getInstance()
                    .setContents(fr.fsh.tokendesigner.actions.TokenDragTransferable.forToken(token))
                
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createHtmlTextBalloonBuilder("Copied!", com.intellij.openapi.ui.MessageType.INFO, null)
                    .setFadeoutTime(1500)
                    .createBalloon()
                    .show(com.intellij.ui.awt.RelativePoint.getSouthOf(btn), com.intellij.openapi.ui.popup.Balloon.Position.above)
            }.apply {
                preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            })

            if (token.variants.isNotEmpty()) {
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(RoundIconButton(AllIcons.General.Information, "View variants") { btn ->
                    showVariantsPopup(btn)
                }.apply {
                    preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
                })
            }
        }

        topPanel.add(previewPanel, BorderLayout.WEST)
        topPanel.add(actionsPanel, BorderLayout.EAST)

        val nameLabel = JBLabel(token.name).apply {
            font = JBFont.small().deriveFont(java.awt.Font.BOLD)
            toolTipText = token.name
        }
        val valueLabel = JBLabel(token.resolvedValue).apply {
            font = JBFont.small()
            foreground = JBColor.GRAY
            toolTipText = token.resolvedValue
        }

        val bottomPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(nameLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(valueLabel)
        }

        add(topPanel, BorderLayout.NORTH)
        add(bottomPanel, BorderLayout.CENTER)

        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                isHovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                isHovered = false
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    onDoubleClick(token)
                } else if (e.button == MouseEvent.BUTTON3 || e.isPopupTrigger) {
                    onRightClick(token, e)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) onRightClick(token, e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) onRightClick(token, e)
            }
        })
        
        // Drag and Drop
        transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent): Int = COPY
            override fun createTransferable(c: JComponent): Transferable {
                return fr.fsh.tokendesigner.actions.TokenDragTransferable.forToken(token)
            }
        }
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                transferHandler.exportAsDrag(this@TokenCardPanel, e, TransferHandler.COPY)
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        // Background
        g2.color = if (isHovered) hoverBg else defaultBg
        g2.fillRoundRect(0, 0, width, height, 16, 16)
        
        // Border
        g2.color = if (isHovered) hoverBorderColor else defaultBorderColor
        g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
        
        g2.dispose()
    }

    private fun showVariantsPopup(invoker: JComponent) {
        val html = VariantTableHtml.build(token)
        val label = JBLabel(html).apply {
            border = JBUI.Borders.empty(8)
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(label, null)
            .createPopup()
            .show(com.intellij.ui.awt.RelativePoint(invoker, java.awt.Point(0, invoker.height)))
    }

    private fun createPreviewPanel(): JComponent {
        val wrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
        
        when (token.category) {
            TokenCategory.COLOR -> {
                val color = ColorParser.parse(token.resolvedValue) ?: JBColor.GRAY
                wrapper.add(ColorCirclePreview(color))
            }
            TokenCategory.RADIUS -> {
                val px = parseToPixels(token.resolvedValue)
                wrapper.add(RadiusPreview(px))
            }
            TokenCategory.SPACING -> {
                val px = parseToPixels(token.resolvedValue)
                wrapper.add(SpacingPreview(px))
            }
            else -> {
                val fallback = JBLabel(CategoryGlyphs.glyphFor(token.category) ?: "·").apply {
                    font = JBFont.h1().deriveFont(java.awt.Font.BOLD).deriveFont(20f)
                    foreground = JBColor.GRAY
                    horizontalAlignment = SwingConstants.CENTER
                    preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
                }
                wrapper.add(fallback)
            }
        }
        return wrapper
    }

    private fun parseToPixels(value: String): Int {
        val numStr = value.replace(Regex("[^0-9.]"), "")
        val num = numStr.toFloatOrNull() ?: 0f
        return when {
            value.endsWith("rem") || value.endsWith("em") -> (num * 16).roundToInt()
            value.endsWith("px") -> num.roundToInt()
            else -> num.roundToInt() // Assume px if unitless
        }
    }

    private class ColorCirclePreview(private val color: Color) : JComponent() {
        init {
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            if (color.alpha < 255) {
                g2.color = JBColor.WHITE
                g2.fillOval(0, 0, width, height)
                g2.color = JBColor.LIGHT_GRAY
                g2.fillArc(0, 0, width, height, 0, 90)
                g2.fillArc(0, 0, width, height, 180, 90)
            }

            g2.color = color
            g2.fillOval(0, 0, width, height)

            g2.color = JBColor.border()
            g2.drawOval(0, 0, width - 1, height - 1)
            g2.dispose()
        }
    }

    private class RadiusPreview(private val radiusPx: Int) : JComponent() {
        init {
            preferredSize = Dimension(JBUI.scale(40), JBUI.scale(24))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            g2.color = JBColor.namedColor("Plugins.tagBackground", JBColor(0xE0E0E0, 0x43454A))
            val arc = JBUI.scale(radiusPx * 2).coerceAtMost(width)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
        }
    }

    private class SpacingPreview(private val spacingPx: Int) : JComponent() {
        init {
            preferredSize = Dimension(JBUI.scale(60), JBUI.scale(24))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            val boxWidth = JBUI.scale(12)
            val boxHeight = JBUI.scale(12)
            val y = (height - boxHeight) / 2
            
            val scaledSpacing = JBUI.scale(spacingPx).coerceAtMost(JBUI.scale(30))
            
            val startX = 0
            
            g2.color = JBColor.namedColor("Plugins.tagBackground", JBColor(0xE0E0E0, 0x43454A))
            g2.fillRoundRect(startX, y, boxWidth, boxHeight, 3, 3)
            g2.fillRoundRect(startX + boxWidth + scaledSpacing, y, boxWidth, boxHeight, 3, 3)
            
            g2.color = JBColor.namedColor("Component.focusColor", JBColor.BLUE)
            val lineY = height / 2
            g2.drawLine(startX + boxWidth, lineY, startX + boxWidth + scaledSpacing, lineY)
            g2.drawLine(startX + boxWidth, lineY - 2, startX + boxWidth, lineY + 2)
            g2.drawLine(startX + boxWidth + scaledSpacing, lineY - 2, startX + boxWidth + scaledSpacing, lineY + 2)
            
            g2.dispose()
        }
    }
}
