package fr.fsh.tokendesigner.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.SwingUtilities

/**
 * A custom layout that acts like CSS Grid `grid-template-columns: repeat(auto-fill, minmax(minCellWidth, 1fr))`.
 * It calculates the optimal number of columns and stretches the children to fill the available width evenly.
 */
class CssGridLayout(
    private val minCellWidth: Int,
    private val cellHeight: Int,
    private val hgap: Int = 8,
    private val vgap: Int = 8
) : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component?) {}
    override fun removeLayoutComponent(comp: Component?) {}

    override fun preferredLayoutSize(parent: Container): Dimension = layoutContainer(parent, true)
    override fun minimumLayoutSize(parent: Container): Dimension = layoutContainer(parent, false)

    override fun layoutContainer(parent: Container) {
        layoutContainer(parent, false)
    }

    private fun layoutContainer(parent: Container, computePreferredSize: Boolean): Dimension {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            var targetWidth = parent.width
            
            // If inside a BoxLayout, width might be 0 during preferred size calculation.
            // We climb up to the JViewport to find the real available width.
            if (targetWidth <= 0) {
                val viewport = SwingUtilities.getAncestorOfClass(javax.swing.JViewport::class.java, parent) as? javax.swing.JViewport
                if (viewport != null) {
                    targetWidth = viewport.extentSize.width
                }
                if (targetWidth <= 0) targetWidth = 800 // Fallback
            }

            val availableWidth = targetWidth - insets.left - insets.right
            
            // Calculate number of columns
            var numCols = (availableWidth + hgap) / (minCellWidth + hgap)
            if (numCols < 1) numCols = 1

            // Calculate actual cell width to stretch and fill the row
            val cellWidth = (availableWidth - (numCols - 1) * hgap) / numCols

            val n = parent.componentCount
            var x = insets.left
            var y = insets.top

            var col = 0
            for (i in 0 until n) {
                val comp = parent.getComponent(i)
                if (comp.isVisible) {
                    if (!computePreferredSize) {
                        comp.setBounds(x, y, cellWidth, cellHeight)
                    }
                    col++
                    if (col >= numCols) {
                        col = 0
                        x = insets.left
                        y += cellHeight + vgap
                    } else {
                        x += cellWidth + hgap
                    }
                }
            }

            val totalHeight = if (col == 0) y - vgap else y + cellHeight
            return Dimension(targetWidth, totalHeight + insets.bottom)
        }
    }
}
