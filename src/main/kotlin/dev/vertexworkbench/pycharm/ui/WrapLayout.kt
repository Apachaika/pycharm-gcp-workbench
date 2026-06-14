package dev.vertexworkbench.pycharm.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A [FlowLayout] that actually reports the multi-row height it needs.
 *
 * Plain [FlowLayout] visually wraps its children when the parent is too narrow but
 * still reports a single-row preferred size, so a parent layout (BorderLayout.SOUTH,
 * etc.) only allocates one row's worth of vertical space and the wrapped rows get
 * clipped. This subclass recalculates [preferredLayoutSize] and [minimumLayoutSize]
 * by simulating the wrapping at the current target width.
 *
 * Adapted from the well-known WrapLayout recipe by Rob Camick
 * (https://tips4java.wordpress.com/2008/11/06/wrap-layout/).
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, false).apply {
            // Min size of one row's contents — let the parent shrink the column down
            // to a single button if it really wants to.
            width -= (hgap + 1)
        }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val targetWidth = resolveTargetWidth(target)
            val maxWidth = if (targetWidth == 0) Int.MAX_VALUE else targetWidth

            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2)
            val maxRowWidth = maxWidth - horizontalInsetsAndGap

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0
            val members = target.componentCount

            for (i in 0 until members) {
                val component = target.getComponent(i)
                if (!component.isVisible) continue
                val size = if (preferred) component.preferredSize else component.minimumSize

                if (rowWidth + size.width > maxRowWidth) {
                    addRow(dim, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += hgap
                rowWidth += size.width
                rowHeight = maxOf(rowHeight, size.height)
            }
            addRow(dim, rowWidth, rowHeight)

            dim.width += horizontalInsetsAndGap
            dim.height += insets.top + insets.bottom + vgap * 2

            // When sitting inside a JScrollPane, the parent reports its viewport size
            // through SwingUtilities.getAncestorOfClass; otherwise -hgap - 1 lets the
            // last column kiss the right edge.
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
            if (scrollPane != null && target.isValid) {
                dim.width -= (hgap + 1)
            }
            return dim
        }
    }

    private fun resolveTargetWidth(target: Container): Int {
        var current: Container? = target
        while (current != null) {
            if (current.size.width > 0) return current.size.width
            current = current.parent
        }
        return 0
    }

    private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) dim.height += vgap
        dim.height += rowHeight
    }
}
