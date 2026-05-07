package fr.fsh.tokendesigner.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.settings.ScopeResolver
import fr.fsh.tokendesigner.settings.TokenSelectorSettings

/**
 * Flags hardcoded values (hex colors, `rgb()`, lengths, durations) that match
 * an existing design token, and offers a quick fix to replace the literal with
 * `var(--name)` or `$name`.
 *
 * Suggestion ordering:
 *  1. Tokens whose category matches the current CSS property (e.g. `font-size:
 *     12px` → typography token first, even when a spacing token has the same
 *     value).
 *  2. For colors with no exact match: closest token in RGB space within a
 *     threshold (the suggestion text says "closest token").
 *  3. Token name length (shorter, more semantic names first).
 *  4. Alphabetical.
 *
 * Files inside any configured scope's `sourcePaths` are skipped — those are
 * the tokens' own source of truth and would otherwise self-flag.
 */
class HardcodedValueInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Hardcoded value matches a design token"
    override fun getGroupDisplayName(): String = "Token Flow"
    override fun getShortName(): String = "DesignTokenHardcodedValue"

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val vf = file.virtualFile ?: return null
        val ext = vf.extension?.lowercase() ?: return null
        if (ext !in TARGET_EXTS) return null

        val project = file.project
        if (isTokenSourceFile(project, vf)) return null

        // Restrict candidates to the kind that makes syntactic sense in this
        // file. A `'12px'` literal in a `.ts` file should yield a JS object
        // path, not a `var(--name)` that would break the source.
        val allTokens = TokenIndex.getInstance(project).get(vf)
        if (allTokens.isEmpty()) return null
        val tokens = allTokens.filter { it.kind in compatibleKinds(ext) }
        if (tokens.isEmpty()) return null
        val valueIndex = TokenValueIndex(tokens)
        val text = file.text

        val problems = mutableListOf<ProblemDescriptor>()

        val isJs = ext in JS_EXTS
        for (hit in LiteralFinder.findIn(text)) {
            // In JS/TS files, a `'{token}'` reference can't be embedded inside
            // a multi-value string (e.g. a CSS `box-shadow` literal): inserting
            // the inner quotes would corrupt the surrounding string. Skip
            // those hits — the user can still tokenise the whole expression by
            // hand if they want to.
            if (isJs && hit.insidePartialString) continue
            val expected = PropertyContext.detectAt(text, hit.startOffset)
            val suggestions = SuggestionEngine.findSuggestions(hit, valueIndex, tokens, expected)
            if (suggestions.isEmpty()) continue

            val first = suggestions.first()
            val description = describe(hit, first, expected)
            val fixes: Array<LocalQuickFix> = suggestions
                .map { ReplaceWithTokenFix(it.token.name, it.token.kind) }
                .toTypedArray()

            problems += manager.createProblemDescriptor(
                file,
                TextRange(hit.replaceStart, hit.replaceEndExclusive),
                description,
                ProblemHighlightType.WEAK_WARNING,
                isOnTheFly,
                *fixes,
            )
        }
        return if (problems.isEmpty()) null else problems.toTypedArray()
    }

    private fun describe(hit: LiteralFinder.Hit, first: TokenSuggestion, expected: TokenCategory?): String {
        val prefixedName = first.token.name
        val context = expected?.let { " (${it.name.lowercase()} context)" }.orEmpty()
        return if (first.exact) {
            "Replace hardcoded ${hit.replaceText} with design token $prefixedName$context"
        } else {
            val deltaPct = (first.delta * 100).toInt()
            "Closest design token to ${hit.replaceText}: $prefixedName (≈${deltaPct}% off)$context"
        }
    }

    // ─── Token source detection ──────────────────────────────────────────

    private fun isTokenSourceFile(project: Project, file: VirtualFile): Boolean {
        val settings = TokenSelectorSettings.getInstance(project)
        val targetPath = file.path
        for (scope in settings.scopes) {
            for (src in scope.sourcePaths) {
                val abs = ScopeResolver.absolutize(project, src) ?: continue
                if (targetPath == abs || targetPath.startsWith("$abs/")) return true
            }
        }
        return false
    }

    private fun compatibleKinds(ext: String): Set<TokenKind> = when (ext) {
        "ts", "tsx", "js", "jsx", "mjs", "cjs" -> setOf(TokenKind.JS_OBJECT_PATH)
        else -> setOf(TokenKind.SCSS_VARIABLE, TokenKind.CSS_CUSTOM_PROPERTY)
    }

    companion object {
        private val TARGET_EXTS = setOf(
            "scss", "sass", "css",
            "ts", "tsx", "js", "jsx", "mjs", "cjs",
        )
        private val JS_EXTS = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")
    }
}
