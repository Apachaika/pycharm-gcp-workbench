package dev.vertexworkbench.pycharm.terminal

import com.intellij.testFramework.LightVirtualFile

class WorkbenchTerminalVirtualFile(
    val terminalName: String,
    val connector: JupyterTerminadoTtyConnector,
) : LightVirtualFile("Vertex Terminal $terminalName")
