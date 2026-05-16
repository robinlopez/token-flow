package fr.fsh.tokendesigner.inspection

/**
 * Locates "tokenizable" literal values in a `.scss/.sass/.css` file:
 * hex colors, functional colors (`rgb()`/`hsl()`), and lengths/durations
 * with units (px, rem, em, %, ms, s).
 *
 * Each [Hit] carries the matched text, its absolute offsets in the file
 * and a coarse [Kind] so the inspection can decide how to look the value up.
 */
object LiteralFinder {

    enum class Kind {
        COLOR,
        LENGTH,
        DURATION,
        /**
         * Plain numeric literal in property-value position (`fontSize: 34`,
         * `radius: 8`, `opacity: 0.5`). Common in React-Native / CSS-in-JS
         * stylesheets where sizes are unitless. NOT emitted for numbers that
         * sit inside a function-call's argument list — those are intentional
         * scale calls (`spacing(0.5)`) and would be misleading to flag.
         *
         * Callers in non-JS files should filter these out: CSS shorthand like
         * `border: 1 solid red` would produce a spurious hit otherwise.
         */
        NUMBER,
    }

    /**
     * @property text          Inner literal value, used for token-value lookup
     *                         (e.g. `14px`).
     * @property startOffset   Inner literal start offset — used by
     *                         [PropertyContext] to detect the surrounding CSS
     *                         property context.
     * @property replaceStart  Range to highlight & replace. Equals [startOffset]
     *                         unless the literal sits as the sole argument of a
     *                         transparent wrapper like `utils.rem-calc(14px)`,
     *                         in which case the wrapper call is included so the
     *                         replacement covers the entire expression.
     * @property replaceText   Display text matching the replace range.
     */
    data class Hit(
        val text: String,
        val startOffset: Int,
        val endOffsetExclusive: Int,
        val kind: Kind,
        val replaceStart: Int = startOffset,
        val replaceEndExclusive: Int = endOffsetExclusive,
        val replaceText: String = text,
        /**
         * True when the literal sits inside a string literal alongside other
         * non-whitespace content (e.g. `'0 0.5px 0 0 rgba(0, 0, 0, 0.06)'`).
         * In that case, swapping the literal for a `'{token}'` reference would
         * break the surrounding string — JS callers must skip these hits.
         */
        val insidePartialString: Boolean = false,
        /**
         * True when the literal is assigned to a variable (e.g. `$color: #fff`
         * or `--color: #fff`). Useful to filter out definitions from the
         * "hardcoded values" scan.
         */
        val isDeclaration: Boolean = false,
    )

