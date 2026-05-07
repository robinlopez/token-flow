package fr.fsh.tokendesigner.scanner

/**
 * Light-weight parser for design-token files written as TS/JS object literals
 * (e.g. PrimeUIX preset syntax, Style Dictionary refs, Material 3 tokens, …).
 *
 * Looks for top-level `export const NAME = { … }` or `export default { … }`
 * blocks and walks the nested object recording every `path → string` leaf.
 *
 * The path is the dot-joined sequence of keys leading to the leaf, e.g.
 * `global.modeLight.high.surface.default`. Values are stored verbatim, with the
 * surrounding quotes stripped:
 *   `"{primitive.primary.500}"` → `{primitive.primary.500}`
 *   `'12px'` → `12px`
 *   `'#fe5716'` → `#fe5716`
 *
 * Intentionally NOT a full JS parser — it skips arrays, computed keys, function
 * values and template-string interpolations. Good enough for token files which
 * are by convention pure data objects.
 */
object JsObjectTokenParser {

    data class Leaf(val path: String, val value: String, val offset: Int)

    fun parse(text: CharSequence): List<Leaf> {
        val out = mutableListOf<Leaf>()
        // Walk every top-level `export const|default` exported object.
        val regex = Regex("\\bexport\\s+(?:default|const\\s+\\w+)\\s*[:=]?\\s*")
        for (match in regex.findAll(text)) {
            var i = match.range.last + 1
            i = skipWs(text, i)
            if (i >= text.length || text[i] != '{') continue
            walkObject(text, i + 1, ArrayDeque(), out)
        }
        return out
    }

    /**
     * Walks an object starting at [start] (just after the opening `{`), pushing
     * keys onto [path] and emitting leaves into [out]. Returns the index of the
     * character after the matching `}`.
     */
    private fun walkObject(
        text: CharSequence,
        start: Int,
        path: ArrayDeque<String>,
        out: MutableList<Leaf>,
    ): Int {
        var i = start
        while (i < text.length) {
            i = skipWsAndComments(text, i)
            if (i >= text.length) return i
            val c = text[i]
            if (c == '}') return i + 1
            if (c == ',') { i++; continue }

            // Read a key: either a quoted string or a JS identifier.
            val key = readKey(text, i) ?: return i
            i = key.endExclusive
            i = skipWsAndComments(text, i)
            if (i >= text.length || text[i] != ':') return i
            i++ // past ':'
            i = skipWsAndComments(text, i)
            if (i >= text.length) return i

            path.addLast(key.value)
            i = readValue(text, i, path, out)
            path.removeLast()
        }
        return i
    }

    private fun readValue(
        text: CharSequence,
        start: Int,
        path: ArrayDeque<String>,
        out: MutableList<Leaf>,
    ): Int {
        var i = skipWsAndComments(text, start)
        if (i >= text.length) return i
        return when (text[i]) {
            '{' -> walkObject(text, i + 1, path, out)
            '[' -> skipBracketed(text, i, '[', ']')
            '"', '\'', '`' -> {
                val tokenStart = i
                val str = readStringLiteral(text, i)
                out += Leaf(path.joinToString("."), str.value, tokenStart)
                str.endExclusive
            }
            else -> {
                // Number / identifier / expression — read until `,` or `}` at depth 0.
                val rawStart = i
                val end = readPrimitive(text, i)
                val raw = text.subSequence(rawStart, end).toString().trim()
                if (raw.isNotEmpty() && raw != "null" && raw != "undefined") {
                    out += Leaf(path.joinToString("."), raw, rawStart)
                }
                end
            }
        }
    }

    private data class Range(val value: String, val endExclusive: Int)

    private fun readKey(text: CharSequence, start: Int): Range? {
        val c = text.getOrNull(start) ?: return null
        return when (c) {
            '"', '\'', '`' -> readStringLiteral(text, start)
            else -> {
                // Accept identifier-style keys *and* unquoted numeric keys
                // (e.g. `neutral: { 700: '#fff' }`), which Style-Dictionary /
                // PrimeUIX presets routinely use for colour-scale shades.
                if (!c.isLetterOrDigit() && c != '_' && c != '$') return null
                var j = start
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$' || text[j] == '-')) j++
                Range(text.subSequence(start, j).toString(), j)
            }
        }
    }

    private fun readStringLiteral(text: CharSequence, start: Int): Range {
        val quote = text[start]
        var j = start + 1
        val sb = StringBuilder()
        while (j < text.length) {
            val ch = text[j]
            if (ch == '\\' && j + 1 < text.length) {
                sb.append(text[j + 1])
                j += 2
                continue
            }
            if (ch == quote) {
                return Range(sb.toString(), j + 1)
            }
            sb.append(ch)
            j++
        }
        return Range(sb.toString(), j)
    }

    private fun readPrimitive(text: CharSequence, start: Int): Int {
        var j = start
        while (j < text.length) {
            val c = text[j]
            if (c == ',' || c == '}' || c == ']' || c == '\n') break
            j++
        }
        return j
    }

    private fun skipBracketed(text: CharSequence, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var j = start
        while (j < text.length) {
            when (text[j]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return j + 1
                }
                '"', '\'', '`' -> j = readStringLiteral(text, j).endExclusive - 1
            }
            j++
        }
        return j
    }

    private fun skipWs(text: CharSequence, start: Int): Int {
        var j = start
        while (j < text.length && text[j].isWhitespace()) j++
        return j
    }

    private fun skipWsAndComments(text: CharSequence, start: Int): Int {
        var j = skipWs(text, start)
        while (j + 1 < text.length && text[j] == '/') {
            when (text[j + 1]) {
                '/' -> {
                    j += 2
                    while (j < text.length && text[j] != '\n') j++
                }
                '*' -> {
                    j += 2
                    while (j + 1 < text.length && !(text[j] == '*' && text[j + 1] == '/')) j++
                    j += 2
                }
                else -> return j
            }
            j = skipWs(text, j)
        }
        return j
    }
}
