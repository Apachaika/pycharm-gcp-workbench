package dev.vertexworkbench.pycharm.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import dev.vertexworkbench.pycharm.contents.JupyterContentsClient
import dev.vertexworkbench.pycharm.jupyter.RemoteNotebookSessionService
import dev.vertexworkbench.pycharm.jupyter.formatIdleFor
import dev.vertexworkbench.pycharm.model.RemoteNotebookSession
import dev.vertexworkbench.pycharm.model.RemoteSearchResult
import dev.vertexworkbench.pycharm.remote.RemoteBootstrapService
import dev.vertexworkbench.pycharm.remote.RemoteRunService
import dev.vertexworkbench.pycharm.remote.RemoteSearchService
import dev.vertexworkbench.pycharm.remote.RemoteStatusService
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService
import dev.vertexworkbench.pycharm.workspace.RemoteSelectiveSyncService
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.Timer

class RemoteSearchPanel(
    private val project: Project,
    private val selectedDirectory: () -> String,
) {
    private val queryField = JTextField()
    private val resultModel = DefaultListModel<SearchRow>()
    private val resultList = JList(resultModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) openSelected()
            }
        })
    }

    fun component(): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout(6, 6)).apply {
            add(toolbar(), BorderLayout.NORTH)
            add(JBScrollPane(resultList), BorderLayout.CENTER)
        }

    private fun toolbar(): JComponent =
        JPanel(GridBagLayout()).apply {
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(3, 3, 3, 3)
            }
            c.gridx = 0
            c.weightx = 1.0
            add(queryField, c)
            c.gridx = 1
            c.weightx = 0.0
            add(JButton("Text").apply { addActionListener { search(text = true) } }, c)
            c.gridx = 2
            add(JButton("Files").apply { addActionListener { search(text = false) } }, c)
            c.gridx = 3
            add(JButton("Open").apply { addActionListener { openSelected() } }, c)
        }

    private fun search(text: Boolean) {
        val query = queryField.text.trim()
        if (query.isBlank()) return
        try {
            val root = selectedDirectory()
            val results = runWithProgress(project, "Searching Workbench...") {
                if (text) project.service<RemoteSearchService>().searchText(query, root)
                else project.service<RemoteSearchService>().searchFiles(query, root)
            }
            resultModel.clear()
            results.forEach { resultModel.addElement(SearchRow(it)) }
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench Search")
        }
    }

    private fun openSelected() {
        val result = resultList.selectedValue?.result ?: return
        try {
            runWithProgress(project, "Opening ${result.path}...") {
                project.service<RemoteFileSyncService>().openRemotePath(result.path)
            }
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench Search")
        }
    }

    private data class SearchRow(val result: RemoteSearchResult) {
        override fun toString(): String {
            val pos = result.line?.let { ":$it${result.column?.let { col -> ":$col" } ?: ""}" }.orEmpty()
            return "${result.path}$pos  ${result.preview}"
        }
    }
}

class RemoteRunPanel(
    private val project: Project,
    private val selectedDirectory: () -> String,
) {
    private val commandField = JTextField()
    private val outputArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
    }

    fun component(): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout(6, 6)).apply {
            add(toolbar(), BorderLayout.NORTH)
            add(JBScrollPane(outputArea), BorderLayout.CENTER)
        }

    private fun toolbar(): JComponent =
        JPanel(GridBagLayout()).apply {
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(3, 3, 3, 3)
            }
            val presets = project.service<RemoteRunService>().presets
            presets.forEachIndexed { index, preset ->
                c.gridx = index
                c.weightx = 0.0
                add(JButton(preset.name).apply { addActionListener { run(preset.command) } }, c)
            }
            c.gridx = presets.size
            c.weightx = 1.0
            add(commandField, c)
            c.gridx = presets.size + 1
            c.weightx = 0.0
            add(JButton("Run").apply { addActionListener { run(commandField.text.trim()) } }, c)
        }

    private fun run(command: String) {
        if (command.isBlank()) return
        try {
            val result = runWithProgress(project, "Running on Workbench...") {
                project.service<RemoteRunService>().run(command, selectedDirectory())
            }
            outputArea.text = buildString {
                append("$ ").append(command).append('\n')
                append("exit ").append(result.exitCode).append("\n\n")
                append(result.output)
            }
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench Run")
        }
    }
}