    fun findIn(text: CharSequence): List<Hit> {
        val out = mutableListOf<Hit>()
        // Compute fallback and comment ranges first; any literal inside them must NOT be flagged.
        val fallbackRanges = computeFallbackRanges(text)
        val commentRanges = computeCommentRanges(text)

        fun isIgnored(offset: Int): Boolean =
            isInsideFallback(offset, fallbackRanges) || commentRanges.any { offset in it }

        for (m in HEX_REGEX.findAll(text)) {
            if (isInsideTokenName(text, m.range.first)) continue
            if (isIgnored(m.range.first)) continue
            val hit = expandWrapper(text, m.value, m.range.first, m.range.last + 1, Kind.COLOR)
            out += hit.copy(isDeclaration = isVariableDeclaration(text, m.range.first))
        }
        for (m in FN_COLOR_REGEX.findAll(text)) {
            if (isIgnored(m.range.first)) continue
            val hit = expandWrapper(text, m.value, m.range.first, m.range.last + 1, Kind.COLOR)
            out += hit.copy(isDeclaration = isVariableDeclaration(text, m.range.first))
        }
        for (m in NAMED_COLOR_REGEX.findAll(text)) {
            if (isIgnored(m.range.first)) continue
            val hit = expandWrapper(text, m.value, m.range.first, m.range.last + 1, Kind.COLOR)
            out += hit.copy(isDeclaration = isVariableDeclaration(text, m.range.first))
        }
        for (m in DURATION_REGEX.findAll(text)) {
            if (isWhitelisted(m.value)) continue
            if (isIgnored(m.range.first)) continue
            val hit = expandWrapper(text, m.value, m.range.first, m.range.last + 1, Kind.DURATION)
            out += hit.copy(isDeclaration = isVariableDeclaration(text, m.range.first))
        }
        for (m in LENGTH_REGEX.findAll(text)) {
            if (isWhitelisted(m.value)) continue
            if (isIgnored(m.range.first)) continue
            // Avoid double-matching the duration `12s` as length when both regex agree.
            if (out.any { it.startOffset == m.range.first }) continue
            val hit = expandWrapper(text, m.value, m.range.first, m.range.last + 1, Kind.LENGTH)
            out += hit.copy(isDeclaration = isVariableDeclaration(text, m.range.first))
        }
        // Plain numbers in property-value position. The regex captures the
        // surrounding `IDENT:` for anchoring; group 1 is the number itself.
        // We deliberately match here, after LENGTH, so any value already
        // tagged at the same offset (e.g. `12px`) wins.
        for (m in NUMBER_PROP_REGEX.findAll(text)) {
            val numberGroup = m.groups[1] ?: continue
            val start = numberGroup.range.first
            val end = numberGroup.range.last + 1
            val value = numberGroup.value
            if (isWhitelisted(value)) continue
            if (isIgnored(start)) continue
            if (out.any { it.startOffset == start }) continue
            // If the key starts with $ or is --, it's a variable declaration.
            val key = m.value.substringBefore(':').trim()
            val isDecl = key.startsWith("$") || key.startsWith("--")
            out += Hit(value, start, end, Kind.NUMBER, isDeclaration = isDecl)
        }
        return out
    }

    /**
     * If the literal at [start, end) is the sole argument of a transparent
     * wrapper call (e.g. `utils.rem-calc(14px)`, `rem-calc(14px)`), expand the
     * replace range to cover the whole call so a quick-fix can swap the entire
     * expression for `var(--token)` rather than leaving a redundant
     * `utils.rem-calc(var(--token))`.
     *
     * Inner [text]/[startOffset] keep pointing at the literal, so token-value
     * lookups and CSS-property-context detection still work as before.
     */
    private fun expandWrapper(
        source: CharSequence,
        value: String,
        start: Int,
        end: Int,
        kind: Kind,
    ): Hit {
        val baseHit = Hit(value, start, end, kind)
            .copy(insidePartialString = isInsidePartialString(source, start, end))
        // First check: literal sits inside a JS/TS string literal (`'12px'`)
        // as the *sole* content. Expanding to the full quoted span lets a
        // JS_OBJECT_PATH replacement produce a clean `'{path}'` instead of
        // nesting quotes.
        expandStringLiteral(source, value, start, end, kind)?.let { return it }
        // Walk back from the literal: only whitespace, then `(`, then a
        // recognised wrapper name (optionally namespaced with `module.`).
        var i = start - 1
        while (i >= 0 && source[i].isWhitespace()) i--
        if (i < 0 || source[i] != '(') return baseHit
        val parenOpen = i
        // Capture the function-call name immediately before `(`.
        var nameEnd = parenOpen
        var nameStart = nameEnd
        while (nameStart > 0 && isWrapperNameChar(source[nameStart - 1])) nameStart--
        if (nameStart == nameEnd) return baseHit
        val wrapperName = source.subSequence(nameStart, nameEnd).toString()
        if (!isTransparentWrapper(wrapperName)) return baseHit
        // Walk forward from the literal end: only whitespace, then `)`.
        var j = end
        while (j < source.length && source[j].isWhitespace()) j++
        if (j >= source.length || source[j] != ')') return baseHit
        val parenClose = j + 1

        return baseHit.copy(
            replaceStart = nameStart,
            replaceEndExclusive = parenClose,
            replaceText = source.subSequence(nameStart, parenClose).toString(),
        )
    }

