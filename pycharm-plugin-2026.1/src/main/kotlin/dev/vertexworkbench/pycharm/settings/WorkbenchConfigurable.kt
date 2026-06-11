package dev.vertexworkbench.pycharm.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.vertexworkbench.pycharm.auth.GcloudPathResolver
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService
import dev.vertexworkbench.pycharm.imports.RemoteImportIndexService
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class WorkbenchConfigurable(
    private val project: Project,
) : Configurable {
    private var gcloudPathField: JTextField? = null
    private var proxyPortSpinner: JSpinner? = null
    private var autoIndexImportsBox: JCheckBox? = null
    private var importRootsField: JTextField? = null
    private var syncIgnoreField: JTextField? = null
    private var maxSyncSizeSpinner: JSpinner? = null

    override fun getDisplayName(): String = "Vertex Workbench"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        val gcloud = JTextField(30)
        val detectGcloud = JButton("Auto-detect").apply {
            addActionListener {
                val detected = GcloudPathResolver.detect()
                if (detected == null) {
                    gcloud.text = "gcloud"
                    Messages.showInfoMessage(
                        project,
                        "Google Cloud CLI was not found in PATH or common install locations. Install it or set the path manually.",
                        "Vertex Workbench",
                    )
                } else {
                    gcloud.text = detected
                }
            }
        }
        val port = JSpinner(SpinnerNumberModel(0, 0, 65535, 1))
        val autoIndex = JCheckBox("Auto-index remote Python imports after connect")
        val importRoots = JTextField(30)
        val syncIgnore = JTextField(30)
        val maxSyncSize = JSpinner(SpinnerNumberModel(25, 1, 1024, 1))
        gcloudPathField = gcloud
        proxyPortSpinner = port
        autoIndexImportsBox = autoIndex
        importRootsField = importRoots
        syncIgnoreField = syncIgnore
        maxSyncSizeSpinner = maxSyncSize

        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JLabel("gcloud path:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(gcloud, constraints)
        constraints.gridx = 2
        constraints.weightx = 0.0
        panel.add(detectGcloud, constraints)

        constraints.gridx = 0
        constraints.gridy = 1
        constraints.weightx = 0.0
        panel.add(JLabel("Proxy port (0 = auto):"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(port, constraints)

        constraints.gridx = 0
        constraints.gridy = 2
        constraints.weightx = 0.0
        panel.add(JLabel("Import indexing:"), constraints)
        constraints.gridx = 1
        constraints.gridwidth = 2
        constraints.weightx = 1.0
        panel.add(autoIndex, constraints)
        constraints.gridwidth = 1

        constraints.gridx = 0
        constraints.gridy = 3
        constraints.weightx = 0.0
        panel.add(JLabel("Import roots:"), constraints)
        constraints.gridx = 1
        constraints.gridwidth = 2
        constraints.weightx = 1.0
        panel.add(importRoots, constraints)
        constraints.gridwidth = 1

        constraints.gridx = 0
        constraints.gridy = 4
        constraints.weightx = 0.0
        panel.add(JLabel("Sync ignore:"), constraints)
        constraints.gridx = 1
        constraints.gridwidth = 2
        constraints.weightx = 1.0
        panel.add(syncIgnore, constraints)
        constraints.gridwidth = 1

        constraints.gridx = 0
        constraints.gridy = 5
        constraints.weightx = 0.0
        panel.add(JLabel("Max sync file MB:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(maxSyncSize, constraints)

        val disconnect = JButton("Disconnect local session").apply {
            addActionListener {
                project.service<WorkbenchConnectionService>().stopProxy()
                Messages.showInfoMessage(project, "Vertex Workbench local session disconnected.", "Vertex Workbench")
            }
        }
        val reindexImports = JButton("Reindex Imports").apply {
            addActionListener {
                try {
                    project.service<RemoteImportIndexService>().reindex()
                    Messages.showInfoMessage(project, "Remote Python imports index was rebuilt.", "Vertex Workbench")
                } catch (t: Throwable) {
                    Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
                }
            }
        }
        val advanced = JPanel().apply {
            add(disconnect)
            add(reindexImports)
        }

        constraints.gridx = 0
        constraints.gridy = 6
        constraints.weightx = 0.0
        panel.add(JLabel("Advanced:"), constraints)
        constraints.gridx = 1
        constraints.gridwidth = 2
        constraints.weightx = 1.0
        panel.add(advanced, constraints)
        constraints.gridwidth = 1

        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val state = project.service<WorkbenchSettings>().state
        return gcloudPathField?.text != state.gcloudPath ||
            (proxyPortSpinner?.value as? Int ?: 0) != state.proxyPort ||
            autoIndexImportsBox?.isSelected != state.autoIndexImports ||
            importRootsField?.text != state.importIndexRoots.joinToString(",") ||
            syncIgnoreField?.text != state.syncIgnorePatterns.joinToString(",") ||
            (maxSyncSizeSpinner?.value as? Int ?: 25) != state.maxSyncFileSizeMb
    }

    override fun apply() {
        val state = project.service<WorkbenchSettings>().state
        state.gcloudPath = gcloudPathField?.text?.trim()?.takeIf { it.isNotBlank() } ?: "gcloud"
        state.proxyPort = proxyPortSpinner?.value as? Int ?: 0
        state.autoIndexImports = autoIndexImportsBox?.isSelected == true
        state.importIndexRoots = splitCsv(importRootsField?.text).toMutableList()
        state.syncIgnorePatterns = splitCsv(syncIgnoreField?.text).toMutableList()
        state.maxSyncFileSizeMb = maxSyncSizeSpinner?.value as? Int ?: 25
    }

    override fun reset() {
        val state = project.service<WorkbenchSettings>().state
        gcloudPathField?.text = state.gcloudPath
        proxyPortSpinner?.value = state.proxyPort
        autoIndexImportsBox?.isSelected = state.autoIndexImports
        importRootsField?.text = state.importIndexRoots.joinToString(",")
        syncIgnoreField?.text = state.syncIgnorePatterns.joinToString(",")
        maxSyncSizeSpinner?.value = state.maxSyncFileSizeMb
    }

    override fun disposeUIResources() {
        gcloudPathField = null
        proxyPortSpinner = null
        autoIndexImportsBox = null
        importRootsField = null
        syncIgnoreField = null
        maxSyncSizeSpinner = null
    }

    private fun splitCsv(value: String?): List<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
}
