package fr.fsh.tokendesigner.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.model.TokenReference

/**
 * Replaces a hardcoded value with the appropriate token reference:
 *  - SCSS variable: `$name`
 *  - CSS custom property: `var(--name)`
 *
 * The replacement is the literal under the inspection's TextRange; we never
 * touch surrounding code so the formatter can leave the rest alone.
 */
class ReplaceWithTokenFix(
    private val tokenName: String,
    private val tokenKind: TokenKind,
) : LocalQuickFix {

    override fun getName(): String = "Replace with $tokenName"

    override fun getFamilyName(): String = "Replace with design token"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        val rangeInElement = descriptor.textRangeInElement
        val absStart = element.textRange.startOffset + rangeInElement.startOffset
        val absEnd = element.textRange.startOffset + rangeInElement.endOffset

        val replacement = TokenReference.expression(tokenName, tokenKind)
        document.replaceString(absStart, absEnd, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
