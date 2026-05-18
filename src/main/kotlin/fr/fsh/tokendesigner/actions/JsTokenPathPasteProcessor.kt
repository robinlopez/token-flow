package fr.fsh.tokendesigner.actions

import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import fr.fsh.tokendesigner.util.TokenInsertion
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/**
 * Data attached to a drag/copy of a `JS_OBJECT_PATH` token. Lets us rewrap
 * the pasted text on the receiving side based on the drop position — e.g.
 * `'{path}'` becomes `${dt('path')}` when dropped inside a backtick template
 * literal in TS/JS.
 */
class JsTokenPathTransferableData(val path: String) : TextBlockTransferableData {
    override fun getFlavor(): DataFlavor = FLAVOR

    companion object {
        val FLAVOR: DataFlavor = DataFlavor(
            JsTokenPathTransferableData::class.java,
            "design-token-js-path",
        )
    }
}

/**
 * Intercepts pastes/drops carrying a [JsTokenPathTransferableData] and replaces
 * the just-inserted text with the form appropriate to the paste location.
 *
 * Triggers only when the source Transferable advertises our custom flavor —
 * keeps the post-processor inert for regular copy/paste operations.
 */
class JsTokenPathPasteProcessor : CopyPastePostProcessor<JsTokenPathTransferableData>() {

    override fun collectTransferableData(
        file: PsiFile,
        editor: Editor,
        startOffsets: IntArray,
        endOffsets: IntArray,
    ): List<JsTokenPathTransferableData> = emptyList()

    override fun extractTransferableData(content: Transferable): List<JsTokenPathTransferableData> {
        if (!content.isDataFlavorSupported(JsTokenPathTransferableData.FLAVOR)) return emptyList()
        val data = try {
            content.getTransferData(JsTokenPathTransferableData.FLAVOR)
        } catch (_: Throwable) {
            null
        } as? JsTokenPathTransferableData ?: return emptyList()
        return listOf(data)
    }

    override fun processTransferableData(
        project: Project,
        editor: Editor,
        bounds: RangeMarker,
        caretOffset: Int,
        indented: Ref<in Boolean>,
        values: List<JsTokenPathTransferableData>,
    ) {
        val data = values.firstOrNull() ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val ext = file.extension
        // The drop position is captured BEFORE we mutate the document: the
        // bounds' start sits at the freshly pasted text, but
        // `templateContextAt` walks from offset 0 so it reads the user's
        // existing context, not the insertion itself.
        val newText = TokenInsertion.wrapJsObjectPath(
            tokenName = data.path,
            fileExtension = ext,
            text = editor.document.charsSequence,
            offset = bounds.startOffset,
        )
        WriteCommandAction.runWriteCommandAction(project, "Wrap Design Token", null, {
            editor.document.replaceString(bounds.startOffset, bounds.endOffset, newText)
        })
    }
}