class RemoteStatusPanel(private val project: Project) {
    private val text = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    fun component(): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout(6, 6)).apply {
            add(JButton("Refresh").apply { addActionListener { refresh() } }, BorderLayout.NORTH)
            add(JBScrollPane(text), BorderLayout.CENTER)
        }

    fun refresh() {
        try {
            val snapshot = runWithProgress(project, "Loading Workbench status...") {
                project.service<RemoteStatusService>().snapshot()
            }
            text.text = """
                Account: ${snapshot.account}
                Project: ${snapshot.projectId}
                Instance: ${snapshot.instanceName}
                State: ${snapshot.instanceState}
                Python: ${snapshot.python}
                Jupyter: ${snapshot.jupyter}
                CPU: ${snapshot.cpu}
                Memory: ${snapshot.memory}
                Disk: ${snapshot.disk}
                GPU: ${snapshot.gpu}
                Uptime: ${snapshot.uptime}
                Last sync: ${snapshot.lastSync}
            """.trimIndent()
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench Status")
        }
    }
}

class RemoteResourceBar(private val project: Project) {
    private val cpu = JBLabel("CPU --")
    private val memory = JBLabel("RAM --/--")
    private val disk = JBLabel("Disk --/--")
    private val timer = Timer(5_000) { refreshAsync() }

    fun component(): JComponent =
        JPanel(GridBagLayout()).apply {
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.NONE
                anchor = GridBagConstraints.WEST
                insets = Insets(4, 8, 4, 8)
            }
            c.gridx = 0
            c.weightx = 0.0
            add(cpu, c)
            c.gridx = 1
            add(memory, c)
            c.gridx = 2
            add(disk, c)
            c.gridx = 3
            c.weightx = 1.0
            c.fill = GridBagConstraints.HORIZONTAL
            add(JPanel(), c)
        }

    fun start() {
        if (!timer.isRunning) timer.start()
        refreshAsync()
    }

    fun stop() {
        timer.stop()
    }

    private fun refreshAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val metrics = runCatching { project.service<RemoteStatusService>().metrics() }.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (metrics == null) {
                    cpu.text = "CPU --"
                    memory.text = "RAM --/--"
                    disk.text = "Disk --/--"
                    return@invokeLater
                }
                cpu.text = "CPU ${metrics.cpuPercent}%"
                memory.text = "RAM ${metrics.memoryUsed}/${metrics.memoryTotal}"
                disk.text = "Disk ${metrics.diskUsed}/${metrics.diskTotal}"
            }
        }
    }
}

class RemoteSyncPanel(
    private val project: Project,
    private val selectedDirectory: () -> String,
) {
    private val model = DefaultListModel<String>()
    private val folders = JList(model)
    private val status = JBLabel("Sync idle")

    fun component(): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout(6, 6)).apply {
            add(toolbar(), BorderLayout.NORTH)
            add(JBScrollPane(folders), BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
            refreshModel()
        }

    private fun toolbar(): JComponent =
        JPanel().apply {
            add(JButton("Pin Selected").apply { addActionListener { pinSelected() } })
            add(JButton("Unpin").apply { addActionListener { unpinSelected() } })
            add(JButton("Sync Now").apply { addActionListener { syncNow() } })
        }

    private fun pinSelected() {
        project.service<RemoteSelectiveSyncService>().pin(selectedDirectory())
        refreshModel()
    }

    private fun unpinSelected() {
        folders.selectedValue?.let { project.service<RemoteSelectiveSyncService>().unpin(it) }
        refreshModel()
    }

    private fun syncNow() {
        try {
            val summary = runWithProgress(project, "Syncing pinned Workbench folders...") {
                project.service<RemoteSelectiveSyncService>().syncPinned()
            }
            status.text = "Cached ${summary.cached}, skipped ${summary.skipped}, errors ${summary.errors.size}"
            if (summary.errors.isNotEmpty()) {
                Messages.showErrorDialog(project, summary.errors.joinToString("\n").take(2000), "Vertex Workbench Sync")
            }
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench Sync")
        }
    }

    private fun refreshModel() {
        model.clear()
        project.service<RemoteSelectiveSyncService>().pinnedFolders().forEach { model.addElement(it) }
    }
}

class RemoteNotebookPanel(private val project: Project) {
    private val model = DefaultListModel<SessionRow>()
    private val list = JList(model)

