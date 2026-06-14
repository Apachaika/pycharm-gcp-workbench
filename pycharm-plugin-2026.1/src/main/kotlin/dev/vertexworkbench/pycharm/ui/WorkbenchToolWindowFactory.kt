package dev.vertexworkbench.pycharm.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService
import dev.vertexworkbench.pycharm.contents.JupyterContentsClient
import dev.vertexworkbench.pycharm.git.RemoteGitPanel
import dev.vertexworkbench.pycharm.imports.RemoteImportIndexService
import dev.vertexworkbench.pycharm.jupyter.RemoteSessionLookup
import dev.vertexworkbench.pycharm.model.RemoteFileEntry
import dev.vertexworkbench.pycharm.terminal.WorkbenchTerminalService
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService
import dev.vertexworkbench.pycharm.workspace.RemoteWorkspaceService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.DropMode
import javax.swing.JFileChooser
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTabbedPane
import javax.swing.Timer
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import java.util.concurrent.atomic.AtomicBoolean

class WorkbenchToolWindowFactory : ToolWindowFactory {
    override fun init(toolWindow: ToolWindow) {
        toolWindow.setIcon(WorkbenchIcons.ToolWindow)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val connectionService = project.service<WorkbenchConnectionService>()
        val workspaceService = project.service<RemoteWorkspaceService>()
        val syncService = project.service<RemoteFileSyncService>()
        val contentsClient = project.service<JupyterContentsClient>()

        var clipboardPath: String? = null

        val nameValidator = object : InputValidator {
            override fun checkInput(input: String?): Boolean {
                val value = input?.trim().orEmpty()
                return value.isNotEmpty() && !value.contains('/') && value != "." && value != ".."
            }

            override fun canClose(input: String?): Boolean = checkInput(input)
        }

        val rootNode = DefaultMutableTreeNode(RemoteTreeItem.rootPlaceholder())
        val treeModel = DefaultTreeModel(rootNode)
        // Assigned on EDT by the session-poll timer; read on EDT by the cell renderer.
        var liveSessionPaths: Set<String> = emptySet()
        val tree = JTree(treeModel).apply {
            isRootVisible = true
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = RemoteTreeCellRenderer { liveSessionPaths }
            dropMode = DropMode.ON_OR_INSERT
        }
        val statusDot = JBLabel("●")
        val statusText = JBLabel()

        fun statusColor(state: String): JBColor =
            when (state) {
                "ACTIVE" -> JBColor(0x1E8E3E, 0x81C995)
                "STOPPED", "DISCONNECTED" -> JBColor(0x80868B, 0x9AA0A6)
                "STARTING", "STOPPING" -> JBColor(0xF9AB00, 0xFDD663)
                "ERROR" -> JBColor(0xD93025, 0xF28B82)
                else -> JBColor(0x80868B, 0x9AA0A6)
            }

        fun refreshStatus() {
            val state = connectionService.workbenchState
            statusDot.foreground = statusColor(state)
            statusText.text = "Status: $state"
        }

        refreshStatus()
        var refreshContentVisibility: () -> Unit = {}
        val statusRefreshInProgress = AtomicBoolean(false)

        fun selectedEntry(): RemoteFileEntry? {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
            return (node.userObject as? RemoteTreeItem)?.entry
        }

        fun selectedDirectoryPath(): String {
            val entry = selectedEntry() ?: return ""
            return if (entry.isDirectory) entry.path else entry.path.substringBeforeLast('/', missingDelimiterValue = "")
        }

        fun replaceNode(node: DefaultMutableTreeNode, entry: RemoteFileEntry) {
            node.userObject = RemoteTreeItem(entry)
            node.removeAllChildren()
            entry.children.forEach { child -> node.add(DefaultMutableTreeNode(RemoteTreeItem(child))) }
            treeModel.reload(node)
        }

        fun resetTree() {
            rootNode.removeAllChildren()
            rootNode.userObject = RemoteTreeItem.rootPlaceholder()
            treeModel.reload()
        }

        fun loadRoot() {
            try {
                if (connectionService.activeConnection == null) {
                    refreshStatus()
                    return
                }
                runWithProgress(project, "Checking Vertex Workbench status...") {
                    connectionService.refreshActiveState()
                }
                refreshStatus()
                if (connectionService.workbenchState != "ACTIVE") return
                val root = runWithProgress(project, "Loading Vertex Workbench files...") {
                    workspaceService.root()
                }
                replaceNode(rootNode, root.copy(name = "/"))
                tree.expandRow(0)
                refreshStatus()
                refreshContentVisibility()
            } catch (t: Throwable) {
                refreshStatus()
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun openSelected() {
            val entry = selectedEntry() ?: return
            try {
                if (entry.isDirectory) {
                    val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val refreshed = runWithProgress(project, "Loading ${entry.path.ifBlank { "/" }}...") {
                        workspaceService.list(entry.path)
                    }
                    replaceNode(selectedNode, refreshed)
                    tree.expandPath(tree.selectionPath)
                } else {
                    runWithProgress(project, "Opening ${entry.name}...") {
                        syncService.open(entry)
                    }
                }
            } catch (t: Throwable) {
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun nodeEntry(node: DefaultMutableTreeNode?): RemoteFileEntry? =
            (node?.userObject as? RemoteTreeItem)?.entry

        fun refreshDir(node: DefaultMutableTreeNode, entry: RemoteFileEntry) {
            val refreshed = runWithProgress(project, "Loading ${entry.path.ifBlank { "/" }}...") {
                workspaceService.list(entry.path)
            }
            replaceNode(node, if (entry.path.isBlank()) refreshed.copy(name = "/") else refreshed)
            tree.expandPath(TreePath(node.path))
        }

        // The directory a new/pasted item lands in: the selected folder, or the parent of the
        // selected file, falling back to the workspace root.
        fun dirContext(): Pair<DefaultMutableTreeNode, RemoteFileEntry>? {
            val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode) ?: rootNode
            val entry = nodeEntry(node) ?: return null
            if (entry.isDirectory) return node to entry
            val parent = node.parent as? DefaultMutableTreeNode ?: return null
            val parentEntry = nodeEntry(parent) ?: return null
            return parent to parentEntry
        }

        fun dirContextForNode(node: DefaultMutableTreeNode?): Pair<DefaultMutableTreeNode, RemoteFileEntry>? {
            val targetNode = node ?: rootNode
            val entry = nodeEntry(targetNode) ?: return null
            if (entry.isDirectory) return targetNode to entry
            val parent = targetNode.parent as? DefaultMutableTreeNode ?: return null
            val parentEntry = nodeEntry(parent) ?: return null
            return parent to parentEntry
        }

        fun selectedNodeAndEntry(): Pair<DefaultMutableTreeNode, RemoteFileEntry>? {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
            val entry = nodeEntry(node) ?: return null
            return node to entry
        }

        // Returns the created entry, or null if the name was already taken.
        fun createEntry(dirEntry: RemoteFileEntry, name: String, progressTitle: String, create: () -> RemoteFileEntry): RemoteFileEntry? {
            val fullPath = if (dirEntry.path.isBlank()) name else "${dirEntry.path.trimEnd('/')}/$name"
            return runWithProgress(project, progressTitle) {
                if (contentsClient.exists(fullPath)) null else create()
            }
        }

        fun createNew(type: String, title: String, defaultName: String) {
            val (dirNode, dirEntry) = dirContext() ?: return
            val name = Messages.showInputDialog(project, "Name:", title, null, defaultName, nameValidator)
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return
            try {
                val created = createEntry(dirEntry, name, "Creating $name…") {
                    contentsClient.createFile(dirEntry.path, name, type)
                }
                if (created == null) {
                    Messages.showErrorDialog(project, "'$name' already exists in ${dirEntry.path.ifBlank { "/" }}.", "Vertex Workbench")
                    return
                }
                refreshDir(dirNode, dirEntry)
                runWithProgress(project, "Opening $name…") { syncService.open(created) }
            } catch (t: Throwable) {
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun createNewFolder() {
            val (dirNode, dirEntry) = dirContext() ?: return
            val name = Messages.showInputDialog(project, "Name:", "New Folder", null, "untitled", nameValidator)
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return
            try {
                val created = createEntry(dirEntry, name, "Creating folder $name…") {
                    contentsClient.createDirectory(dirEntry.path, name)
                }
                if (created == null) {
                    Messages.showErrorDialog(project, "'$name' already exists in ${dirEntry.path.ifBlank { "/" }}.", "Vertex Workbench")
                    return
                }
                refreshDir(dirNode, dirEntry)
            } catch (t: Throwable) {
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun openTerminal(cwd: String? = null) {
            try {
                runWithProgress(project, "Opening Vertex Workbench terminal...") {
                    project.service<WorkbenchTerminalService>().openTerminal(cwd)
                }
            } catch (t: Throwable) {
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun renameSelected() {
            val (node, entry) = selectedNodeAndEntry() ?: return
            if (entry.path.isBlank()) return
            val parent = node.parent as? DefaultMutableTreeNode ?: return
            val parentEntry = nodeEntry(parent) ?: return
            val newName = Messages.showInputDialog(project, "New name:", "Rename", null, entry.name, nameValidator)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != entry.name } ?: return
            try {
                val newPath = if (parentEntry.path.isBlank()) newName else "${parentEntry.path.trimEnd('/')}/$newName"
                runWithProgress(project, "Renaming...") { contentsClient.rename(entry.path, newPath) }
                refreshDir(parent, parentEntry)
            } catch (t: Throwable) {
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun deleteSelected() {
            val (node, entry) = selectedNodeAndEntry() ?: return
            if (entry.path.isBlank()) return
            val parent = node.parent as? DefaultMutableTreeNode ?: return
            val parentEntry = nodeEntry(parent) ?: return
            val suffix = if (entry.isDirectory) " and all of its contents" else ""
            val confirm = Messages.showYesNoDialog(
                project,
                "Delete '${entry.name}'$suffix on the Workbench instance?",
                "Vertex Workbench",
                "Delete",
                "Cancel",
                null,
            )
            if (confirm != Messages.YES) return
            try {
                runWithProgress(project, "Deleting ${entry.name}...") { contentsClient.delete(entry.path) }
                refreshDir(parent, parentEntry)
            } catch (t: Throwable) {
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun pasteInto() {
            val source = clipboardPath ?: return
            val (dirNode, dirEntry) = dirContext() ?: return
            try {
                runWithProgress(project, "Pasting...") { contentsClient.copy(source, dirEntry.path) }
                refreshDir(dirNode, dirEntry)
            } catch (t: Throwable) {
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun uploadFiles(files: List<File>, dirNode: DefaultMutableTreeNode, dirEntry: RemoteFileEntry) {
            val regularFiles = files.filter { it.isFile }
            if (regularFiles.isEmpty()) {
                Messages.showInfoMessage(project, "Only regular files can be uploaded.", "Vertex Workbench")
                return
            }
            try {
                regularFiles.forEach { file ->
                    val remotePath = if (dirEntry.path.isBlank()) file.name else "${dirEntry.path.trimEnd('/')}/${file.name}"
                    val exists = runWithProgress(project, "Checking ${file.name}...") {
                        contentsClient.exists(remotePath)
                    }
                    if (exists) {
                        val overwrite = Messages.showYesNoDialog(
                            project,
                            "'$remotePath' already exists on the Workbench instance.\n\nOverwrite it?",
                            "Vertex Workbench",
                            "Overwrite",
                            "Cancel",
                            null,
                        )
                        if (overwrite != Messages.YES) return@forEach
                    }
                    runWithProgress(project, "Uploading ${file.name}...") {
                        syncService.uploadLocalFile(file.toPath(), dirEntry)
                    }
                }
                refreshDir(dirNode, dirEntry)
            } catch (t: Throwable) {
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun uploadFileFromChooser() {
            val (dirNode, dirEntry) = dirContext() ?: return
            val chooser = JFileChooser().apply {
                dialogTitle = "Upload File to Vertex Workbench"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = true
            }
            if (chooser.showOpenDialog(tree) != JFileChooser.APPROVE_OPTION) return
            uploadFiles(chooser.selectedFiles.toList(), dirNode, dirEntry)
        }

        fun downloadSelected() {
            val entry = selectedEntry() ?: return
            if (entry.isDirectory) return
            val chooser = JFileChooser().apply {
                dialogTitle = "Download ${entry.name}"
                selectedFile = File(entry.name)
            }
            if (chooser.showSaveDialog(tree) != JFileChooser.APPROVE_OPTION) return
            val target = chooser.selectedFile.toPath()
            if (Files.exists(target)) {
                val overwrite = Messages.showYesNoDialog(
                    project,
                    "'${chooser.selectedFile.name}' already exists locally.\n\nOverwrite it?",
                    "Vertex Workbench",
                    "Overwrite",
                    "Cancel",
                    null,
                )
                if (overwrite != Messages.YES) return
            }
            try {
                val bytes = runWithProgress(project, "Downloading ${entry.name}...") {
                    contentsClient.read(entry.path, forceFile = !entry.name.endsWith(".ipynb", ignoreCase = true)).bytes
                }
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
            } catch (t: Throwable) {
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun showContextMenu(x: Int, y: Int, nodeUnderCursor: DefaultMutableTreeNode?) {
            if (nodeUnderCursor != null) {
                tree.selectionPath = TreePath(nodeUnderCursor.path)
            }
            val selected = nodeUnderCursor?.let { node ->
                nodeEntry(node)?.let { entry -> node to entry }
            } ?: selectedNodeAndEntry()
            val selectedNode = selected?.first
            val entry = selected?.second
            val menu = JPopupMenu()

            val dirCtxEntry = dirContext()?.second
            if (dirCtxEntry != null) {
                val newMenu = JMenu("New")
                newMenu.add(JMenuItem("File").apply { addActionListener { createNew("file", "New File", "untitled.txt") } })
                newMenu.add(JMenuItem("Python File").apply { addActionListener { createNew("file", "New Python File", "untitled.py") } })
                newMenu.add(JMenuItem("Notebook").apply { addActionListener { createNew("notebook", "New Notebook", "Untitled.ipynb") } })
                newMenu.addSeparator()
                newMenu.add(JMenuItem("Terminal").apply { addActionListener { openTerminal(dirCtxEntry.path) } })
                newMenu.addSeparator()
                newMenu.add(JMenuItem("Folder").apply { addActionListener { createNewFolder() } })
                menu.add(newMenu)
                menu.add(JMenuItem("Upload File…").apply { addActionListener { uploadFileFromChooser() } })
                menu.add(JMenuItem("Open in Terminal").apply { addActionListener { openTerminal(dirCtxEntry.path) } })
            }
            if (entry != null && !entry.isDirectory) {
                menu.add(JMenuItem("Open").apply { addActionListener { openSelected() } })
                menu.add(JMenuItem("Download…").apply { addActionListener { downloadSelected() } })
            }
            if (entry != null && entry.path.isNotBlank()) {
                menu.add(JMenuItem("Copy").apply { addActionListener { clipboardPath = entry.path } })
            }
            if (clipboardPath != null && dirContext() != null) {
                menu.add(JMenuItem("Paste").apply { addActionListener { pasteInto() } })
            }
            if (entry != null && entry.path.isNotBlank()) {
                menu.add(JMenuItem("Rename…").apply { addActionListener { renameSelected() } })
                menu.add(JMenuItem("Delete").apply { addActionListener { deleteSelected() } })
            }
            if (menu.componentCount > 0) menu.addSeparator()
            menu.add(JMenuItem("Refresh").apply {
                addActionListener {
                    if (selectedNode != null && entry != null && entry.isDirectory) {
                        refreshDir(selectedNode, entry)
                    } else {
                        loadRoot()
                    }
                }
            })
            menu.show(tree, x, y)
        }

        fun connectAndLoad() {
            try {
                val connection = connectionService.connectInteractively()
                refreshStatus()
                if (connection != null) {
                    refreshContentVisibility()
                    loadRoot()
                    runCatching { project.service<RemoteImportIndexService>().reindexIfEnabled() }
                }
            } catch (t: Throwable) {
                refreshContentVisibility()
                refreshStatus()
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        tree.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                connectionService.activeConnection != null && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val files = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                }.getOrNull() ?: return false
                val dropLocation = support.dropLocation as? JTree.DropLocation
                val targetNode = dropLocation?.path?.lastPathComponent as? DefaultMutableTreeNode
                    ?: tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    ?: rootNode
                val (dirNode, dirEntry) = dirContextForNode(targetNode) ?: return false
                uploadFiles(files, dirNode, dirEntry)
                return true
            }
        }

        fun connectOtherInstanceAndLoad() {
            try {
                resetTree()
                refreshContentVisibility()
                refreshStatus()
                val connection = connectionService.connectToOtherInstance()
                refreshStatus()
                if (connection != null) {
                    refreshContentVisibility()
                    loadRoot()
                    runCatching { project.service<RemoteImportIndexService>().reindexIfEnabled() }
                } else {
                    refreshContentVisibility()
                }
            } catch (t: Throwable) {
                refreshContentVisibility()
                refreshStatus()
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        fun stopWorkbenchInstance() {
            val instance = connectionService.activeConnection?.instance
            if (instance == null) {
                Messages.showInfoMessage(project, "No active Vertex Workbench connection.", "Vertex Workbench")
                return
            }
            val confirm = Messages.showYesNoDialog(
                project,
                "Stop Vertex Workbench instance '${instance.name}'?\n\nThis stops the VM but does not delete it.",
                "Vertex Workbench",
                "Stop Instance",
                "Cancel",
                null,
            )
            if (confirm != Messages.YES) return
            try {
                runWithProgress(project, "Stopping Vertex Workbench instance...") {
                    connectionService.stopActiveInstance()
                }
                resetTree()
                refreshStatus()
                refreshContentVisibility()
            } catch (t: Throwable) {
                refreshStatus()
                Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
            }
        }

        val connect = JButton("Connect").apply {
            addActionListener { connectAndLoad() }
        }
        val refresh = JButton("Refresh").apply {
            addActionListener { loadRoot() }
        }
        val otherInstance = JButton("Other Instance").apply {
            addActionListener { connectOtherInstanceAndLoad() }
        }
        val stopInstance = JButton("Stop Instance").apply {
            addActionListener { stopWorkbenchInstance() }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybePopup(e)

            override fun mouseReleased(e: MouseEvent) = maybePopup(e)

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) openSelected()
            }

            private fun maybePopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y)
                val node = path?.lastPathComponent as? DefaultMutableTreeNode
                showContextMenu(e.x, e.y, node)
            }
        })

        val buttons = JBPanel<JBPanel<*>>(WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(connect)
            add(refresh)
            add(otherInstance)
            add(stopInstance)
            add(statusDot)
            add(statusText)
        }

        val cards = JBPanel<JBPanel<*>>(CardLayout())
        val emptyConnect = JButton("Connect").apply {
            font = font.deriveFont(Font.BOLD, 16f)
            addActionListener { connectAndLoad() }
        }
        val empty = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            val constraints = GridBagConstraints().apply {
                gridx = 0
                fill = GridBagConstraints.NONE
                anchor = GridBagConstraints.CENTER
            }
            add(JBLabel("Connect to Vertex AI Workbench").apply {
                font = font.deriveFont(Font.BOLD, 18f)
            }, constraints.apply {
                gridy = 0
                insets = Insets(0, 0, 12, 0)
            })
            add(emptyConnect, constraints.apply {
                gridy = 1
                insets = Insets(0, 0, 0, 0)
            })
        }
        val treePanel = JBScrollPane(tree)
        cards.add(empty, "empty")
        cards.add(treePanel, "tree")

        refreshContentVisibility = {
            val layout = cards.layout as CardLayout
            layout.show(cards, if (connectionService.activeConnection == null) "empty" else "tree")
        }

        val panel = JBPanel<JBPanel<*>>(BorderLayout(8, 8)).apply {
            add(cards, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        val gitPanel = RemoteGitPanel(
            project = project,
            selectedRemoteEntry = { selectedEntry() },
            refreshFiles = { loadRoot() },
        )
        val searchPanel = RemoteSearchPanel(project) { selectedDirectoryPath() }
        val runPanel = RemoteRunPanel(project) { selectedDirectoryPath() }
        val statusPanel = RemoteStatusPanel(project)
        val syncPanel = RemoteSyncPanel(project) { selectedDirectoryPath() }
        val notebookPanel = RemoteNotebookPanel(project)
        val bootstrapPanel = RemoteBootstrapPanel(project, { selectedDirectoryPath() }, { openTerminal() })
        val agentsPanel = RemoteAgentsPanel(project) { selectedDirectoryPath() }
        val resourceBar = RemoteResourceBar(project)
        val tabs = JTabbedPane().apply {
            addTab("Files", panel)
            addTab("Git", gitPanel.component())
            addTab("Run", runPanel.component())
            addTab("Search", searchPanel.component())
            addTab("Status", statusPanel.component())
            addTab("Sync", syncPanel.component())
            addTab("Notebooks", notebookPanel.component())
            addTab("Bootstrap", bootstrapPanel.component())
            addTab("Agents", agentsPanel.component())
            addChangeListener {
                if (selectedIndex >= 0 && getTitleAt(selectedIndex) == "Git") {
                    gitPanel.activate()
                }
                if (selectedIndex >= 0 && getTitleAt(selectedIndex) == "Status") {
                    statusPanel.refresh()
                }
                if (selectedIndex >= 0 && getTitleAt(selectedIndex) == "Notebooks") {
                    notebookPanel.refresh()
                }
            }
        }

        val contentPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(resourceBar.component(), BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        resourceBar.start()

        val content = ContentFactory.getInstance().createContent(contentPanel, "", false)
        toolWindow.contentManager.addContent(content)

        val statusTimer = Timer(60_000) {
            if (connectionService.activeConnection == null) {
                refreshStatus()
                return@Timer
            }
            if (!statusRefreshInProgress.compareAndSet(false, true)) return@Timer
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    connectionService.refreshActiveState()
                } finally {
                    statusRefreshInProgress.set(false)
                    ApplicationManager.getApplication().invokeLater {
                        refreshStatus()
                    }
                }
            }
        }
        statusTimer.initialDelay = 60_000
        statusTimer.start()

        // Polls /api/sessions every 30s so the tree can render a green dot on .ipynb files
        // whose remote kernel is currently running. The set is also cached for ~3s by
        // RemoteSessionLookup, so the explicit forceRefresh keeps it fresh enough.
        val sessionRefreshInProgress = AtomicBoolean(false)
        val sessionTimer = Timer(30_000) {
            if (connectionService.activeConnection == null) {
                if (liveSessionPaths.isNotEmpty()) {
                    liveSessionPaths = emptySet()
                    tree.repaint()
                }
                return@Timer
            }
            if (!sessionRefreshInProgress.compareAndSet(false, true)) return@Timer
            ApplicationManager.getApplication().executeOnPooledThread {
                val fresh = runCatching {
                    project.service<RemoteSessionLookup>().livePathSet(forceRefresh = true)
                }.getOrElse { emptySet() }
                ApplicationManager.getApplication().invokeLater {
                    try {
                        if (fresh != liveSessionPaths) {
                            liveSessionPaths = fresh
                            tree.repaint()
                        }
                    } finally {
                        sessionRefreshInProgress.set(false)
                    }
                }
            }
        }
        sessionTimer.initialDelay = 5_000
        sessionTimer.start()

        Disposer.register(toolWindow.disposable) {
            statusTimer.stop()
            sessionTimer.stop()
            resourceBar.stop()
        }

        if (connectionService.activeConnection != null) {
            loadRoot()
        } else {
            refreshContentVisibility()
        }
        refreshContentVisibility()
    }

    private fun <T> runWithProgress(project: Project, title: String, task: () -> T): T =
        ProgressManager.getInstance().runProcessWithProgressSynchronously<T, RuntimeException>(
            task,
            title,
            false,
            project,
        )
}
