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

    enum class Mode { STYLE_DICTIONARY, RUNTIME, NONE }

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
     * Patterns that indicate a file is definitively NOT a design-token file.
     * Checked first so that Storybook stories and JSON-Schema definitions are
     * never indexed, even if they happen to contain strings that would otherwise
     * match [STYLE_DICT_ALIAS] or [RUNTIME_HINTS].
     *
     *  - `from '@storybook/…'` — component story files
     *  - `$schema:` / `$defs:` / `$ref:` object keys — JSON Schema vocabulary,
     *    quoted *or* unquoted (`{ $schema: '…' }`, `{ "$ref": '…' }`). These
     *    dollar-prefixed keys are characteristic of the spec and never appear
     *    in design-token presets. A leading boundary keeps it from matching a
     *    `obj.$ref` member access.
     */
    private val SKIP_HINTS = Regex(
        "from\\s+[\"'`]@storybook/" +
            "|[\\s{,(\\[][\"'`]?\\$(?:schema|defs|ref)[\"'`]?\\s*:"
    )

    /**
     * Picks a mode for [text]. Order matters:
     *  1. Known non-token signals ([SKIP_HINTS]) win unconditionally so that
     *     Storybook stories and JSON-Schema definitions are never indexed.
     *  2. Presence of `{path.like.this}` alias literals is a near-certain
     *     marker of a Style-Dictionary preset.
     *  3. Otherwise, runtime hints (RN import, typed export, StyleSheet) win.
     *  4. Fallback to Style-Dictionary to preserve historic behaviour for
     *     files that match neither (e.g. plain primitive presets).
     */
    fun detectMode(text: CharSequence): Mode = when {
        SKIP_HINTS.containsMatchIn(text) -> Mode.NONE
        STYLE_DICT_ALIAS.containsMatchIn(text) -> Mode.STYLE_DICTIONARY
        RUNTIME_HINTS.containsMatchIn(text) -> Mode.RUNTIME
        else -> Mode.STYLE_DICTIONARY
    }

    /** Returns null for [Mode.NONE] — callers must guard before invoking `.kind` or `.parse`. */
    fun parserFor(mode: Mode): JsTokenFileParser? = when (mode) {
        Mode.STYLE_DICTIONARY -> StyleDictionaryParser
        Mode.RUNTIME -> RuntimeObjectParser
        Mode.NONE -> null
    }

    /** Convenience: detect + parse in one call. Returns empty list for [Mode.NONE]. */
    fun parse(text: CharSequence): Pair<Mode, List<ParsedLeaf>> {
        val mode = detectMode(text)
        val leaves = parserFor(mode)?.parse(text) ?: emptyList()
        return mode to leaves
    }

    /**
     * Detect + parse, *and* extract function helpers when the file is in
     * runtime mode. Style-Dictionary presets don't expose callable helpers
     * so we skip the helper pass entirely there to keep parsing fast.
     * Returns an empty [FileTokens] immediately for [Mode.NONE].
     */
    fun parseFull(text: CharSequence): FileTokens {
        val mode = detectMode(text)
        if (mode == Mode.NONE) return FileTokens(mode, emptyList(), emptyList())
        val leaves = parserFor(mode)!!.parse(text)
        val helpers = if (mode == Mode.RUNTIME) RuntimeFunctionParser.parse(text) else emptyList()
        return FileTokens(mode, leaves, helpers)
    }

    data class FileTokens(
        val mode: Mode,
        val leaves: List<ParsedLeaf>,
        val helpers: List<RuntimeFunctionParser.ParsedHelper>,
    )
}
