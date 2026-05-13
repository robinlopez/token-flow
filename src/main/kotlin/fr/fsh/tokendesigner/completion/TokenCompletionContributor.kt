package fr.fsh.tokendesigner.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import fr.fsh.tokendesigner.inspection.PropertyContext
import fr.fsh.tokendesigner.inspection.TokenValueIndex
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.scanner.TokenNameParser
import fr.fsh.tokendesigner.settings.TokenSelectorSettings
import fr.fsh.tokendesigner.ui.ColorParser
import javax.swing.Icon

/**
 * Code completion for design tokens inside `.scss/.sass/.css/.ts/.tsx/.js/.jsx` files.
 *
 * **Mode A — prefix completion** (existing):
 *  - On `var(--prefix` → suggests CSS custom-property tokens whose name starts with `--prefix`.
 *  - On `$prefix` (in SCSS/Sass files) → suggests SCSS variables.
 *  - Tokens whose category matches the surrounding CSS property are boosted.
 *
 * **Mode B — value completion** (new):
 *  - On `property: <partial-value>` → suggests tokens whose `resolvedValue` starts with the
 *    typed partial value.  This allows `padding: 4` to propose `$spacing-4` or `var(--spacing-4)`.
 *  - Controlled by `valueCompletionEnabled` and `valueCompletionMinChars` in settings.
 *  - Works in CSS/SCSS and JS/TS style files.
 */
class TokenCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val virtualFile = parameters.originalFile.virtualFile ?: return
        val ext = virtualFile.extension?.lowercase() ?: return
        if (ext !in TARGET_EXTS) return

        val project = parameters.position.project
        val settings = TokenSelectorSettings.getInstance(project)

        val document = parameters.editor.document
        val offset = parameters.offset
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineText = document.charsSequence.subSequence(lineStart, offset).toString()

        val isJs = ext in JS_EXTS
        val isScssLike = ext == "scss" || ext == "sass"

        // ── Mode A: prefix-based completion ─────────────────────────────────
        if (settings.autocompleteEnabled) {
            val cssMatch = if (!isJs) CSS_VAR_PREFIX.find(lineText) else null
            val scssMatch = if (isScssLike) SCSS_VAR_PREFIX.find(lineText) else null
            val jsMatch = if (isJs) JS_PATH_PREFIX.find(lineText) else null
            // Runtime / object-access prefix (`colors.`, `theme.spacing.`). Only
            // fires when the typed text already contains a dot — without the
            // dot we'd compete with the IDE's own JS completion for plain
            // identifiers, which would be noisy.
            val jsRuntimeMatch = if (isJs && jsMatch == null) JS_RUNTIME_PREFIX.find(lineText) else null

            val context = when {
                jsMatch != null -> Context(
                    prefix = jsMatch.groupValues[1],
                    kind = TokenKind.JS_OBJECT_PATH,
                )
                jsRuntimeMatch != null -> Context(
                    prefix = jsRuntimeMatch.groupValues[1],
                    kind = TokenKind.JS_RUNTIME_PROPERTY,
                )
                cssMatch != null -> Context(
                    prefix = "--" + cssMatch.groupValues[1],
                    kind = TokenKind.CSS_CUSTOM_PROPERTY,
                )
                scssMatch != null -> Context(
                    prefix = "$" + scssMatch.groupValues[1],
                    kind = TokenKind.SCSS_VARIABLE,
                )
                else -> null
            }

            if (context != null) {
                val canonicalPrefix = if (context.kind == TokenKind.JS_OBJECT_PATH) {
                    TokenNameParser.stripModeSegment(context.prefix) ?: context.prefix
                } else context.prefix
                val rawMode = if (context.kind == TokenKind.JS_OBJECT_PATH)
                    TokenNameParser.rawModeSegmentOf(context.prefix) else null
                val modeIdx = if (rawMode != null) TokenNameParser.modeSegmentIndex(context.prefix) else -1

                val matcher = result.withPrefixMatcher(canonicalPrefix)
                val tokens = TokenIndex.getInstance(project).get(virtualFile)
                if (tokens.isNotEmpty()) {
                    val expectedCategory = inferCategoryFromProperty(lineText)
                    val nearbyFamilies = collectNearbyFamilies(document, offset)
                    for (token in tokens) {
                        if (token.kind != context.kind) continue
                        if (!token.name.startsWith(canonicalPrefix)) continue
                        val insertText = if (rawMode != null && modeIdx >= 0) {
                            TokenNameParser.injectModeSegment(token.name, rawMode, modeIdx)
                        } else token.name
                        matcher.addElement(buildLookup(token, insertText, expectedCategory, nearbyFamilies))
                    }
                }
                // Mode A matched: don't also run Mode B on the same trigger.
                return
            }
        }
    }

    private fun buildLookup(
        token: DesignToken,
        insertText: String,
        expectedCategory: TokenCategory?,
        nearbyFamilies: Set<String>,
    ): com.intellij.codeInsight.lookup.LookupElement {
        var element = LookupElementBuilder.create(insertText)
            .withPresentableText(insertText)
            .withTypeText(token.resolvedValue, true)
            .withCaseSensitivity(false)
        colorIconFor(token)?.let { element = element.withIcon(it) }

        var priority = 0.0
        if (expectedCategory != null && token.category == expectedCategory) priority += 100
        val family = familyOf(token)
        if (family in nearbyFamilies) priority += 50
        return PrioritizedLookupElement.withPriority(element, priority)
    }

    private fun colorIconFor(token: DesignToken): Icon? {
        if (token.category != TokenCategory.COLOR) return null
        val color = ColorParser.parse(token.resolvedValue) ?: return null
        return com.intellij.util.ui.ColorIcon(12, color)
    }

    private fun familyOf(token: DesignToken): String {
        val stripped = token.name.removePrefix("--").removePrefix("$")
        val segs = stripped.split('-')
        if (segs.isEmpty()) return ""
        val head = segs[0].lowercase()
        return if (head == "token" && segs.size >= 2) segs[1].lowercase() else head
    }

    /** Returns the category implied by the CSS property being assigned, if any. */
    private fun inferCategoryFromProperty(lineText: String): TokenCategory? {
        val match = PROPERTY_PATTERN.findAll(lineText).lastOrNull() ?: return null
        return PropertyContext.categoryFor(match.groupValues[1])
    }

    /**
     * Walks back from [offset] to the nearest `{` to scope the lookup to the
     * current selector block, then collects every `var(--…)` and `$…` family
     * found inside it.
     */
    private fun collectNearbyFamilies(document: com.intellij.openapi.editor.Document, offset: Int): Set<String> {
        val text = document.charsSequence
        var depth = 0
        var blockStart = 0
        var i = offset - 1
        while (i >= 0) {
            when (text[i]) {
                '}' -> depth++
                '{' -> {
                    if (depth == 0) {
                        blockStart = i + 1
                        break
                    }
                    depth--
                }
            }
            i--
        }
        val block = text.subSequence(blockStart, offset).toString()
        val families = mutableSetOf<String>()
        for (m in NEARBY_VAR.findAll(block)) families += families(m.groupValues[1])
        for (m in NEARBY_SCSS.findAll(block)) families += families(m.groupValues[1])
        return families
    }

    private fun families(name: String): String {
        val segs = name.split('-')
        if (segs.isEmpty()) return ""
        val head = segs[0].lowercase()
        return if (head == "token" && segs.size >= 2) segs[1].lowercase() else head
    }

    private data class Context(val prefix: String, val kind: TokenKind)

    private companion object {
        val TARGET_EXTS = setOf("scss", "sass", "css", "ts", "tsx", "js", "jsx", "mjs", "cjs")
        val JS_EXTS = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")
        val CSS_VAR_PREFIX = Regex("var\\(\\s*--([a-zA-Z0-9_-]*)$")
        val SCSS_VAR_PREFIX = Regex("(?:^|[\\s,;:({\\[])\\$([a-zA-Z][a-zA-Z0-9_-]*)$")
        // Triggers in TS/JS:
        //  - `'{path.in.token...`     (Style-Dictionary alias literal)
        //  - `dt('path.in.token...`   (PrimeUIX preset helper)
        // Group 1 = the path typed so far.
        val JS_PATH_PREFIX = Regex(
            "(?:[\"'`]\\{|dt\\(\\s*[\"'`])([a-zA-Z][a-zA-Z0-9_.-]*)$"
        )
        // Bare property-access prefix in JS/TS code: `colors.`, `theme.radius.`,
        // `nomTheme.colors.PRIMARY_`. Requires at least one `.` so we don't
        // shadow regular JS identifier completion. Anchored on a non-identifier
        // boundary so we don't trigger inside the middle of an unrelated
        // expression (e.g. `foo.barcolors.x` would not).
        val JS_RUNTIME_PREFIX = Regex(
            "(?:^|[^A-Za-z0-9_$.])([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\.[\\w$]*)$"
        )
        val PROPERTY_PATTERN = Regex("([a-zA-Z][a-zA-Z-]*)\\s*:")
        val NEARBY_VAR = Regex("var\\(\\s*--([a-zA-Z][a-zA-Z0-9_-]*)\\)")
        val NEARBY_SCSS = Regex("\\$([a-zA-Z][a-zA-Z0-9_-]*)")
    }
}
