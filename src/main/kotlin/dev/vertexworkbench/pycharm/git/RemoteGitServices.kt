package dev.vertexworkbench.pycharm.git

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.model.RemoteGitBranch
import dev.vertexworkbench.pycharm.model.RemoteGitChange
import dev.vertexworkbench.pycharm.model.RemoteGitCommit
import dev.vertexworkbench.pycharm.model.RemoteGitRepository
import dev.vertexworkbench.pycharm.model.RemoteGitStatusSnapshot
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class RemoteGitRepositoryService(
    private val project: Project,
) {
    private val active = AtomicReference<RemoteGitRepository?>()
    private val lastDiscoveryOutput = AtomicReference("")

    fun activeRepository(): RemoteGitRepository? = active.get()
    fun lastDiscoveryOutput(): String = lastDiscoveryOutput.get()

    fun discover(path: String, isDirectory: Boolean? = null): RemoteGitRepository? {
        val command = project.service<RemoteGitCommandService>()
        val script = RemoteGitDiscovery.discoveryScript(path, isDirectory)
        val root = command.shell(script, 30)
        lastDiscoveryOutput.set(root.output)
        if (root.exitCode != 0) {
            active.set(null)
            return null
        }
        val lines = root.output.lines().map { it.trim() }
        val shellPath = lines.firstOrNull { it.startsWith("__VW_GIT_ROOT__") }
            ?.removePrefix("__VW_GIT_ROOT__")
            ?.trim()
            ?: return null
        val remoteRoot = lines.firstOrNull { it.startsWith("__VW_GIT_REMOTE_ROOT__") }
            ?.removePrefix("__VW_GIT_REMOTE_ROOT__")
            ?.trim()
            .orEmpty()
            .let { if (it == shellPath) it.trimStart('/') else it }
            .trim('/')
            .ifBlank { shellPath.trimStart('/').trim('/') }
        val branch = lines.firstOrNull { it.startsWith("__VW_GIT_BRANCH__") }
            ?.removePrefix("__VW_GIT_BRANCH__")
            ?.trim()
            .orEmpty()
            .ifBlank { "HEAD" }
        val repo = RemoteGitRepository(
            rootPath = remoteRoot,
            name = remoteRoot.substringAfterLast('/').ifBlank { remoteRoot },
            currentBranch = branch,
            shellPath = shellPath,
        )
        active.set(repo)
        return repo
    }
}

object RemoteGitDiscovery {
    fun discoveryScript(path: String, isDirectory: Boolean?): String {
        val candidates = candidateDirs(path, isDirectory)
        val selectedName = selectedName(path, isDirectory)
        return buildString {
            append("found=0\n")
            append("try_git_dir() {\n")
            append("  [ -d \"$1\" ] || return 1\n")
            append("  root=$(git -C \"$1\" rev-parse --show-toplevel 2>/dev/null) || return 1\n")
            append("  branch=$(git -C \"${'$'}root\" branch --show-current 2>/dev/null)\n")
            append("  remote=\"${'$'}root\"\n")
            append("  case \"${'$'}remote\" in \"${'$'}HOME\"/*) remote=\"${'$'}{remote#${'$'}HOME/}\" ;; esac\n")
            append("  case \"${'$'}remote\" in /home/jupyter/*) remote=\"${'$'}{remote#/home/jupyter/}\" ;; esac\n")
            append("  printf '__VW_GIT_ROOT__%s\\n' \"${'$'}root\"\n")
            append("  printf '__VW_GIT_REMOTE_ROOT__%s\\n' \"${'$'}remote\"\n")
            append("  printf '__VW_GIT_BRANCH__%s\\n' \"${'$'}{branch:-HEAD}\"\n")
            append("  return 0\n")
            append("}\n")
            append("for d in ")
            append(candidates.joinToString(" ") { RemoteShell.quote(it) })
            append("; do\n")
            append("  try_git_dir \"${'$'}d\" && found=1 && break\n")
            append("  try_git_dir \"${'$'}HOME/${'$'}d\" && found=1 && break\n")
            append("  try_git_dir \"/home/jupyter/${'$'}d\" && found=1 && break\n")
            append("done\n")
            if (selectedName.isNotBlank() && selectedName != ".") {
                append("if [ \"${'$'}found\" != \"1\" ]; then\n")
                append("  while IFS= read -r d; do\n")
                append("    try_git_dir \"${'$'}d\" && found=1 && break\n")
                append("  done < <(find \"${'$'}HOME\" -type d -name ")
                append(RemoteShell.quote(selectedName))
                append(" -print 2>/dev/null | head -n 20)\n")
                append("fi\n")
            }
            append("[ \"${'$'}found\" = \"1\" ]")
        }
    }

