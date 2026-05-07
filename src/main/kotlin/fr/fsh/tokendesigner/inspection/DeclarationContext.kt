package fr.fsh.tokendesigner.inspection

/**
 * Returns a human-readable description of the chain of enclosing blocks for an
 * offset. Used when collecting a token's variants so the user knows under which
 * `@media`/theme/etc. each variant applies.
 *
 * Two block flavours are tracked:
 *  - CSS rule blocks delimited by `{}` (selectors, `@media`, …)
 *  - SCSS map literals delimited by `()` with a `"key":` (or bare `key:`)
 *    label. This is what makes nested theme maps like
 *    ```
 *    $themes: ( "light": ( --color: red ), "dark": ( --color: blue ) );
 *    ```
 *    contribute `light` / `dark` to the chain — without it both variants share
 *    the same `(top level)` context and get rendered as identical columns.
 *
 * Example output (CSS):    `:root @media (min-width: 1024px)`
 * Example output (map):    `light` or `themeSafetyPortal dark`
 */
object DeclarationContext {

    fun describeAt(text: CharSequence, offset: Int): String {
        val chain = mutableListOf<String>()
        var braceDepth = 0
        var parenDepth = 0
        var i = offset - 1
        while (i >= 0) {
            when (val c = text[i]) {
                '}' -> braceDepth++
                ')' -> parenDepth++
                '{' -> {
                    if (braceDepth == 0) {
                        val selector = extractLabelBefore(text, i)
                        if (selector.isNotEmpty()) chain.add(0, selector)
                    } else braceDepth--
                }
                '(' -> {
                    if (parenDepth == 0) {
                        // SCSS map key: only meaningful when the `(` is the value
                        // of a `key:` pair. Plain function calls don't count.
                        val mapKey = extractMapKeyBefore(text, i)
                        if (mapKey != null) chain.add(0, mapKey)
                    } else parenDepth--
                }
                else -> { /* keep walking */ ; @Suppress("UNUSED_EXPRESSION") c }
            }
            i--
        }
        return chain.joinToString(" ")
    }

    /** Selector text immediately preceding a `{`, stopping at the previous statement boundary. */
    private fun extractLabelBefore(text: CharSequence, braceIndex: Int): String {
        var end = braceIndex - 1
        while (end >= 0 && text[end].isWhitespace()) end--
        if (end < 0) return ""
        var start = end
        while (start > 0) {
            val c = text[start - 1]
            if (c == '}' || c == ';' || c == '{') break
            start--
        }
        return text.subSequence(start, end + 1).toString()
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(120)
    }

    /**
     * Returns the bare key of a `"key": (` or `key: (` map entry, or `null`
     * when the `(` is just a function call (`rgb(…)`, `map-get(…)`, …).
     * Quotes are stripped; the result is suitable as a column header.
     */
    private fun extractMapKeyBefore(text: CharSequence, parenIndex: Int): String? {
        var i = parenIndex - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0 || text[i] != ':') return null
        i--
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0) return null
        val end = i
        // Quoted key — accept either `"…"` or `'…'`.
        if (text[end] == '"' || text[end] == '\'') {
            val quote = text[end]
            var start = end - 1
            while (start >= 0 && text[start] != quote) start--
            if (start < 0) return null
            return text.subSequence(start + 1, end).toString()
        }
        // Bare identifier — `themeName: (`.
        if (!isIdentChar(text[end])) return null
        var start = end
        while (start > 0 && isIdentChar(text[start - 1])) start--
        return text.subSequence(start, end + 1).toString()
    }

    private fun isIdentChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-'
}
