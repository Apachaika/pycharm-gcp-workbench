package dev.vertexworkbench.pycharm.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.JBColor
import com.intellij.ui.LayeredIcon
import com.intellij.util.ui.JBUI
import dev.vertexworkbench.pycharm.jupyter.normalizeJupyterPath
import dev.vertexworkbench.pycharm.model.RemoteFileEntry
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

/**
 * Renders Workbench tree nodes. When [liveSessionPaths] returns a non-empty set, any `.ipynb`
 * whose normalized path is in the set gets a small green dot overlay indicating that a kernel
 * is currently running on the remote for that notebook.
 */
class RemoteTreeCellRenderer(
    private val liveSessionPaths: () -> Set<String> = { emptySet() },
) : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree?,
        value: Any?,
        sel: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode
        val entry = (node?.userObject as? RemoteTreeItem)?.entry
        val baseIcon: Icon = when {
            entry == null -> AllIcons.Nodes.Folder
            entry.isDirectory -> AllIcons.Nodes.Folder
            entry.name.endsWith(".ipynb", ignoreCase = true) -> WorkbenchIcons.ToolWindow
            else -> FileTypeManager.getInstance()
                .getFileTypeByFileName(entry.name)
                .icon
                ?: AllIcons.FileTypes.Any_type
        }
        icon = if (entry != null
            && !entry.isDirectory
            && entry.name.endsWith(".ipynb", ignoreCase = true)
            && normalizeJupyterPath(entry.path) in liveSessionPaths()
        ) {
            decorateWithLiveDot(baseIcon)
        } else {
            baseIcon
        }
        return component
    }

    private fun decorateWithLiveDot(base: Icon): Icon {
        val layered = LayeredIcon(2)
        layered.setIcon(base, 0)
        val dotW = LiveSessionDotIcon.iconWidth
        val dotH = LiveSessionDotIcon.iconHeight
        // Anchor in the bottom-right corner of the base icon. Fall back gracefully on icons
        // smaller than the dot (just paint at 0,0).
        val hShift = (base.iconWidth - dotW).coerceAtLeast(0)
        val vShift = (base.iconHeight - dotH).coerceAtLeast(0)
        layered.setIcon(LiveSessionDotIcon, 1, hShift, vShift)
        return layered
    }
}

private object LiveSessionDotIcon : Icon {
    private const val NOMINAL_SIZE = 6
    private val color = JBColor(Color(0x1E8E3E), Color(0x81C995))

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            val s = JBUI.scale(NOMINAL_SIZE)
            g2.fillOval(x, y, s, s)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth(): Int = JBUI.scale(NOMINAL_SIZE)
    override fun getIconHeight(): Int = JBUI.scale(NOMINAL_SIZE)
}
