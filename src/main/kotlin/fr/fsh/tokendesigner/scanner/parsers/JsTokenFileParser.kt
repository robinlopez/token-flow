package fr.fsh.tokendesigner.scanner.parsers

import fr.fsh.tokendesigner.model.TokenKind

/**
 * Strategy for extracting design tokens out of a single TS/JS file.
 *
 * Each parser targets one *flavour* of token authorship — Style-Dictionary
 * presets, React-Native-style runtime themes, CSS-in-JS objects, … — and is
 * picked per file by [JsTokenFileParserRegistry] based on a file-level mode
 * detector. New stacks plug in by implementing this interface and registering
 * an entry in the registry.
 */
interface JsTokenFileParser {

    /** The [TokenKind] every leaf returned by [parse] will carry. */
    val kind: TokenKind

    /** Extracts the leaves declared in [text]. Offsets refer to [text] verbatim. */
    fun parse(text: CharSequence): List<ParsedLeaf>
}

/** A `path → value` leaf extracted from a JS/TS source. */
data class ParsedLeaf(
    /** Dot-joined sequence of object keys. Includes the binding name for
     *  runtime parsers, excludes it for Style-Dictionary parsers. */
    val path: String,
    /** Verbatim leaf value, quotes stripped. */
    val value: String,
    /** Absolute offset of the value (or its opening quote) inside the source. */
    val offset: Int,
)
