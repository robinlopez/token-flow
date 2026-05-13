package fr.fsh.tokendesigner.scanner.parsers

import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.scanner.JsObjectTokenParser

/**
 * Parses TS/JS files where design tokens are exposed as a typed runtime
 * object — the convention used by most React-Native themes and CSS-in-JS
 * setups:
 *
 * ```ts
 * const colors = { PRIMARY_500: '#FE5716', … };
 * export const nomTheme: Theme = { colors: { … }, radius: { sm: 8 }, … };
 * ```
 *
 * Two declaration shapes are distinguished, because they translate to
 * **different call-site expressions** in practice:
 *
 *  1. **Typed theme aggregator** — `export const X: SomeType = { … }`.
 *     The binding is the umbrella theme object; barrel files almost always
 *     re-export its *children* (`colors`, `radius`, `fontPresets`) as named
 *     exports rather than the whole `X`. Emitted token names therefore
 *     **drop** the binding prefix:
 *
 *     ```
 *     export const nomTheme: Theme = { fontPresets: { h1: { fontSize: 34 } } }
 *     // → token name = `fontPresets.h1.fontSize`  (not `nomTheme.fontPresets.…`)
 *     ```
 *
 *  2. **Token bag** — `const X = { … }` (with or without `export`, never
 *     typed). The binding *is* the import name at the call site, so the
 *     token name **keeps** the prefix:
 *
 *     ```
 *     const colors = { PRIMARY_500: '#FE5716' }
 *     // → token name = `colors.PRIMARY_500`
 *     ```
 *
 * When the same logical value appears under both shapes (e.g. `nomTheme`
 * aliases the local `colors` bag), the names collide deliberately and
 * `TokenScanner.resolve` collapses them — the user sees a single
 * `colors.PRIMARY_500` entry regardless of which file authored the leaf.
 *
 * `export default { … }` is intentionally skipped — it has no binding name
 * to inspect and is the canonical entry point of Style-Dictionary presets.
 */
object RuntimeObjectParser : JsTokenFileParser {

    override val kind: TokenKind = TokenKind.JS_RUNTIME_PROPERTY

    // Group 1 = binding identifier. Group 2 (when present) captures the
    // type-annotation portion `: SomeType` — used as the discriminator
    // between "typed theme aggregator" (strip prefix) and "token bag" (keep
    // prefix). Multiline so `^` matches line starts; we only pick up
    // top-level declarations.
    private val DECL_REGEX = Regex(
        "(?m)^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*(:\\s*[^=\\n]+?)?\\s*=\\s*\\{"
    )

    override fun parse(text: CharSequence): List<ParsedLeaf> {
        val out = mutableListOf<ParsedLeaf>()
        for (match in DECL_REGEX.findAll(text)) {
            val name = match.groupValues[1]
            val hasTypeAnnotation = match.groupValues[2].isNotEmpty()
            // Typed aggregators drop the binding; bags keep it. See KDoc above.
            val initialPath = if (hasTypeAnnotation) emptyList() else listOf(name)
            // The trailing `{` is the last char of the match → its index is `match.range.last`.
            val openBrace = match.range.last
            JsObjectTokenParser.parseAt(text, openBrace, initialPath).forEach {
                out += ParsedLeaf(it.path, it.value, it.offset)
            }
        }
        return out
    }
}
