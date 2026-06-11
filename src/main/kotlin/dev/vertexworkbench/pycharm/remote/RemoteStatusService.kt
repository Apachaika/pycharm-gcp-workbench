package dev.vertexworkbench.pycharm.remote

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.auth.GcloudAuthService
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService
import dev.vertexworkbench.pycharm.model.RemoteResourceMetrics
import dev.vertexworkbench.pycharm.model.RemoteStatusSnapshot
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService

@Service(Service.Level.PROJECT)
class RemoteStatusService(
    private val project: Project,
) {
    fun metrics(): RemoteResourceMetrics {
        val script = """
            read cpu user nice system idle iowait irq softirq steal guest guest_nice < /proc/stat
            total1=${'$'}((user+nice+system+idle+iowait+irq+softirq+steal))
            idle1=${'$'}((idle+iowait))
            sleep 0.4
            read cpu user nice system idle iowait irq softirq steal guest guest_nice < /proc/stat
            total2=${'$'}((user+nice+system+idle+iowait+irq+softirq+steal))
            idle2=${'$'}((idle+iowait))
            dt=${'$'}((total2-total1))
            di=${'$'}((idle2-idle1))
            if [ "${'$'}dt" -gt 0 ]; then cpu_pct=${'$'}(( (100*(dt-di))/dt )); else cpu_pct=0; fi
            mem_pct=$(free -m | awk '/Mem:/ { if (${'$'}2 > 0) printf "%d", (${'$'}3 * 100 / ${'$'}2); else print 0 }')
            mem_line=$(free -h | awk '/Mem:/ {print ${'$'}3 "|" ${'$'}2}')
            mem_used=$(printf '%s' "${'$'}mem_line" | awk -F'|' '{print ${'$'}1}')
            mem_total=$(printf '%s' "${'$'}mem_line" | awk -F'|' '{print ${'$'}2}')
            disk_line=$(df -hP . | awk 'NR==2 {gsub("%","",${'$'}5); print ${'$'}3 "|" ${'$'}2 "|" ${'$'}5}')
            disk_used=$(printf '%s' "${'$'}disk_line" | awk -F'|' '{print ${'$'}1}')
            disk_total=$(printf '%s' "${'$'}disk_line" | awk -F'|' '{print ${'$'}2}')
            disk_pct=$(printf '%s' "${'$'}disk_line" | awk -F'|' '{print ${'$'}3}')
            printf '__VW_METRICS__%s|%s|%s|%s|%s|%s|%s\n' "${'$'}cpu_pct" "${'$'}{mem_pct:-0}" "${'$'}{disk_pct:-0}" "${'$'}{mem_used:---}" "${'$'}{mem_total:---}" "${'$'}{disk_used:---}" "${'$'}{disk_total:---}"
        """.trimIndent()
        val output = project.service<RemoteCommandService>().script(script, 10).requireSuccess().output
        val values = output.lines()
            .firstOrNull { it.startsWith("__VW_METRICS__") }
            ?.removePrefix("__VW_METRICS__")
            ?.split('|')
            ?: emptyList()
        val cpu = values.getOrNull(0).toPercent()
        val memory = values.getOrNull(1).toPercent()
        val disk = values.getOrNull(2).toPercent()
        return RemoteResourceMetrics(
            cpuPercent = cpu,
            memoryPercent = memory,
            diskPercent = disk,
            memoryUsed = values.getOrNull(3).orDash(),
            memoryTotal = values.getOrNull(4).orDash(),
            diskUsed = values.getOrNull(5).orDash(),
            diskTotal = values.getOrNull(6).orDash(),
            label = "CPU $cpu%  RAM ${values.getOrNull(3).orDash()}/${values.getOrNull(4).orDash()}  Disk ${values.getOrNull(5).orDash()}/${values.getOrNull(6).orDash()}",
        )
    }

    fun snapshot(): RemoteStatusSnapshot {
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: error("Connect to a Vertex Workbench instance first.")
        val output = project.service<RemoteCommandService>().script(
            """
            printf '__VW_PYTHON__%s\n' "$(python --version 2>&1 || true)"
            printf '__VW_JUPYTER__%s\n' "$(jupyter --version 2>&1 | head -n 1 || true)"
            printf '__VW_CPU__%s\n' "$( (top -bn1 2>/dev/null || top -l 1 2>/dev/null) | head -n 5 | tr '\n' ' ' | sed 's/[[:space:]]\+/ /g' || true)"
            printf '__VW_MEMORY__%s\n' "$(free -h 2>/dev/null | awk '/Mem:/ {print $$2 " total, " $$3 " used, " $$4 " free"}' || true)"
            printf '__VW_DISK__%s\n' "$(df -h . 2>/dev/null | tail -n 1 | awk '{print $$2 " total, " $$3 " used, " $$4 " free (" $$5 ")"}' || true)"
            printf '__VW_GPU__%s\n' "$(nvidia-smi --query-gpu=name,memory.used,memory.total --format=csv,noheader 2>/dev/null | head -n 3 | tr '\n' '; ' || true)"
            printf '__VW_UPTIME__%s\n' "$(uptime 2>/dev/null || true)"
            """.trimIndent(),
            60,
        ).requireSuccess().output
        val fields = parseFields(output)
        return RemoteStatusSnapshot(
            account = runCatching { project.service<GcloudAuthService>().activeAccount() }.getOrElse { "unknown" },
            projectId = connection.instance.projectId,
            instanceName = connection.instance.name,
            instanceState = project.service<WorkbenchConnectionService>().workbenchState,
            python = fields["PYTHON"].orEmpty().ifBlank { "unknown" },
            jupyter = fields["JUPYTER"].orEmpty().ifBlank { "unknown" },
            cpu = fields["CPU"].orEmpty().ifBlank { "unknown" },
            memory = fields["MEMORY"].orEmpty().ifBlank { "unknown" },
            disk = fields["DISK"].orEmpty().ifBlank { "unknown" },
            gpu = fields["GPU"].orEmpty().ifBlank { "not detected" },
            uptime = fields["UPTIME"].orEmpty().ifBlank { "unknown" },
            lastSync = project.service<RemoteFileSyncService>().lastSyncDescription(),
        )
    }

    private fun parseFields(output: String): Map<String, String> =
        output.lines()
            .mapNotNull { line ->
                val match = Regex("__VW_([A-Z]+)__(.*)").matchEntire(line.trim()) ?: return@mapNotNull null
                match.groupValues[1] to match.groupValues[2].trim()
            }
            .toMap()

    private fun String?.toPercent(): Int =
        this?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: 0

    private fun String?.orDash(): String =
        this?.trim()?.takeIf { it.isNotBlank() } ?: "--"
}