    /**
     * If the literal sits as the *exclusive* content of a `'…'`/`"…"`/`` `…` ``
     * string (i.e. only whitespace between the quotes and the value), return a
     * Hit whose replace range covers the whole quoted span. Used in TS/JS so
     * `gap: '0.2rem'` can be replaced wholesale with `'{token}'`.
     */
    private fun expandStringLiteral(
        source: CharSequence,
        value: String,
        start: Int,
        end: Int,
        kind: Kind,
    ): Hit? {
        var i = start - 1
        while (i >= 0 && source[i].isWhitespace() && source[i] != '\n') i--
        if (i < 0) return null
        val quote = source[i]
        if (quote != '\'' && quote != '"' && quote != '`') return null
        var j = end
        while (j < source.length && source[j].isWhitespace() && source[j] != '\n') j++
        if (j >= source.length || source[j] != quote) return null
        return Hit(
            text = value,
            startOffset = start,
            endOffsetExclusive = end,
            kind = kind,
            replaceStart = i,
            replaceEndExclusive = j + 1,
            replaceText = source.subSequence(i, j + 1).toString(),
        )
    }

    /**
     * Heuristic: returns true when the literal at `[start, end)` sits inside
     * a `'…'`/`"…"`/`` `…` `` string AND that string contains other
     * non-whitespace content. We scan outward from the literal to the nearest
     * line-bounded matching quotes; if anything other than whitespace lives
     * between those quotes (besides the literal itself), it's a partial
     * string — the literal can't be swapped for a `'{token}'` reference
     * without corrupting the surrounding text.
     */
    private fun isInsidePartialString(source: CharSequence, start: Int, end: Int): Boolean {
        // Walk left within the same line until we find an opening quote.
        var i = start - 1
        while (i >= 0 && source[i] != '\n') {
            val c = source[i]
            if (c == '\'' || c == '"' || c == '`') {
                val openQuote = c
                val openPos = i
                // Walk right within the same line for the matching close quote.
                var j = end
                while (j < source.length && source[j] != '\n') {
                    if (source[j] == openQuote) {
                        // Check whether anything else than whitespace exists
                        // between the open quote and the literal, OR between
                        // the literal end and the close quote.
                        val before = source.subSequence(openPos + 1, start)
                        val after = source.subSequence(end, j)
                        val hasOtherContent = before.any { !it.isWhitespace() } ||
                            after.any { !it.isWhitespace() }
                        return hasOtherContent
                    }
                    j++
                }
                // No matching closing quote on this line — treat as not-inside.
                return false
            }
            i--
        }
        return false
    }

    private fun isWrapperNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'

    /**
     * Wrappers whose arguments map 1:1 to the token's resolved value. `rem-calc`
     * (Foundation / utils helper) converts px to rem; the same token is
     * available directly as `var(--token)`/`$token` in rem, so the call can be
     * replaced wholesale rather than nested.
     *
     * Matched against the trailing identifier (after any `module.` prefix), so
     * both `rem-calc(14px)` and `utils.rem-calc(14px)` are covered.
     */
    private fun isTransparentWrapper(name: String): Boolean {
        val trailing = name.substringAfterLast('.')
        return trailing.equals("rem-calc", ignoreCase = true) ||
            trailing.equals("rem", ignoreCase = true)
    }