    fun candidateDirs(path: String, isDirectory: Boolean?): List<String> {
        val normalized = path.trim().trim('/')
        val first = when {
            normalized.isBlank() -> "."
            isDirectory == true -> normalized
            isDirectory == false -> normalized.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "." }
            else -> normalized
        }
        val dirs = linkedSetOf<String>()
        var current = first
        while (current.isNotBlank()) {
            dirs.add(current)
            if (current == ".") break
            current = current.substringBeforeLast('/', missingDelimiterValue = "")
        }
        dirs.add(".")
        return dirs.toList()
    }

    fun selectedName(path: String, isDirectory: Boolean?): String {
        val normalized = path.trim().trim('/')
        val selected = when {
            normalized.isBlank() -> "."
            isDirectory == false -> normalized.substringBeforeLast('/', missingDelimiterValue = normalized)
            else -> normalized
        }
        return selected.substringAfterLast('/').ifBlank { "." }
    }
}

@Service(Service.Level.PROJECT)
class RemoteGitStatusService(
    private val project: Project,
) {
    fun overview(repo: RemoteGitRepository): RemoteGitOverview {
        val output = project.service<RemoteGitCommandService>()
            .shell(RemoteGitCommands.overview(repo.shellPath), 60)
            .requireSuccess()
            .output
        val statusOutput = RemoteGitParsers.section(output, "__VW_GIT_STATUS__", "__VW_GIT_BRANCHES__")
        val branchOutput = RemoteGitParsers.section(output, "__VW_GIT_BRANCHES__", null)
        return RemoteGitOverview(
            status = RemoteGitParsers.parseStatusSnapshot(statusOutput),
            branches = RemoteGitParsers.parseBranches(branchOutput),
        )
    }

    fun status(repo: RemoteGitRepository): RemoteGitStatusSnapshot {
        val output = project.service<RemoteGitCommandService>()
            .git(repo.shellPath, RemoteGitCommands.status(), 60)
            .requireSuccess()
            .output
        return RemoteGitParsers.parseStatusSnapshot(output)
    }

    fun changes(repo: RemoteGitRepository): List<RemoteGitChange> {
        val output = project.service<RemoteGitCommandService>()
            .git(repo.shellPath, RemoteGitCommands.status(), 60)
            .requireSuccess()
            .output
        return RemoteGitParsers.parseStatus(output)
    }

    fun branches(repo: RemoteGitRepository): List<RemoteGitBranch> {
        val output = project.service<RemoteGitCommandService>()
            .git(repo.shellPath, listOf("branch", "--all", "--no-color"), 60)
            .requireSuccess()
            .output
        return RemoteGitParsers.parseBranches(output)
    }

    fun history(repo: RemoteGitRepository, limit: Int = 20): List<RemoteGitCommit> {
        val format = "%h%x1f%an%x1f%cr%x1f%d%x1f%s"
        val output = project.service<RemoteGitCommandService>()
            .git(repo.shellPath, listOf("log", "--decorate=short", "--date=relative", "--pretty=format:$format", "-n", limit.toString()), 60)
            .requireSuccess()
            .output
        return RemoteGitParsers.parseLog(output)
    }

    fun diff(repo: RemoteGitRepository, change: RemoteGitChange): String {
        val args = if (change.staged) {
            listOf("diff", "--cached", "--", change.path)
        } else {
            listOf("diff", "--", change.path)
        }
        return project.service<RemoteGitCommandService>()
            .git(repo.shellPath, args, 60)
            .requireSuccess()
            .output
    }
}

data class RemoteGitOverview(
    val status: RemoteGitStatusSnapshot,
    val branches: List<RemoteGitBranch>,
)

