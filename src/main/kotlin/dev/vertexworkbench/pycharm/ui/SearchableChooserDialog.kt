package dev.vertexworkbench.pycharm.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

data class SearchableChooserResult<T>(
    val value: T,
    val checked: Boolean,
)

class SearchableChooserDialog<T>(
    project: Project,
    title: String,
    private val values: List<T>,
    private val initialValue: T?,
    private val displayText: (T) -> String,
    private val searchText: (T) -> String = displayText,
    checkboxText: String? = null,
    checkboxSelected: Boolean = false,
) : DialogWrapper(project) {
    private val checkbox = checkboxText?.let { JCheckBox(it, checkboxSelected) }
    private val listModel = DefaultListModel<T>()
    private val searchField = JBTextField().apply {
        emptyText.text = "Search"
    }
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 12
        cellRenderer = javax.swing.DefaultListCellRenderer().also { renderer ->
            @Suppress("UNCHECKED_CAST")
            renderer as javax.swing.ListCellRenderer<in T>
        }
    }

    init {
        this.title = title
        updateFilter()
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateFilter()
            override fun removeUpdate(e: DocumentEvent) = updateFilter()
            override fun changedUpdate(e: DocumentEvent) = updateFilter()
        })
        init()
        initialValue?.let { list.setSelectedValue(it, true) }
        if (list.selectedIndex < 0 && values.isNotEmpty()) {
            list.selectedIndex = 0
        }
    }

    override fun createCenterPanel(): JComponent {
        list.cellRenderer = Renderer(displayText)
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(640, 360)
        panel.add(JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            add(searchField, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        panel.add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
        checkbox?.let { panel.add(it, BorderLayout.SOUTH) }
        SwingUtilities.invokeLater {
            searchField.requestFocusInWindow()
            searchField.selectAll()
        }
        return panel
    }

    fun selectedValue(): T? =
        if (showAndGet()) list.selectedValue else null

    fun selectedValueWithCheckbox(): SearchableChooserResult<T>? =
        if (showAndGet()) {
            SearchableChooserResult(list.selectedValue, checkbox?.isSelected ?: false)
        } else {
            null
        }

    override fun doValidate(): ValidationInfo? =
        if (list.selectedValue == null) ValidationInfo("No matching item selected", searchField) else null

    private fun updateFilter() {
        val previous = list.selectedValue
        val query = searchField.text.trim().lowercase()
        listModel.clear()
        values
            .filter { value -> query.isBlank() || searchText(value).lowercase().contains(query) }
            .forEach { listModel.addElement(it) }

        when {
            previous != null && listModel.contains(previous) -> list.setSelectedValue(previous, true)
            initialValue != null && listModel.contains(initialValue) -> list.setSelectedValue(initialValue, true)
            listModel.size() > 0 -> list.selectedIndex = 0
        }
    }

    private class Renderer<T>(
        private val displayText: (T) -> String,
    ) : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            @Suppress("UNCHECKED_CAST")
            text = value?.let { displayText(it as T) }.orEmpty()
            return component
        }
    }
}
