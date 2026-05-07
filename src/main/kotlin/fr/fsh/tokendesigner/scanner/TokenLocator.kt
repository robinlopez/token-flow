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

    fun find(document: Document, offset: Int): Hit? {
        val text = document.charsSequence
        if (offset < 0 || offset > text.length) return null

        // Step 0: TS/JS object-path reference — `'{path.in.token}'` or
        // `dt('path.in.token')`. Whole expression range is returned so the
        // replacement covers quotes & braces.
        findJsPathReference(text, offset)?.let { return it }

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
}
