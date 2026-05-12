package fr.fsh.tokendesigner.completion

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import fr.fsh.tokendesigner.actions.TokenAlternativesShower
import fr.fsh.tokendesigner.settings.TokenSelectorSettings

/**
 * Triggers the **Token Alternatives popup** (the same categorised popup as
 * Alt+T) when the user types a value after a CSS/SCSS/JS property colon.
 *
 * Example: typing `padding: 4px` → opens the categorised popup showing all
 * spacing tokens whose resolved value starts with `4px`.
 *
 * The handler delegates to [TokenAlternativesShower.showForPartialValue] which
 * performs a background lookup and shows the grouped popup.
 */
class ValueCompletionTypedHandler : TypedHandlerDelegate() {

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (!isValueChar(c)) return Result.CONTINUE

        val vf = file.virtualFile ?: return Result.CONTINUE
        val ext = vf.extension?.lowercase() ?: return Result.CONTINUE
        if (ext !in TARGET_EXTS) return Result.CONTINUE

        val settings = TokenSelectorSettings.getInstance(project)
        if (!settings.valueCompletionEnabled) return Result.CONTINUE

        val offset = editor.caretModel.offset
        val document = editor.document
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineText = document.charsSequence.subSequence(lineStart, offset).toString()

        // Bail out if we're inside a var(-- or $ prefix (Mode A territory).
        if (CSS_VAR_PREFIX.find(lineText) != null) return Result.CONTINUE
        if (SCSS_VAR_PREFIX.find(lineText) != null) return Result.CONTINUE

        // Extract property + value from the line.
        val valueMatch = VALUE_CONTEXT.find(lineText) ?: return Result.CONTINUE
        val propertyName = valueMatch.groupValues[1]
        val partialValue = valueMatch.groupValues[2].trim()
        if (partialValue.length < settings.valueCompletionMinChars) return Result.CONTINUE

        // Compute the absolute offsets of the value portion to replace on selection.
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

        return Result.CONTINUE
    }

    private fun isValueChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '#' || c == '.' || c == '-' || c == '%' || c == ':' || c == ' '

    private companion object {
        val TARGET_EXTS = setOf("scss", "sass", "css", "ts", "tsx", "js", "jsx", "mjs", "cjs")
        // Group 1 = property name, Group 2 = partial value (may have leading spaces).
        val VALUE_CONTEXT = Regex("""([a-zA-Z][a-zA-Z-]*)\s*:\s*([^;{}'"`()\n]*)$""")
        val CSS_VAR_PREFIX = Regex("var\\(\\s*--[a-zA-Z0-9_-]*$")
        val SCSS_VAR_PREFIX = Regex("(?:^|[\\s,;:({\\[])\\$[a-zA-Z][a-zA-Z0-9_-]*$")
    }
}
