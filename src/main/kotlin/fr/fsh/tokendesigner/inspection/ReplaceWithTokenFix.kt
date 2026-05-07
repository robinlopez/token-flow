package fr.fsh.tokendesigner.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import fr.fsh.tokendesigner.model.TokenKind

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

        val replacement = when (tokenKind) {
            TokenKind.SCSS_VARIABLE -> tokenName            // already includes leading `$`
            TokenKind.CSS_CUSTOM_PROPERTY -> "var($tokenName)"
            TokenKind.JS_OBJECT_PATH -> "'{$tokenName}'"
        }
        document.replaceString(absStart, absEnd, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
