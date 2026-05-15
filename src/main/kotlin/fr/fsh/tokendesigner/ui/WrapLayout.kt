package fr.fsh.tokendesigner.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A FlowLayout subclass that correctly computes its preferred size based on the width
 * of its container, causing components to wrap to the next line instead of overflowing.
 * This is especially useful when placed inside a JScrollPane with vertical scrolling only.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {

    private var preferredLayoutSize: Dimension? = null

    override fun preferredLayoutSize(target: Container): Dimension {
        return layoutSize(target, true)
    }

    override fun minimumLayoutSize(target: Container): Dimension {
        val minimum = layoutSize(target, false)
        minimum.width -= hgap + 1
        return minimum
    }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            var targetWidth = target.size.width
            if (targetWidth == 0) {
                targetWidth = Int.MAX_VALUE
            }

            var hgap = this.hgap
            var vgap = this.vgap
            var insets: Insets = target.insets
            var horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2)
            var maxWidth = targetWidth - horizontalInsetsAndGap

            var dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            var nmembers = target.componentCount
            for (i in 0 until nmembers) {
                var m = target.getComponent(i)

                if (m.isVisible) {
                    var d = if (preferred) m.preferredSize else m.minimumSize

                    if (rowWidth + d.width > maxWidth) {
                        addRow(dim, rowWidth, rowHeight)
                        rowWidth = 0
                        rowHeight = 0
                    }

                    if (rowWidth != 0) {
                        rowWidth += hgap
                    }

                    rowWidth += d.width
                    rowHeight = maxOf(rowHeight, d.height)
                }
            }

            addRow(dim, rowWidth, rowHeight)

            dim.width += horizontalInsetsAndGap
            dim.height += insets.top + insets.bottom + vgap * 2

            // When used in a JScrollPane, we need to add the parent's insets
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target) as? JScrollPane
            if (scrollPane != null && target.isValid) {
                dim.width -= scrollPane.verticalScrollBar.size.width
            }

            return dim
        }
    }

    private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) {
            dim.height += vgap
        }
        dim.height += rowHeight
    }
}
