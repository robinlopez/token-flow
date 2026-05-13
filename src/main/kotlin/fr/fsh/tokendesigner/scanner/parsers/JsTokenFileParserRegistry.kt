package fr.fsh.tokendesigner.scanner.parsers

/**
 * Dispatches a TS/JS file to the right [JsTokenFileParser].
 *
 * Per-file detection — instead of running every parser unconditionally — keeps
 * the index clean (no duplicated leaves under different [kind]s for the same
 * physical declaration) and produces suggestions whose replacement syntax is
 * correct for the surrounding source style.
 *
 * Adding a new stack:
 *   1. Implement [JsTokenFileParser].
 *   2. Extend [Mode] and the heuristics in [detectMode] / [parserFor].
 */
object JsTokenFileParserRegistry {

    enum class Mode { STYLE_DICTIONARY, RUNTIME }

    /** Alias literal as it appears in Style-Dictionary presets: `'{a.b.c}'`. */
    private val STYLE_DICT_ALIAS = Regex("[\"'`]\\{[A-Za-z][\\w.\\-]*\\}[\"'`]")

    /** Hints that strongly imply a runtime / object-access theme file. */
    private val RUNTIME_HINTS = Regex(
        // `from 'react-native'` / `from "react-native/…"`
        "from\\s+[\"'`]react-native[\"'`/]" +
            "|StyleSheet\\.create\\s*\\(" +
            "|(?m)^\\s*export\\s+const\\s+\\w+\\s*:\\s*\\w+(?:<[^>]*>)?\\s*="
    )

    /**
     * Picks a mode for [text]. Order matters:
     *  1. Presence of `{path.like.this}` alias literals is a near-certain
     *     marker of a Style-Dictionary preset.
     *  2. Otherwise, runtime hints (RN import, typed export, StyleSheet) win.
     *  3. Fallback to Style-Dictionary to preserve historic behaviour for
     *     files that match neither (e.g. plain primitive presets).
     */
    fun detectMode(text: CharSequence): Mode = when {
        STYLE_DICT_ALIAS.containsMatchIn(text) -> Mode.STYLE_DICTIONARY
        RUNTIME_HINTS.containsMatchIn(text) -> Mode.RUNTIME
        else -> Mode.STYLE_DICTIONARY
    }

    fun parserFor(mode: Mode): JsTokenFileParser = when (mode) {
        Mode.STYLE_DICTIONARY -> StyleDictionaryParser
        Mode.RUNTIME -> RuntimeObjectParser
    }

    /** Convenience: detect + parse in one call. */
    fun parse(text: CharSequence): Pair<Mode, List<ParsedLeaf>> {
        val mode = detectMode(text)
        return mode to parserFor(mode).parse(text)
    }

    /**
     * Detect + parse, *and* extract function helpers when the file is in
     * runtime mode. Style-Dictionary presets don't expose callable helpers
     * so we skip the helper pass entirely there to keep parsing fast.
     */
    fun parseFull(text: CharSequence): FileTokens {
        val mode = detectMode(text)
        val leaves = parserFor(mode).parse(text)
        val helpers = if (mode == Mode.RUNTIME) RuntimeFunctionParser.parse(text) else emptyList()
        return FileTokens(mode, leaves, helpers)
    }

    data class FileTokens(
        val mode: Mode,
        val leaves: List<ParsedLeaf>,
        val helpers: List<RuntimeFunctionParser.ParsedHelper>,
    )
}