    fun component(): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout(6, 6)).apply {
            add(toolbar(), BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }

    private fun toolbar(): JComponent =
        JPanel().apply {
            add(JButton("Refresh").apply { addActionListener { refresh() } })
            add(JButton("Attach in PyCharm").apply { addActionListener { attachSelected() } })
            add(JButton("Restart").apply { addActionListener { selected()?.let { project.service<RemoteNotebookSessionService>().restartKernel(it) }; refresh() } })
            add(JButton("Stop").apply { addActionListener { selected()?.let { project.service<RemoteNotebookSessionService>().stopSession(it) }; refresh() } })
            add(JButton("Stop All").apply { addActionListener { stopAll() } })
            add(JButton("Browser").apply { addActionListener { selected()?.let { project.service<RemoteNotebookSessionService>().openNotebookInBrowser(it) } } })
        }

    fun refresh() {
        try {
            val sessions = runWithProgress(project, "Loading notebook sessions...") {
                project.service<RemoteNotebookSessionService>().sessions()
            }
            model.clear()
            sessions.forEach { model.addElement(SessionRow(it)) }
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench Notebooks")
        }
    }

    private fun stopAll() {
        val confirm = Messages.showYesNoDialog(
            project,
            "Stop all active Workbench notebook sessions?",
            "Vertex Workbench Notebooks",
            "Stop All",
            "Cancel",
            null,
        )
        if (confirm != Messages.YES) return
        project.service<RemoteNotebookSessionService>().stopAllSessions()
        refresh()
    }

    /**
     * Opens the notebook locally and lets the standard Workbench open flow attach to the
     * already-running remote kernel — `RemoteSessionLookup` inside the auto-starter sees the
     * existing session at this path and reuses it.
     */
    private fun attachSelected() {
        val session = selected() ?: return
        if (session.path.isBlank()) {
            Messages.showInfoMessage(project, "Selected session has no remote path.", "Vertex Workbench Notebooks")
            return
        }
        try {
            val entry = runWithProgress(project, "Locating ${session.path}...") {
                project.service<JupyterContentsClient>().metadata(session.path)
            }
            runWithProgress(project, "Opening ${entry.name}...") {
                project.service<RemoteFileSyncService>().open(entry)
            }
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench Notebooks")
        }
    }

    private fun selected(): RemoteNotebookSession? = list.selectedValue?.session

    private data class SessionRow(val session: RemoteNotebookSession) {
        override fun toString(): String {
            val idle = formatIdleFor(session.lastActivity)
            val parts = mutableListOf(
                session.path.ifBlank { "<no path>" },
                session.kernelName.ifBlank { "?" },
                session.executionState,
            )
            if (idle != null) parts.add(idle)
            session.connections?.let { parts.add("conns=$it") }
            return parts.joinToString("  ")
        }
    }
}

class RemoteBootstrapPanel(
    private val project: Project,
    private val selectedDirectory: () -> String,
    private val openTerminal: () -> Unit,
) {
    private val createVenv = JCheckBox("Create .venv if missing", true)
    private val installDependencies = JCheckBox("Install requirements/pyproject")
    private val output = JTextArea().apply {
        isEditable = false
        lineWrap = false
    }

    fun component(): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout(6, 6)).apply {
            add(toolbar(), BorderLayout.NORTH)
            add(JBScrollPane(output), BorderLayout.CENTER)
        }

    private fun toolbar(): JComponent =
        JPanel().apply {
            add(createVenv)
            add(installDependencies)
            add(JButton("Initialize").apply { addActionListener { initialize() } })
        }

    private fun initialize() {
        val remoteFolder = selectedDirectory()
        if (installDependencies.isSelected) {
            val confirm = Messages.showYesNoDialog(
                project,
                "Install dependencies in '${remoteFolder.ifBlank { "/" }}' on the Workbench instance?",
                "Vertex Workbench Bootstrap",
                "Install",
                "Cancel",
                null,
            )
            if (confirm != Messages.YES) return
        }
        try {
            val result = runWithProgress(project, "Initializing Workbench project...") {
                project.service<RemoteBootstrapService>().initialize(
                    remoteFolder,
                    createVenv = createVenv.isSelected,
                    installDependencies = installDependencies.isSelected,
                )
            }
            output.text = buildString {
                append("exit ").append(result.exitCode).append("\n\n")
                append(result.output)
            }
            if (result.exitCode != 0) {
                Messages.showErrorDialog(project, result.output.ifBlank { "Bootstrap failed with exit ${result.exitCode}." }.take(2000), "Vertex Workbench Bootstrap")
                return
            }
            runCatching { project.service<RemoteBootstrapService>().openEntryPoint(remoteFolder) }
            openTerminal()
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench Bootstrap")
        }
    }
}

private fun <T> runWithProgress(project: Project, title: String, task: () -> T): T =
    ProgressManager.getInstance().runProcessWithProgressSynchronously<T, RuntimeException>(
        task,
        title,
        false,
        project,
    )
