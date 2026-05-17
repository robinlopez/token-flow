package fr.fsh.tokendesigner.scanner

import com.intellij.openapi.editor.Document

/**
 * Finds the design token name at a given offset in a document text.
 *
 * Recognises:
 *  - SCSS variables: `$name` (with arbitrary `-` and `_` inside)
 *  - CSS custom properties: `--name` either bare (`--foo: red;`) or inside
 *    a `var(--foo)` call. Caret can sit anywhere within the identifier,
 *    on the `--` prefix, or on the `$` prefix.
 */
object TokenLocator {

    data class Hit(
        val name: String,        // includes leading `$` or `--`
        val startOffset: Int,    // start of the token in the document, prefix included
        val endOffset: Int,      // exclusive
    )

    fun find(document: Document, offset: Int, fileExtension: String? = null): Hit? {
        val text = document.charsSequence
        if (offset < 0 || offset > text.length) return null

        // JS/TS-only detectors. Skipped in CSS / SCSS / SASS files because
        // their syntax overlaps with legitimate CSS constructs:
        //  - `lighten($color, 10%)` looks like a helper call and would hijack
        //    the `$color` variable detection (caret sits inside the parens).
        //  - `.foo.bar` (selector chain) or `utils.rem-calc(…)` (Sass namespace
        //    call) look like a runtime property chain.
        // Keeping these gated by extension preserves the historic CSS/SCSS
        // behaviour while still firing in .ts/.tsx/.js/.jsx files.
        val isCssLike = fileExtension?.lowercase() in CSS_LIKE_EXTS

        // Step 0: TS/JS object-path reference — `'{path.in.token}'` or
        // `dt('path.in.token')`. Whole expression range is returned so the
        // replacement covers quotes & braces.
        findJsPathReference(text, offset)?.let { return it }

        if (!isCssLike) {
            // Step 0.3: TS/JS callable helper — `spacing(0.5)`, `radius(2)`.
            // The whole call expression is captured (name + parens + argument)
            // so the alternatives popup can offer scale variants
            // (`spacing(0.25)`, `spacing(1)`, …) and replace the full call
            // atomically. Tried BEFORE the property chain because `spacing`
            // would otherwise match step 0.5 first as a bare identifier.
            findHelperCall(text, offset)?.let { return it }

            // Step 0.5: TS/JS runtime property-access chain —
            // `colors.PRIMARY_500`, `theme.radius.sm`. Requires at least one
            // `.` so a plain identifier (which IntelliJ already handles)
            // doesn't get hijacked.
            findRuntimePropertyChain(text, offset)?.let { return it }
        }

        // Step 1: expand around the caret over identifier characters.
        // We treat `-` and `_` as part of the identifier — note this means
        // `--name` is fully captured as a single ident range starting with `--`.
        val (s, e) = expandIdent(text, offset)

        // Step 2a: ident range starts with `--` → CSS custom property.
        if (e - s >= 3 && text[s] == '-' && text[s + 1] == '-') {
            val name = text.subSequence(s, e).toString()
            return Hit(name, s, e)
        }
        // Step 2b: ident range preceded by `$` → SCSS variable.
        if (s in 1..text.length && s - 1 >= 0 && text[s - 1] == '$' && e > s) {
            val name = "\$" + text.subSequence(s, e).toString()
            return Hit(name, s - 1, e)
        }
        // Step 3: caret sat exactly on a prefix character (`$`, or one of the `--`).
        return findFromPrefixUnderCaret(text, offset)
    }

    /**
     * Detect a JS/TS object-path reference enclosing [offset]. Two shapes:
     *  - `'{a.b.c}'` / `"{a.b.c}"` (Style-Dictionary aliases)
     *  - `dt('a.b.c')` / `dt("a.b.c")` (PrimeUIX preset helper)
     * The Hit range covers the *whole* expression so a replacement swaps the
     * entire literal, not just the inner path.
     */
    private fun findJsPathReference(text: CharSequence, offset: Int): Hit? {
        // Quoted-brace form: scan for an enclosing `'{ ... }'` / `"{ ... }"`.
        for (quote in listOf('\'', '"', '`')) {
            val opener = "$quote{"
            val closer = "}$quote"
            val open = lastIndexOf(text, opener, offset)
            if (open >= 0) {
                val close = indexOf(text, closer, open + opener.length)
                if (close in offset..(text.length - closer.length)) {
                    val pathStart = open + opener.length
                    val path = text.subSequence(pathStart, close).toString()
                    if (isPathLike(path)) return Hit(path, open, close + closer.length)
                }
            }
        }
        // `dt('path')` form.
        val dtRegex = Regex("dt\\(\\s*[\"'`]([A-Za-z_][A-Za-z0-9_.-]*)[\"'`]\\s*\\)")
        for (m in dtRegex.findAll(text)) {
            if (offset in m.range.first..m.range.last + 1) {
                return Hit(m.groupValues[1], m.range.first, m.range.last + 1)
            }
        }
        return null
    }

