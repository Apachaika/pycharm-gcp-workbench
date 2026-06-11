package dev.vertexworkbench.pycharm.git

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import dev.vertexworkbench.pycharm.model.RemoteFileEntry
import dev.vertexworkbench.pycharm.model.RemoteGitChange
import dev.vertexworkbench.pycharm.model.RemoteGitCommit
import dev.vertexworkbench.pycharm.model.RemoteGitRepository
import dev.vertexworkbench.pycharm.model.RemoteGitStatusSnapshot
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel

private val LOG = logger<RemoteGitPanel>()

class RemoteGitPanel(
    private val project: Project,
    private val selectedRemoteEntry: () -> RemoteFileEntry?,
    private val refreshFiles: () -> Unit,
) {
    private val repositoryService = project.service<RemoteGitRepositoryService>()
    private val statusService = project.service<RemoteGitStatusService>()
    private val actionsService = project.service<RemoteGitActionsService>()
    private val syncService = project.service<RemoteFileSyncService>()

    private val repositoryLabel = JBLabel("Repository: none")
    private val branchLabel = JBLabel("Branch: none")
    private val upstreamLabel = JBLabel("Upstream: none")
    private val statusLabel = JBLabel("Git: idle")
    private val syncLabel = JBLabel("Remote Git")
    private val changeModel = DefaultListModel<ChangeRow>()
    private val changeList = JList(changeModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        cellRenderer = ChangeRowRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) openSelectedChange()
            }
        })
    }
    private val historyModel = DefaultListModel<CommitRow>()
    private val historyList = JList(historyModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = CommitRowRenderer()
        componentPopupMenu = historyPopup()
    }
    private val commitMessageField = JTextField()

    private var lastSnapshot: RemoteGitStatusSnapshot? = null
    private var activated = false
    private var historyLoaded = false
    private var historyLimit = 20

    fun component(): JComponent {
        val tabs = JTabbedPane().apply {
            addTab("Changes", changesPanel())
            addTab("History", historyPanel())
            addChangeListener {
                if (selectedIndex >= 0 && getTitleAt(selectedIndex) == "History" && !historyLoaded) {
                    refreshHistoryOnly()
                }
            }
        }

        return JBPanel<JBPanel<*>>(BorderLayout(6, 6)).apply {
            add(header(), BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
    }

    fun activate() {
        if (activated && repositoryService.activeRepository() != null) return
        activated = true
        if (repositoryService.activeRepository() == null) {
            discoverRepository(showNotFoundDialog = false)
        } else {
            refreshGit()
        }
    }

    private fun header(): JComponent {
        val container = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(3, 6, 3, 6)
        }
        c.gridx = 0
        c.gridy = 0
        container.add(syncLabel, c)
        c.gridy = 1
        container.add(repositoryLabel, c)
        c.gridy = 2
        container.add(branchLabel, c)
        c.gridy = 3
        container.add(upstreamLabel, c)
        c.gridy = 4
        container.add(statusLabel, c)
        c.gridy = 5
        container.add(buttonGrid {
            add(JButton("Repository...").apply { addActionListener { discoverRepository(showNotFoundDialog = true) } })
            add(JButton("Refresh").apply { addActionListener { refreshGit() } })
            add(JButton("Fetch").apply { addActionListener { fetch() } })
            add(JButton("Pull...").apply { addActionListener { pull() } })
            add(JButton("Branch...").apply { addActionListener { branchMenu() } })
            add(JButton("Clone...").apply { addActionListener { cloneRepository() } })
        }, c)
        return container
    }

    private fun changesPanel(): JComponent {
        val changeActions = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }
        c.gridx = 0
        c.gridy = 0
        changeActions.add(buttonGrid {
            add(JButton("Diff").apply { addActionListener { diffSelectedChange() } })
            add(JButton("Open").apply { addActionListener { openSelectedChange() } })
            add(JButton("Stage").apply { addActionListener { stageSelected() } })
            add(JButton("Stage All").apply { addActionListener { stageAll() } })
        }, c)
        c.gridy = 1
        changeActions.add(buttonGrid {
            add(JButton("Unstage").apply { addActionListener { unstageSelected() } })
            add(JButton("Unstage All").apply { addActionListener { unstageAll() } })
            add(JButton("Discard").apply { addActionListener { discardSelected() } })
            add(JButton("Stash").apply { addActionListener { stash() } })
            add(JButton("Pop").apply { addActionListener { stashPop() } })
        }, c)

        val commitPanel = JBPanel<JBPanel<*>>(GridBagLayout())
        val cc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 6, 4, 6)
        }
        cc.gridx = 0
        cc.gridy = 0
        cc.weightx = 1.0
        commitPanel.add(commitMessageField, cc)
        cc.gridx = 1
        cc.weightx = 0.0
        commitPanel.add(JButton("Commit").apply { addActionListener { commit() } }, cc)
        cc.gridx = 2
        commitPanel.add(JButton("Push").apply { addActionListener { push() } }, cc)

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(changeActions, BorderLayout.NORTH)
            add(JBScrollPane(changeList), BorderLayout.CENTER)
            add(commitPanel, BorderLayout.SOUTH)
        }
    }

    private fun historyPanel(): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(buttonGrid {
                add(JButton("Refresh History").apply { addActionListener { refreshHistoryOnly() } })
                add(JButton("Load More").apply { addActionListener { loadMoreHistory() } })
                add(JButton("Copy Hash").apply { addActionListener { copySelectedHash() } })
            }, BorderLayout.NORTH)
            add(JBScrollPane(historyList), BorderLayout.CENTER)
        }

    private fun buttonGrid(init: JPanel.() -> Unit): JPanel =
        JPanel(GridBagLayout()).apply {
            init()
            val components = (0 until componentCount).map { getComponent(it) }
            removeAll()
            components.forEachIndexed { i, component ->
                add(component, GridBagConstraints().apply {
                    gridx = i % 3
                    gridy = i / 3
                    fill = GridBagConstraints.HORIZONTAL
                    weightx = 1.0
                    insets = Insets(2, 2, 2, 2)
                })
            }
            add(Box.createHorizontalGlue(), GridBagConstraints().apply {
                gridx = 3
                gridy = 0
                weightx = 1.0
            })
        }

    private fun discoverRepository(showNotFoundDialog: Boolean) {
        val entry = selectedRemoteEntry()
        val path = entry?.path.orEmpty()
        statusLabel.text = "Git: finding repository..."
        val repo = runGitCatching("Finding Git repository...") { repositoryService.discover(path, entry?.isDirectory) }
        if (repo == null) {
            clearRepository()
            if (!showNotFoundDialog) return
            val diagnostic = repositoryService.lastDiscoveryOutput()
                .lineSequence()
                .filter { it.isNotBlank() }
                .take(12)
                .joinToString("\n")
                .take(1200)
            val message = buildString {
                append("No Git repository found for '${path.ifBlank { "/" }}'.")
                if (diagnostic.isNotBlank()) {
                    append("\n\nRemote output:\n")
                    append(diagnostic)
                }
            }
            Messages.showInfoMessage(project, message, "Vertex Workbench Git")
            return
        }
        showRepository(repo)
        refreshGit()
    }

    private fun refreshGit() {
        val repo = repositoryService.activeRepository()
            ?: selectedRemoteEntry().let { repositoryService.discover(it?.path.orEmpty(), it?.isDirectory) }
            ?: return
        showRepository(repo)
        statusLabel.text = "Git: loading status..."
        val overview = runGitCatching("Refreshing Git status...") { statusService.overview(repo) }
        if (overview != null) {
            lastSnapshot = overview.status
            renderChanges(overview.status.changes)
            renderSummary(overview.status)
        }
    }

    private fun refreshHistoryOnly() {
        val repo = repositoryService.activeRepository() ?: return
        statusLabel.text = "Git: loading history..."
        val commits = runGitCatching("Loading Git history...") { statusService.history(repo, historyLimit) }
        if (commits != null) {
            historyLoaded = true
            renderHistory(commits)
            val changeCount = lastSnapshot?.changes?.size ?: 0
            statusLabel.text = "Git: loaded ($changeCount changes, ${commits.size} commits)"
        }
    }

    private fun loadMoreHistory() {
        historyLimit += 20
        refreshHistoryOnly()
    }

    private fun showRepository(repo: RemoteGitRepository) {
        repositoryLabel.text = "Repository: ${repo.name}"
        branchLabel.text = "Branch: ${repo.currentBranch}"
    }

    private fun clearRepository() {
        repositoryLabel.text = "Repository: none"
        branchLabel.text = "Branch: none"
        upstreamLabel.text = "Upstream: none"
        if (!statusLabel.text.startsWith("Git error:")) statusLabel.text = "Git: repository not found"
        changeModel.clear()
        historyModel.clear()
        lastSnapshot = null
        historyLoaded = false
        historyLimit = 20
    }

    private fun renderSummary(snapshot: RemoteGitStatusSnapshot) {
        val summary = snapshot.summary
        branchLabel.text = "Branch: ${summary.currentBranch}"
        upstreamLabel.text = buildString {
            append("Upstream: ")
            append(summary.upstream ?: "none")
            if (summary.ahead > 0) append("  ahead ${summary.ahead}")
            if (summary.behind > 0) append("  behind ${summary.behind}")
        }
        statusLabel.text = "Git: ${summary.stagedCount} staged, ${summary.changedCount} changed, ${summary.untrackedCount} untracked"
    }

    private fun renderChanges(changes: List<RemoteGitChange>) {
        changeModel.clear()
        addChangeGroup("Staged", changes.filter { it.staged })
        addChangeGroup("Changed", changes.filter { !it.staged && it.status != "Untracked" })
        addChangeGroup("Untracked", changes.filter { it.status == "Untracked" })
    }

    private fun addChangeGroup(title: String, changes: List<RemoteGitChange>) {
        changeModel.addElement(ChangeRow("$title (${changes.size})", null))
        changes.forEach { changeModel.addElement(ChangeRow("${it.status}  ${it.path}", it)) }
    }

    private fun renderHistory(commits: List<RemoteGitCommit>) {
        historyModel.clear()
        commits.forEach { historyModel.addElement(CommitRow(it)) }
    }

    private fun selectedChanges(): List<RemoteGitChange> =
        changeList.selectedValuesList.mapNotNull { it.change }

    private fun allChanges(): List<RemoteGitChange> =
        (0 until changeModel.size()).mapNotNull { changeModel.getElementAt(it).change }

    private fun openSelectedChange() {
        val repo = repositoryService.activeRepository() ?: return
        val change = selectedChanges().firstOrNull() ?: return
        val remotePath = "${repo.rootPath.trimEnd('/')}/${change.path}"
        runGit("Opening ${change.path}...") { syncService.openRemotePath(remotePath) }
    }

    private fun diffSelectedChange() {
        val repo = repositoryService.activeRepository() ?: return
        val change = selectedChanges().firstOrNull() ?: return
        val diff = runGit("Loading diff for ${change.path}...") { statusService.diff(repo, change) }
        val text = diff.ifBlank { "No diff for ${change.path}." }
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                "Remote Git Diff: ${change.path}",
                DiffContentFactory.getInstance().create(""),
                DiffContentFactory.getInstance().create(text),
                "Base",
                "Diff",
            ),
        )
    }

    private fun stageSelected() {
        val repo = repositoryService.activeRepository() ?: return
        val paths = selectedChanges().map { it.path }
        if (paths.isEmpty()) return
        showResult(actionsService.stage(repo, paths), showSuccess = false)
        refreshGit()
    }

    private fun stageAll() {
        val repo = repositoryService.activeRepository() ?: return
        showResult(actionsService.stageAll(repo), showSuccess = false)
        refreshGit()
    }

    private fun unstageSelected() {
        val repo = repositoryService.activeRepository() ?: return
        val paths = selectedChanges().map { it.path }
        if (paths.isEmpty()) return
        showResult(actionsService.unstage(repo, paths), showSuccess = false)
        refreshGit()
    }

    private fun unstageAll() {
        val repo = repositoryService.activeRepository() ?: return
        showResult(actionsService.unstageAll(repo), showSuccess = false)
        refreshGit()
    }

    private fun discardSelected() {
        val repo = repositoryService.activeRepository() ?: return
        val changes = selectedChanges()
        if (changes.isEmpty()) return
        val confirm = Messages.showYesNoDialog(
            project,
            "Discard selected remote changes?\n\n${changes.joinToString("\n") { it.path }}",
            "Vertex Workbench Git",
            "Discard",
            "Cancel",
            null,
        )
        if (confirm != Messages.YES) return
        val tracked = changes.filter { it.status != "Untracked" }.map { it.path }
        val untracked = changes.filter { it.status == "Untracked" }.map { it.path }
        if (tracked.isNotEmpty()) showResult(actionsService.discard(repo, tracked), showSuccess = false)
        if (untracked.isNotEmpty()) showResult(actionsService.discardUntracked(repo, untracked), showSuccess = false)
        refreshFiles()
        historyLoaded = false
        refreshGit()
    }

    private fun commit() {
        val repo = repositoryService.activeRepository() ?: return
        val summary = commitMessageField.text.trim()
        if (summary.isBlank()) {
            Messages.showErrorDialog(project, "Commit message is required.", "Vertex Workbench Git")
            return
        }

        val snapshot = runGit("Checking Git status...") { statusService.status(repo) }
        val stagedCount = snapshot.summary.stagedCount
        if (stagedCount == 0) {
            val selected = selectedChanges()
            val stageResult = if (selected.isNotEmpty()) {
                actionsService.stage(repo, selected.map { it.path })
            } else {
                if (snapshot.changes.isEmpty()) {
                    Messages.showInfoMessage(project, "No changes to commit.", "Vertex Workbench Git")
                    return
                }
                actionsService.stageAll(repo)
            }
            if (stageResult.exitCode != 0) {
                showResult(stageResult)
                refreshGit()
                return
            }
        }

        showResult(actionsService.commit(repo, summary, ""))
        commitMessageField.text = ""
        refreshFiles()
        historyLoaded = false
        refreshGit()
    }

    private fun branchMenu() {
        val repo = repositoryService.activeRepository() ?: return
        val actions = arrayOf("Checkout Branch", "New Branch", "Delete Branch")
        val action = Messages.showDialog(
            project,
            "Choose branch action:",
            "Vertex Workbench Git Branch",
            actions,
            0,
            null,
        )
        when (actions.getOrNull(action)) {
            "Checkout Branch" -> checkoutBranchDialog(repo)
            "New Branch" -> createBranch(repo)
            "Delete Branch" -> deleteBranch(repo)
        }
    }

    private fun checkoutBranchDialog(repo: RemoteGitRepository) {
        val branches = runGit("Loading Git branches...") { statusService.branches(repo) }
        val names = branches.map { it.name }.filter { it.isNotBlank() }.toTypedArray()
        if (names.isEmpty()) {
            Messages.showInfoMessage(project, "No branches found.", "Vertex Workbench Git")
            return
        }
        val selected = Messages.showEditableChooseDialog(
            "Select or type branch name:",
            "Checkout Branch",
            null,
            names,
            branches.firstOrNull { it.isCurrent }?.name ?: names.first(),
            null,
        )?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (!confirmDirty(repo, "Checkout branch '$selected'?")) return
        val result = if (selected.startsWith("remotes/")) {
            actionsService.checkoutRemoteTracking(repo, selected.removePrefix("remotes/"))
        } else {
            actionsService.checkout(repo, selected)
        }
        showResult(result)
        repositoryService.discover(repo.rootPath)
        refreshFiles()
        historyLoaded = false
        refreshGit()
    }

    private fun createBranch(repo: RemoteGitRepository) {
        val branch = Messages.showInputDialog(project, "Branch name:", "New Branch", null)
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: return
        val branches = runGit("Loading Git branches...") { statusService.branches(repo) }
        val bases = (listOf(repo.currentBranch) + branches.map { normalizeBranchName(it.name) })
            .filter { it.isNotBlank() }
            .distinct()
            .toTypedArray()
        val base = Messages.showEditableChooseDialog(
            "Create '$branch' from:",
            "New Branch Base",
            null,
            bases,
            bases.firstOrNull { it == "origin/main" || it == "main" } ?: bases.firstOrNull() ?: repo.currentBranch,
            null,
        )?.trim()?.takeIf { it.isNotBlank() } ?: return
        showResult(actionsService.createBranchFrom(repo, branch, base))
        repositoryService.discover(repo.rootPath)
        historyLoaded = false
        refreshGit()
    }

    private fun deleteBranch(repo: RemoteGitRepository) {
        val branches = runGit("Loading Git branches...") { statusService.branches(repo) }
        val names = branches
            .filterNot { it.isCurrent || it.isRemote }
            .map { it.name }
            .filter { it.isNotBlank() }
            .toTypedArray()
        if (names.isEmpty()) {
            Messages.showInfoMessage(project, "No local non-current branches found.", "Vertex Workbench Git")
            return
        }
        val selected = Messages.showEditableChooseDialog(
            "Delete local branch:",
            "Delete Branch",
            null,
            names,
            names.first(),
            null,
        )?.trim()?.takeIf { it.isNotBlank() } ?: return
        val confirm = Messages.showYesNoDialog(
            project,
            "Delete local branch '$selected' on Workbench?",
            "Vertex Workbench Git",
            "Delete",
            "Cancel",
            null,
        )
        if (confirm != Messages.YES) return
        showResult(actionsService.deleteBranch(repo, selected))
        historyLoaded = false
        refreshGit()
    }

    private fun stash() {
        val repo = repositoryService.activeRepository() ?: return
        if (!confirmDirty(repo, "Stash current remote changes?")) return
        showResult(actionsService.stash(repo))
        refreshFiles()
        historyLoaded = false
        refreshGit()
    }

    private fun stashPop() {
        val repo = repositoryService.activeRepository() ?: return
        showResult(actionsService.stashPop(repo))
        refreshFiles()
        historyLoaded = false
        refreshGit()
    }

    private fun cloneRepository() {
        val entry = selectedRemoteEntry()
        val parent = when {
            entry == null -> "."
            entry.isDirectory -> entry.path.trim('/').ifBlank { "." }
            else -> entry.path.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "." }
        }
        val url = Messages.showInputDialog(project, "Repository URL:", "Clone Repository", null)
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: return
        val confirm = Messages.showYesNoDialog(
            project,
            "Clone into '${parent}' on the Workbench instance?",
            "Vertex Workbench Git",
            "Clone",
            "Cancel",
            null,
        )
        if (confirm != Messages.YES) return
        showResult(actionsService.cloneInto(parent, url))
        repositoryService.discover(parent)
        refreshFiles()
        historyLoaded = false
        refreshGit()
    }

    private fun fetch() {
        val repo = repositoryService.activeRepository() ?: return
        showResult(actionsService.fetch(repo))
        historyLoaded = false
        refreshGit()
    }

    private fun pull() {
        val repo = repositoryService.activeRepository() ?: return
        val branches = runGit("Loading Git branches...") { statusService.branches(repo) }
        val remoteBranches = branches
            .map { normalizeBranchName(it.name) }
            .filter { it.contains("/") && !it.startsWith("HEAD") }
            .distinct()
            .toTypedArray()
        val defaultBranch = lastSnapshot?.summary?.upstream ?: remoteBranches.firstOrNull { it == "origin/main" } ?: remoteBranches.firstOrNull()
        val selected = if (remoteBranches.isNotEmpty()) {
            Messages.showEditableChooseDialog(
                "Pull from remote branch:",
                "Pull Remote Branch",
                null,
                remoteBranches,
                defaultBranch,
                null,
            )?.trim()?.takeIf { it.isNotBlank() } ?: return
        } else {
            Messages.showInputDialog(project, "Pull from remote/branch:", "Pull Remote Branch", null)
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return
        }
        val remote = selected.substringBefore("/", missingDelimiterValue = "origin")
        val branch = selected.substringAfter("/", missingDelimiterValue = selected)
        if (!confirmDirty(repo, "Pull '$remote/$branch' with --ff-only?")) return
        showResult(actionsService.pullFrom(repo, remote, branch))
        refreshFiles()
        historyLoaded = false
        refreshGit()
    }

    private fun push() {
        val repo = repositoryService.activeRepository() ?: return
        val snapshot = runGit("Checking Git status...") { statusService.status(repo) }
        val branch = snapshot.summary.currentBranch
        val result = when (val strategy = RemoteGitPushDecider.decide(branch, snapshot.summary.upstream)) {
            is RemoteGitPushStrategy.MatchingUpstream -> actionsService.push(repo)
            is RemoteGitPushStrategy.NoUpstream -> pushNoUpstream(repo, branch) ?: return
            is RemoteGitPushStrategy.UpstreamMismatch -> pushUpstreamMismatch(repo, branch, strategy) ?: return
        }
        showResult(result)
        if (result.exitCode == 0) {
            RemoteGitPushHintParser.extract(result.output)?.let { hint -> notifyPushHint(hint) }
        }
        historyLoaded = false
        refreshGit()
    }

    private fun pushNoUpstream(repo: RemoteGitRepository, branch: String): RemoteGitCommandResult? {
        val confirm = Messages.showYesNoDialog(
            project,
            "No upstream is configured for '$branch'. Push and set upstream to origin/$branch?",
            "Vertex Workbench Git",
            "Push -u",
            "Cancel",
            null,
        )
        if (confirm != Messages.YES) return null
        return actionsService.pushWithUpstream(repo, "origin", branch)
    }

    private fun pushUpstreamMismatch(
        repo: RemoteGitRepository,
        branch: String,
        strategy: RemoteGitPushStrategy.UpstreamMismatch,
    ): RemoteGitCommandResult? {
        val newBranchOption = "Push to ${strategy.upstreamRemote}/$branch as new branch (set upstream)"
        val upstreamOption = "Push to ${strategy.upstream} (current upstream)"
        val options = arrayOf(newBranchOption, upstreamOption, "Cancel")
        val choice = Messages.showDialog(
            project,
            "Branch '$branch' is tracking '${strategy.upstream}', whose name differs from '$branch'.\n" +
                "Git will refuse a plain push. Choose where to push:",
            "Vertex Workbench Git Push",
            options,
            0,
            null,
        )
        return when (options.getOrNull(choice)) {
            newBranchOption -> actionsService.pushWithUpstream(repo, strategy.upstreamRemote, branch)
            upstreamOption -> actionsService.pushHeadToRemoteBranch(repo, strategy.upstreamRemote, strategy.upstreamBranch)
            else -> null
        }
    }

    private fun notifyPushHint(hint: PushHint) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Vertex Workbench")
            .createNotification("Push complete", hint.url, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimple(hint.label) { BrowserUtil.browse(hint.url) })
            .notify(project)
    }

    private fun confirmDirty(repo: RemoteGitRepository, message: String): Boolean {
        val changes = runGit("Checking Git status...") { statusService.changes(repo) }
        if (changes.isEmpty()) return true
        return Messages.showYesNoDialog(
            project,
            "$message\n\nRepository has ${changes.size} changed files.",
            "Vertex Workbench Git",
            "Continue",
            "Cancel",
            null,
        ) == Messages.YES
    }

    private fun normalizeBranchName(name: String): String =
        name.removePrefix("remotes/")

    private fun copySelectedHash() {
        val hash = historyList.selectedValue?.commit?.hash ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(hash))
    }

    private fun historyPopup(): JPopupMenu =
        JPopupMenu().apply {
            add(JMenuItem("Copy Hash").apply { addActionListener { copySelectedHash() } })
            add(JMenuItem("Refresh History").apply { addActionListener { refreshHistoryOnly() } })
        }

    private fun showResult(result: RemoteGitCommandResult, showSuccess: Boolean = true) {
        val text = result.output.ifBlank { "Done." }
        if (result.exitCode == 0) {
            if (showSuccess) Messages.showInfoMessage(project, text.take(1000), "Vertex Workbench Git")
        } else {
            Messages.showErrorDialog(project, text.take(2000), "Vertex Workbench Git Error")
        }
    }

    private fun <T> runGit(title: String, task: () -> T): T =
        ProgressManager.getInstance().runProcessWithProgressSynchronously<T, RuntimeException>(
            task,
            title,
            false,
            project,
        )

    private fun <T> runGitCatching(title: String, task: () -> T): T? =
        try {
            runGit(title, task)
        } catch (t: Throwable) {
            LOG.warn("Vertex Workbench Git failed: $title", t)
            statusLabel.text = "Git error: ${t.message?.lineSequence()?.firstOrNull()?.take(180) ?: t.javaClass.simpleName}"
            null
        }

    private data class ChangeRow(val label: String, val change: RemoteGitChange?) {
        override fun toString(): String = label
    }

    private data class CommitRow(val commit: RemoteGitCommit) {
        override fun toString(): String {
            val refs = commit.refs.takeIf { it.isNotEmpty() }?.joinToString(", ", " [", "]").orEmpty()
            return "${commit.hash}  ${commit.relativeDate}  ${commit.author}$refs  ${commit.message}"
        }
    }

    private class ChangeRowRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val row = value as? ChangeRow ?: return this
            if (row.change == null) {
                text = "<html><b>${escape(row.label)}</b></html>"
            } else {
                text = "<html><b>${escape(row.change.status)}</b>&nbsp;&nbsp;${escape(row.change.path)}</html>"
            }
            border = javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8)
            return this
        }
    }

    private class CommitRowRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val commit = (value as? CommitRow)?.commit ?: return this
            val refs = commit.refs.takeIf { it.isNotEmpty() }?.joinToString(", ", " <span color='#8ab4f8'>", "</span>").orEmpty()
            text = "<html><b>${escape(commit.message)}</b><br><span color='#999999'>${escape(commit.hash)} · ${escape(commit.author)} · ${escape(commit.relativeDate)}</span>$refs</html>"
            border = javax.swing.BorderFactory.createEmptyBorder(7, 8, 7, 8)
            return this
        }
    }

    companion object {
        private fun escape(value: String): String =
            value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
    }
}