    /**
     * Returns ranges (within the file text) that fall *after the comma* in a
     * `var(--name, fallback)` call. Anything inside such a range is a deliberate
     * fallback and should not be considered hardcoded.
     */
    private fun computeFallbackRanges(text: CharSequence): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (m in VAR_WITH_FALLBACK.findAll(text)) {
            val fallbackGroup = m.groups[1] ?: continue
            ranges += fallbackGroup.range
        }
        return ranges
    }

    private fun isInsideFallback(offset: Int, ranges: List<IntRange>): Boolean =
        ranges.any { offset in it }

    /**
     * Returns ranges of both block (`/* ... */`) and line (`// ...`) comments.
     */
    private fun computeCommentRanges(text: CharSequence): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        val blockRegex = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        for (m in blockRegex.findAll(text)) ranges += m.range
        val lineRegex = Regex("//.*")
        for (m in lineRegex.findAll(text)) ranges += m.range
        return ranges
    }

    /**
     * Hex colors should not be inspected when they appear inside a token name
     * (e.g. `--color-#fff` would be unusual but defensive). For now the regex
     * already requires word-boundary; this is here as a safety net.
     */
    private fun isInsideTokenName(text: CharSequence, offset: Int): Boolean {
        if (offset == 0) return false
        val prev = text[offset - 1]
        return prev.isLetterOrDigit() || prev == '_' || prev == '-'
    }

    /**
     * Checks if the literal at [offset] is part of a variable declaration
     * like `$name: ...` or `--name: ...`.
     */
    private fun isVariableDeclaration(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0 || text[i] != ':') return false

        i--
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0) return false

        // Match variable name backwards
        var j = i
        while (j >= 0 && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == '_')) j--

        if (j < 0) {
            // Check if we hit the start of the file exactly at the name start
            return false
        }

        // SCSS: $name
        if (text[j] == '$') return true

        // CSS: --name
        if (text[j] == '-' && j > 0 && text[j - 1] == '-') return true

        return false
    }

    private fun isWhitelisted(value: String): Boolean {
        val v = value.trim().lowercase()
        return v in WHITELIST
    }

    // Matches: `#abc`, `#abcd`, `#aabbcc`, `#aabbccdd`, NOT inside ident chars.
    private val HEX_REGEX = Regex(
        "(?<![a-zA-Z0-9_-])#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})\\b"
    )
    // Functional color forms; we keep the parentheses inside the match for replacement.
    private val FN_COLOR_REGEX = Regex(
        "(?<![a-zA-Z0-9_-])(?:rgb|rgba|hsl|hsla|hwb)\\(\\s*[^)]*\\)",
        RegexOption.IGNORE_CASE,
    )
    // Named colors
    private val NAMED_COLOR_REGEX = Regex(
        "(?<![a-zA-Z0-9_-])(?:transparent|black|white|red|green|blue|yellow|orange|purple|gray|grey|pink|brown)\\b(?!-)",
        RegexOption.IGNORE_CASE,
    )
    private val DURATION_REGEX = Regex("(?<![a-zA-Z0-9_-])-?\\d*\\.?\\d+(?:ms|s)\\b")
    private val LENGTH_REGEX = Regex(
        "(?<![a-zA-Z0-9_-])-?\\d*\\.?\\d+(?:px|rem|em|vh|vw|vmin|vmax|ch|ex|%)\\b"
    )
    // Plain numeric in `property: NUMBER[,;)}\]\n]` position. Group 1 = the
    // number range we'll report. The trailing lookahead requires the number
    // to be the **sole content** of its property-value slot — followed only
    // by whitespace and then a terminator (`,;)}\]\n`) or end-of-input. That
    // way CSS shorthand like `border: 1 solid red` (where `1` is followed by
    // ` solid`) doesn't generate a spurious NUMBER hit, while real RN-style
    // single-value assignments (`fontSize: 34,`, `radius: 8}`, `opacity: 0.5`
    // at end of line) match.
    private val NUMBER_PROP_REGEX = Regex(
        "[A-Za-z_\$][\\w\$]*\\s*:\\s*(-?\\d+(?:\\.\\d+)?)(?![\\w%.\\-])(?=\\s*[,;)}\\]\\n]|\\s*$)"
    )
    /**
     * `var(--token-name, FALLBACK)` — group 1 is the fallback expression (after
     * the comma, before the closing `)`). We skip any nested `var(...)`
     * intentionally; the regex stops at the first unmatched `)`.
     */
    private val VAR_WITH_FALLBACK = Regex(
        "var\\(\\s*--[A-Za-z_][A-Za-z0-9_-]*\\s*,([^)]*)\\)"
    )

    private val WHITELIST = setOf(
        "0", "0px", "0rem", "0em", "0%", "100%", "0s", "0ms",
    )
}
