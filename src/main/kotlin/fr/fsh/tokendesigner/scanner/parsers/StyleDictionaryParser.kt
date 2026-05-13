package fr.fsh.tokendesigner.scanner.parsers

import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.scanner.JsObjectTokenParser

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
        JsObjectTokenParser.parse(text).map { ParsedLeaf(it.path, it.value, it.offset) }
}
