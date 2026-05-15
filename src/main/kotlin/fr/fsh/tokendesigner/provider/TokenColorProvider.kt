package fr.fsh.tokendesigner.provider

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.ui.ColorParser
import java.awt.Color

class TokenColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element !is LeafPsiElement) return null

        val text = element.text

        if (!text.startsWith("$") && !text.startsWith("--")) return null

        val file = element.containingFile?.virtualFile ?: return null
        val tokens = TokenIndex.getInstance(element.project).get(file)
        
        val token = tokens.find { it.name == text } ?: return null
        if (token.category != TokenCategory.COLOR) return null
        
        return ColorParser.parse(token.resolvedValue)
    }

    override fun setColorTo(element: PsiElement, color: Color) {
    }
}
