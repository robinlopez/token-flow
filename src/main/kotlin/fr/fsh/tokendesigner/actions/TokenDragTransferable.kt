package fr.fsh.tokendesigner.actions

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.model.TokenReference
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/**
 * Builds the [Transferable] used when the user drags a token out of the
 * Library. For [TokenKind.JS_OBJECT_PATH] tokens we attach a custom
 * [JsTokenPathTransferableData] flavor so [JsTokenPathPasteProcessor] can
 * rewrap the inserted text at the drop site. Every other kind drops a plain
 * string — the canonical form is already context-correct (e.g. `var(--x)`).
 */
object TokenDragTransferable {
    fun forToken(token: DesignToken): Transferable {
        val canonical = TokenReference.expression(token)
        if (token.kind != TokenKind.JS_OBJECT_PATH) return StringSelection(canonical)
        val data = JsTokenPathTransferableData(token.name)
        return object : Transferable {
            private val flavors = arrayOf(JsTokenPathTransferableData.FLAVOR, DataFlavor.stringFlavor)
            override fun getTransferDataFlavors(): Array<DataFlavor> = flavors
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in flavors
            override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
                JsTokenPathTransferableData.FLAVOR -> data
                DataFlavor.stringFlavor -> canonical
                else -> throw UnsupportedFlavorException(flavor)
            }
        }
    }
}