    private fun lastIndexOf(text: CharSequence, needle: String, fromOffset: Int): Int {
        var i = (fromOffset - 1).coerceAtMost(text.length - needle.length)
        while (i >= 0) {
            if (matches(text, i, needle)) return i
            i--
        }
        return -1
    }

    private fun indexOf(text: CharSequence, needle: String, fromOffset: Int): Int {
        var i = fromOffset
        val limit = text.length - needle.length
        while (i <= limit) {
            if (matches(text, i, needle)) return i
            i++
        }
        return -1
    }

    private fun matches(text: CharSequence, at: Int, needle: String): Boolean {
        for (k in needle.indices) if (text[at + k] != needle[k]) return false
        return true
    }

    private fun isPathLike(s: String): Boolean =
        s.isNotEmpty() && s.first().let { it.isLetter() || it == '_' } &&
            s.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    /**
     * Expands the caret position over a maximal `IDENT(.IDENT)+` chain — what
     * a React-Native / CSS-in-JS theme usage typically looks like in source:
     * `colors.PRIMARY_500`, `theme.radius.sm`, `nomTheme.colors.NEUTRAL_700`.
     *
     * Returns `null` when:
     *  - the resulting span contains no `.` (just a plain identifier — let
     *    IntelliJ's own completion handle it);
     *  - any segment isn't a valid JS identifier (so `1.5` or `.foo` is
     *    rejected);
     *  - the chain is preceded by `$` or `-` (would clash with SCSS / CSS
     *    custom-property detection downstream).
     */
    /**
     * Detects an `IDENT(ARGS)` call expression around [offset]. Used to lift
     * `spacing(0.5)` into a single Hit so the alternatives popup can offer
     * other scale values (`spacing(0.25)`, `spacing(1)`, …) and replace the
     * whole call. The Hit's name is the full call expression (e.g.
     * `spacing(0.5)`); downstream popup logic parses it back when needed.
     *
     * Limited to single, well-formed calls — nested parens inside ARGS are
     * skipped because they usually mean we're looking at a chained or
     * composed expression we shouldn't blindly replace.
     */
    private fun findHelperCall(text: CharSequence, offset: Int): Hit? {
        // Search a `(` to the left and a `)` to the right of [offset]. Bail
        // on `;`, `\n`, `,`, `}` or an early `)` since those mark statement
        // boundaries we should never cross.
        val maxScan = 200
        var open = -1
        var i = offset
        // If the caret is exactly on `(`, treat that as the opening paren.
        if (i < text.length && text[i] == '(') {
            open = i
        }
        if (open == -1) {
            var k = offset - 1
            var leftLimit = maxOf(0, offset - maxScan)
            var depth = 0
            while (k >= leftLimit) {
                val c = text[k]
                if (c == ')') depth++
                else if (c == '(') {
                    if (depth == 0) { open = k; break }
                    depth--
                } else if (c == ';' || c == '\n' || c == '{' || c == '}') break
                k--
            }
        }
        if (open == -1) {
            // Caret may sit on the helper name with `(` ahead. Expand the
            // identifier and check the next non-space char.
            val (s, e) = expandIdent(text, offset)
            if (e == s) return null
            var j = e
            while (j < text.length && text[j].isWhitespace()) j++
            if (j >= text.length || text[j] != '(') return null
            open = j
        }
        // Find the matching close paren.
        var close = -1
        var depth = 1
        val rightLimit = minOf(text.length, open + maxScan)
        var j = open + 1
        while (j < rightLimit) {
            when (text[j]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) { close = j; break } }
                ';', '\n', '{', '}' -> return null
            }
            j++
        }
        if (close == -1) return null
        // Caret must sit somewhere between the start of IDENT and close+1.
        var idEnd = open
        while (idEnd > 0 && text[idEnd - 1].isWhitespace()) idEnd--
        var idStart = idEnd
        while (idStart > 0 && isIdentChar(text[idStart - 1])) idStart--
        if (idStart == idEnd) return null
        if (offset < idStart || offset > close + 1) return null
        // Reject identifiers preceded by `.` — those are method calls
        // (`obj.method(…)`) where the bare identifier alone wouldn't resolve
        // to a token in the index.
        if (idStart > 0 && text[idStart - 1] == '.') return null
        // Reject CSS functional notations (`var(--x)`, `calc(...)`, `rgb(...)`)
        // that may appear inside backtick template literals. They look like
        // helper calls but their argument is a CSS construct that the CSS /
        // SCSS detectors downstream handle correctly.
        val identName = text.subSequence(idStart, idEnd).toString()
        if (identName in CSS_FUNCTIONS) return null
        val expression = text.subSequence(idStart, close + 1).toString()
        return Hit(expression, idStart, close + 1)
    }

    private val CSS_FUNCTIONS = setOf(
        "var", "calc", "min", "max", "clamp", "env",
        "rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color",
        "url", "attr",
        "linear-gradient", "radial-gradient", "conic-gradient",
        "repeating-linear-gradient", "repeating-radial-gradient",
        "translate", "translateX", "translateY", "translateZ", "translate3d",
        "rotate", "rotateX", "rotateY", "rotateZ", "rotate3d",
        "scale", "scaleX", "scaleY", "scaleZ", "scale3d",
        "skew", "skewX", "skewY", "matrix", "matrix3d", "perspective",
        "cubic-bezier", "steps",
    )

    private fun findRuntimePropertyChain(text: CharSequence, offset: Int): Hit? {
        fun isChainChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_' || c == '$' || c == '.'

        var s = offset
        while (s > 0 && isChainChar(text[s - 1])) s--
        var e = offset
        while (e < text.length && isChainChar(text[e])) e++

        // Trim leading/trailing dots so `colors.` or `.foo.bar` normalise.
        while (s < e && text[s] == '.') s++
        while (e > s && text[e - 1] == '.') e--
        if (e - s < 3) return null

        val name = text.subSequence(s, e).toString()
        if (!name.contains('.')) return null
        if (name.split('.').any { seg ->
                seg.isEmpty() || seg[0].let { !it.isLetter() && it != '_' && it != '$' }
            }) return null
        if (s > 0 && (text[s - 1] == '$' || text[s - 1] == '-')) return null
        return Hit(name, s, e)
    }

    private fun expandIdent(text: CharSequence, offset: Int): Pair<Int, Int> {
        var s = offset
        while (s > 0 && isIdentChar(text[s - 1])) s--
        var e = offset
        while (e < text.length && isIdentChar(text[e])) e++
        return s to e
    }

    private fun findFromPrefixUnderCaret(text: CharSequence, offset: Int): Hit? {
        // Caret on `$`: look at the next char as ident start.
        if (offset < text.length && text[offset] == '$') {
            val identStart = offset + 1
            val identEnd = scanForward(text, identStart)
            if (identEnd > identStart) {
                val name = "\$" + text.subSequence(identStart, identEnd).toString()
                return Hit(name, offset, identEnd)
            }
        }
        // Caret on the first `-` of `--`: the dash at offset is start.
        if (offset < text.length - 1 && text[offset] == '-' && text[offset + 1] == '-') {
            val identEnd = scanForward(text, offset + 2)
            if (identEnd > offset + 2) {
                val name = text.subSequence(offset, identEnd).toString()
                return Hit(name, offset, identEnd)
            }
        }
        // Caret on the second `-` of `--`: prev char is also `-`.
        if (offset in 1 until text.length && text[offset] == '-' && text[offset - 1] == '-') {
            val identEnd = scanForward(text, offset + 1)
            if (identEnd > offset + 1) {
                val name = text.subSequence(offset - 1, identEnd).toString()
                return Hit(name, offset - 1, identEnd)
            }
        }
        return null
    }

    private fun scanForward(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && isIdentChar(text[i])) i++
        return i
    }

    private fun isIdentChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-'

    /** File extensions where JS/TS-only detectors must be skipped. */
    private val CSS_LIKE_EXTS = setOf("css", "scss", "sass")
}
