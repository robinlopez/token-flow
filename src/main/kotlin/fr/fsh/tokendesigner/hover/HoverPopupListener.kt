package fr.fsh.tokendesigner.hover

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.Alarm
import fr.fsh.tokendesigner.actions.TokenInfoShower
import fr.fsh.tokendesigner.scanner.TokenLocator
import fr.fsh.tokendesigner.settings.TokenSelectorSettings
import java.awt.Point

/**
 * Optional hover trigger: when enabled in settings, hovering over a design
 * token for `hoverDelayMs` opens the alternatives popup automatically.
 *
 * Implemented as a [ProjectActivity] that registers an [EditorFactoryListener]
 * scoped to the project. The listener attaches a per-editor mouse motion
 * listener that schedules the popup via an [Alarm]. Editors already open at
 * activity time are also wired up.
 */
class HoverPopupStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val factory = EditorFactory.getInstance()
        val listener = HoverEditorFactoryListener(project)
        factory.addEditorFactoryListener(listener, project)
        // Wire up editors that were already open before this activity ran.
        factory.allEditors
            .filter { it.project == null || it.project === project }
            .forEach(listener::attach)
    }
}

private class HoverEditorFactoryListener(private val project: Project) : EditorFactoryListener {

    private val handlers = mutableMapOf<Editor, HoverMouseListener>()

    override fun editorCreated(event: EditorFactoryEvent) = attach(event.editor)

    override fun editorReleased(event: EditorFactoryEvent) {
        val handler = handlers.remove(event.editor) ?: return
        event.editor.removeEditorMouseMotionListener(handler)
        handler.dispose()
    }

    fun attach(editor: Editor) {
        if (editor.project != null && editor.project !== project) return
        if (handlers.containsKey(editor)) return
        val handler = HoverMouseListener(project, editor)
        editor.addEditorMouseMotionListener(handler)
        handlers[editor] = handler
    }
}

private class HoverMouseListener(
    private val project: Project,
    private val editor: Editor,
) : EditorMouseMotionListener, Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var lastOffset = -1
    private var lastMouseScreen: Point? = null

    override fun mouseMoved(e: EditorMouseEvent) {
        val settings = TokenSelectorSettings.getInstance(project)
        if (!settings.openOnHover) {
            cancel()
            return
        }
        val offset = e.offset
        if (offset < 0) {
            cancel()
            return
        }
        // Capture the on-screen mouse location so the popup anchors at the
        // hovered token rather than at the caret (which may be elsewhere).
        lastMouseScreen = e.mouseEvent.locationOnScreen

        if (offset == lastOffset) return
        lastOffset = offset
        alarm.cancelAllRequests()

        val ext = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(editor.document)?.extension
        val hit = TokenLocator.find(editor.document, offset, ext) ?: return
        alarm.addRequest({ trigger(hit) }, settings.hoverDelayMs)
    }

    private fun trigger(hit: TokenLocator.Hit) {
        if (project.isDisposed || editor.isDisposed) return
        TokenInfoShower.show(project, editor, hit, lastMouseScreen)
    }

    private fun cancel() {
        alarm.cancelAllRequests()
        lastOffset = -1
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }
}
