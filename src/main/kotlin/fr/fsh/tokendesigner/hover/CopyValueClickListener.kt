package fr.fsh.tokendesigner.hover

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import fr.fsh.tokendesigner.actions.CopyTokenValueShower
import fr.fsh.tokendesigner.scanner.TokenLocator
import fr.fsh.tokendesigner.settings.TokenSelectorSettings

/**
 * Issue #27 — modifier+click to copy a token's resolved value.
 *
 * Registered as a [ProjectActivity] that wires an [EditorMouseListener] onto
 * every editor (mirroring [HoverPopupStartup]). On a left-click carrying the
 * configured [fr.fsh.tokendesigner.settings.CopyClickShortcut] over a token
 * reference, it consumes the click and opens [CopyTokenValueShower] instead of
 * letting the IDE move the caret / add a multi-caret.
 */
class CopyValueClickStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val factory = EditorFactory.getInstance()
        val listener = CopyClickEditorFactoryListener(project)
        factory.addEditorFactoryListener(listener, project)
        factory.allEditors
            .filter { it.project == null || it.project === project }
            .forEach(listener::attach)
    }
}

private class CopyClickEditorFactoryListener(private val project: Project) : EditorFactoryListener {

    private val handlers = mutableMapOf<Editor, CopyClickMouseListener>()

    override fun editorCreated(event: EditorFactoryEvent) = attach(event.editor)

    override fun editorReleased(event: EditorFactoryEvent) {
        val handler = handlers.remove(event.editor) ?: return
        event.editor.removeEditorMouseListener(handler)
    }

    fun attach(editor: Editor) {
        if (editor.project != null && editor.project !== project) return
        if (handlers.containsKey(editor)) return
        val handler = CopyClickMouseListener(project, editor)
        editor.addEditorMouseListener(handler)
        handlers[editor] = handler
    }
}

private class CopyClickMouseListener(
    private val project: Project,
    private val editor: Editor,
) : EditorMouseListener {

    override fun mousePressed(e: EditorMouseEvent) {
        val settings = TokenSelectorSettings.getInstance(project)
        if (!settings.copyClickEnabled) return
        if (!settings.copyClickShortcut.matches(e.mouseEvent)) return

        val offset = e.offset
        if (offset < 0) return
        val ext = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(editor.document)?.extension
        val hit = TokenLocator.find(editor.document, offset, ext) ?: return

        // Swallow the click so the IDE doesn't reposition the caret or start a
        // rectangular/multi-caret selection under the same modifiers.
        e.consume()
        val screen = runCatching { e.mouseEvent.locationOnScreen }.getOrNull()
        CopyTokenValueShower.show(project, editor, hit, screen)
    }
}
