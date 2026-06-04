package fr.fsh.tokendesigner.scanner.parsers

import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.scanner.JsObjectTokenParser
import fr.fsh.tokendesigner.scanner.StyleValueHeuristics

/**
 * Parses TS/JS files written in the Style-Dictionary / PrimeUIX preset style:
 *   `export const tokens = { … }`  or  `export default { … }`
 *
 * Leaves are emitted *without* the exported binding name as a prefix because
 * references in code use only the inner path: `'{primitive.neutral.500}'` or
 * `dt('primitive.neutral.500')`.
 *
 * Type-annotated exports (`export const x: SomeType = { … }`) and bare
 * `const x = { … }` declarations are intentionally NOT handled here — those
 * shapes belong to [RuntimeObjectParser].
 */
object StyleDictionaryParser : JsTokenFileParser {

    override val kind: TokenKind = TokenKind.JS_OBJECT_PATH

    override fun parse(text: CharSequence): List<ParsedLeaf> =
        // Classify each exported object independently: a file may hold a real
        // token preset next to a JSON-Schema body or an enum map. Objects whose
        // values aren't style primitives are dropped wholesale (see issue #24).
        JsObjectTokenParser.parseGroups(text)
            .filter { group -> StyleValueHeuristics.looksLikeTokenObject(group.map { it.value }) }
            .flatten()
            .map { ParsedLeaf(it.path, it.value, it.offset) }
}
