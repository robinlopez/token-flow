package fr.fsh.tokendesigner.completion

import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.startup.ProjectActivity
import fr.fsh.tokendesigner.actions.TokenAlternativesShower
import fr.fsh.tokendesigner.settings.TokenSelectorSettings
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Watches for document changes that look like a CSS property auto-completion
 * insertion (e.g. `width: ;`) and opens the value-completion popup when the
 * caret ends up in the value position.
 *
 * This covers the case where IntelliJ's own CSS completion inserts a property
 * and the [ValueCompletionTypedHandler] cannot fire (no user-typed character).
 */
class ValueCompletionStartup : ProjectActivity {

    override suspend fun execute(project: com.intellij.openapi.project.Project) {
        val parentDisposable = Disposer.newDisposable("ValueCompletionEditorWatcher")
        // Dispose when the project closes.
        @Suppress("DEPRECATION")
        com.intellij.openapi.util.Disposer.register(project as Disposable, parentDisposable)

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            private var pendingTimer: Timer? = null

            override fun documentChanged(event: DocumentEvent) {
                val newText = event.newFragment.toString()
                // Quick check: only react to insertions that contain ": "
                // (typical of property completion like "width: ;").
                if (": " !in newText) return

                val document = event.document
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                val ext = file.extension?.lowercase() ?: return
                if (ext !in TARGET_EXTS) return

                val settings = TokenSelectorSettings.getInstance(project)
                if (!settings.valueCompletionEnabled) return

                // Debounce: cancel any pending check and schedule a new one.
                pendingTimer?.stop()
                pendingTimer = Timer(120) {
                    checkAndShowPopup(project, document)
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        }, parentDisposable)
    }

    private fun checkAndShowPopup(
        project: com.intellij.openapi.project.Project,
        document: com.intellij.openapi.editor.Document,
    ) {
        // Find the editor showing this document.
        val editor = EditorFactory.getInstance().getEditors(document, project)
            .firstOrNull() ?: return
        if (editor.isDisposed) return

        val offset = editor.caretModel.offset
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineText = document.charsSequence.subSequence(lineStart, offset).toString()

        // Bail out if in Mode A territory.
        if (CSS_VAR_PREFIX.find(lineText) != null) return
        if (SCSS_VAR_PREFIX.find(lineText) != null) return

        val valueMatch = VALUE_CONTEXT.find(lineText) ?: return
        val propertyName = valueMatch.groupValues[1]
        val partialValue = valueMatch.groupValues[2].trim()

        // Only trigger for empty value (the TypedHandler handles non-empty).
        if (partialValue.isNotEmpty()) return

        val settings = TokenSelectorSettings.getInstance(project)
        if (partialValue.length < settings.valueCompletionMinChars) return

        val valueStartInLine = valueMatch.groups[2]!!.range.first
        val replaceStart = lineStart + valueStartInLine
        val replaceEnd = offset

        TokenAlternativesShower.showForPartialValue(
            project = project,
            editor = editor,
            partialValue = partialValue,
            propertyName = propertyName,
            replaceStart = replaceStart,
            replaceEnd = replaceEnd,
        )
    }

    private companion object {
        val TARGET_EXTS = setOf("scss", "sass", "css", "ts", "tsx", "js", "jsx", "mjs", "cjs")
        val VALUE_CONTEXT = Regex("""([a-zA-Z][a-zA-Z-]*)\s*:\s*([^;{}'"`()\n]*)$""")
        val CSS_VAR_PREFIX = Regex("var\\(\\s*--[a-zA-Z0-9_-]*$")
        val SCSS_VAR_PREFIX = Regex("(?:^|[\\s,;:({\\[])\\$[a-zA-Z][a-zA-Z0-9_-]*$")
    }
}
