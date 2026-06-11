package dev.vertexworkbench.pycharm.terminal

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile

class WorkbenchTerminalFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        file is WorkbenchTerminalVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        WorkbenchTerminalFileEditor(file as WorkbenchTerminalVirtualFile)

    override fun getEditorTypeId(): String = "vertex-workbench-terminal"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
