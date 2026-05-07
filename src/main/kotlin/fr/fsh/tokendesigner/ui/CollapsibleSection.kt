package fr.fsh.tokendesigner.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Boxed section with a clickable header (chevron + title + count + optional
 * help tooltip). Click toggles the body's visibility. Used inside
 * [AnalyzePanel] so the dossier stays compact even with many sections.
 *
 * The header repaints its chevron when the panel toggles; consumers should
 * pass the body component pre-built — it isn't recomputed on toggle.
 */
class CollapsibleSection(
    title: String,
    count: Int,
    helpText: String? = null,
    private val body: JComponent,
    initiallyCollapsed: Boolean = false,
) : JPanel(BorderLayout()) {

    private val chevron = JLabel(
        if (initiallyCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown,
    ).apply { border = JBUI.Borders.emptyRight(8) }

    private val titleLabel = JLabel(
        "<html><b>$title</b> <span style='color:#888'>· $count</span></html>"
    )

    private var collapsed: Boolean = initiallyCollapsed

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(0),
        )

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = JBColor.namedColor("ToolWindow.HeaderBackground", JBColor(0xF5F5F5, 0x3C3F41))
            border = JBUI.Borders.empty(8, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isOpaque = true
            alignmentX = Component.LEFT_ALIGNMENT
            add(chevron)
            add(titleLabel)
            if (helpText != null) {
                add(JLabel(AllIcons.General.ContextHelp).apply {
                    border = JBUI.Borders.emptyLeft(8)
                    toolTipText = helpText
                })
            }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = toggle()
            })
        }

        val bodyWrap = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 12, 12)
            isOpaque = false
            add(body, BorderLayout.CENTER)
        }
        // Only the wrapper toggles visibility. Touching `body.isVisible` would
        // leave it hidden once we reveal `bodyWrap` (the wrap shows but the
        // inner content stays invisible — exactly the bug we hit on the
        // Couverture section when it started collapsed).
        bodyWrap.isVisible = !initiallyCollapsed

        add(header, BorderLayout.NORTH)
        add(bodyWrap, BorderLayout.CENTER)
    }

    private fun toggle() {
        collapsed = !collapsed
        chevron.icon = if (collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
        // Toggle the wrapper at CENTER (component index 1).
        (getComponent(1) as JComponent).isVisible = !collapsed
        revalidate()
        repaint()
    }
}
