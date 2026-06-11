package dev.vertexworkbench.pycharm.terminal

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.jediterm.terminal.ui.JediTermWidget
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class WorkbenchTerminalFileEditor(
    private val file: WorkbenchTerminalVirtualFile,
) : UserDataHolderBase(), FileEditor {
    private val widget = JediTermWidget(JBTerminalSystemSettingsProvider())

    init {
        widget.createTerminalSession(file.connector)
        widget.start()
    }

    override fun getComponent(): JComponent = widget

    override fun getFile(): VirtualFile = file

    override fun getPreferredFocusedComponent(): JComponent = widget

    override fun getName(): String = "Vertex Terminal ${file.terminalName}"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.connector.isConnected

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        file.connector.close()
        runCatching { widget.close() }
    }
}