@Service(Service.Level.PROJECT)
class RemoteGitActionsService(
    private val project: Project,
) {
    fun stage(repo: RemoteGitRepository, paths: List<String>): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, RemoteGitCommands.stage(paths), 60)

    fun stageAll(repo: RemoteGitRepository): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, RemoteGitCommands.stageAll(), 60)

    fun unstage(repo: RemoteGitRepository, paths: List<String>): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, RemoteGitCommands.unstage(paths), 60)

    fun unstageAll(repo: RemoteGitRepository): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, RemoteGitCommands.unstageAll(), 60)

    fun discard(repo: RemoteGitRepository, paths: List<String>): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, RemoteGitCommands.discardTracked(paths), 60)

    fun discardUntracked(repo: RemoteGitRepository, paths: List<String>): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, RemoteGitCommands.discardUntracked(paths), 60)

    fun commit(repo: RemoteGitRepository, summary: String, description: String): RemoteGitCommandResult {
        val args = mutableListOf("commit", "-m", summary)
        if (description.isNotBlank()) args.addAll(listOf("-m", description))
        return project.service<RemoteGitCommandService>().git(repo.shellPath, args, 90)
    }

    fun pull(repo: RemoteGitRepository): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("pull", "--ff-only"), 180)

    fun pullFrom(repo: RemoteGitRepository, remote: String, branch: String): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, RemoteGitCommands.pullFrom(remote, branch), 180)

    fun fetch(repo: RemoteGitRepository): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("fetch", "--prune"), 180)

    fun push(repo: RemoteGitRepository): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("push"), 180)

    fun pushWithUpstream(repo: RemoteGitRepository, remote: String, branch: String): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("push", "-u", remote, branch), 180)

    fun pushHeadToRemoteBranch(repo: RemoteGitRepository, remote: String, remoteBranch: String): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("push", remote, "HEAD:$remoteBranch"), 180)

    fun checkout(repo: RemoteGitRepository, branch: String): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("checkout", branch), 120)

    fun checkoutRemoteTracking(repo: RemoteGitRepository, remoteBranch: String): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("checkout", "--track", remoteBranch), 120)

    fun createBranch(repo: RemoteGitRepository, branch: String): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("checkout", "-b", branch), 120)

    fun createBranchFrom(repo: RemoteGitRepository, branch: String, base: String): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, RemoteGitCommands.createBranchFrom(branch, base), 120)

    fun deleteBranch(repo: RemoteGitRepository, branch: String): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("branch", "-d", branch), 120)

    fun stash(repo: RemoteGitRepository): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("stash", "push", "-u"), 120)

    fun stashPop(repo: RemoteGitRepository): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(repo.shellPath, listOf("stash", "pop"), 120)

    fun cloneInto(parentShellPath: String, url: String): RemoteGitCommandResult =
        project.service<RemoteGitCommandService>().git(parentShellPath, listOf("clone", url), 600)
}

object RemoteGitCommands {
    fun overview(repoPath: String): String {
        val git = "GIT_PAGER=cat PAGER=cat git --no-pager -C ${RemoteShell.quote(repoPath)}"
        return buildString {
            append("printf '\\n__VW_GIT_STATUS__\\n'\n")
            append("$git status --porcelain=v1 -z --branch\n")
            append("printf '\\n__VW_GIT_BRANCHES__\\n'\n")
            append("$git branch --all --no-color\n")
        }
    }

    fun status(): List<String> = listOf("status", "--porcelain=v1", "-z", "--branch")
    fun stage(paths: List<String>): List<String> = listOf("add", "--") + paths
    fun stageAll(): List<String> = listOf("add", "--all")
    fun unstage(paths: List<String>): List<String> = listOf("restore", "--staged", "--") + paths
    fun unstageAll(): List<String> = listOf("restore", "--staged", "--", ".")
    fun discardTracked(paths: List<String>): List<String> = listOf("restore", "--") + paths
    fun discardUntracked(paths: List<String>): List<String> = listOf("clean", "-f", "--") + paths
    fun pullFrom(remote: String, branch: String): List<String> = listOf("pull", "--ff-only", remote, branch)
    fun createBranchFrom(branch: String, base: String): List<String> =
        listOf("checkout", "-b", branch, "--no-track", base)
}
