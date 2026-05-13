package fr.fsh.tokendesigner.scanner.parsers

/**
 * Detects callable design-token helpers declared as **single-argument linear
 * arrow functions** — the convention used by most React-Native theme files for
 * spacings, sizes and elevations:
 *
 * ```ts
 * const spacingUnit = 8;
 * const spacing = (value: number) => spacingUnit * value;
 * const radius  = (n: number) => Math.floor(4 * Math.abs(n));
 * ```
 *
 * Each match yields a [ParsedHelper] carrying the helper name, the numeric
 * unit (the constant multiplier — resolved through previously declared
 * `const NAME = NUMBER` in the same file), and the original source offset for
 * goto-definition.
 *
 * Intentionally narrow:
 *  - only **arrow** functions with a single non-destructured parameter,
 *  - only **linear** bodies of the form `[wrap(]UNIT * [Math.abs(]param[)][)]`
 *    (or `param *` swapped), where `UNIT` is a numeric literal or a
 *    previously declared local `const`,
 *  - wrappers `Math.floor`, `Math.ceil`, `Math.round` are transparently
 *    skipped (they don't change the inversion math at quarter-step precision).
 *
 * Multi-arg helpers (`normalize(size, 'width')`), polynomial bodies or anything
 * else falls through — those are intentionally NOT indexed for now; surfacing
 * them as suggestions without a sound inverse function would mislead users.
 */
object RuntimeFunctionParser {

    data class ParsedHelper(
        val name: String,
        val paramName: String,
        val paramType: String?,
        val unit: Double,
        /** How the unit appeared in source (`8`, `spacingUnit`). For display. */
        val unitSource: String,
        /** Absolute offset of the helper declaration (the `const` keyword). */
        val offset: Int,
    )

    // `const NAME = (PARAM[: TYPE]) => BODY` — captures up to the arrow.
    // The body is read separately (it may contain matched parens).
    private val DECL = Regex(
        "(?m)^\\s*(?:export\\s+)?const\\s+([A-Za-z_$][\\w$]*)\\s*" +
            "=\\s*\\(\\s*([A-Za-z_$][\\w$]*)(?:\\s*:\\s*([^,)\\n]+?))?\\s*\\)\\s*=>\\s*"
    )

    fun parse(text: CharSequence): List<ParsedHelper> {
        val numericConsts = collectNumericConsts(text)
        val out = mutableListOf<ParsedHelper>()
        for (m in DECL.findAll(text)) {
            val name = m.groupValues[1]
            val param = m.groupValues[2]
            val type = m.groupValues[3].trim().takeIf { it.isNotEmpty() }
            val bodyStart = m.range.last + 1
            val body = readArrowBody(text, bodyStart) ?: continue
            val helper = analyseLinearBody(body, param, numericConsts) ?: continue
            out += ParsedHelper(
                name = name,
                paramName = param,
                paramType = type,
                unit = helper.first,
                unitSource = helper.second,
                offset = m.range.first,
            )
        }
        return out
    }

    /**
     * Reads the body of an arrow function starting at [start]. Handles both
     * `(...) => EXPR;` (expression body, terminated by `;` / newline / EOF)
     * and `(...) => { return EXPR; }` (block body — we extract the `return`).
     */
    private fun readArrowBody(text: CharSequence, start: Int): String? {
        var i = start
        while (i < text.length && text[i].isWhitespace() && text[i] != '\n') i++
        if (i >= text.length) return null
        return if (text[i] == '{') {
            val end = matchBrace(text, i) ?: return null
            val block = text.subSequence(i + 1, end).toString()
            // Extract `return EXPR;` — first `return` on its own.
            val retMatch = Regex("\\breturn\\b\\s*([^;]+)").find(block) ?: return null
            retMatch.groupValues[1].trim()
        } else {
            // Expression body: read until top-level `;`, `,`, `)` or newline.
            val end = readExpressionEnd(text, i)
            text.subSequence(i, end).toString().trim()
        }
    }

    private fun matchBrace(text: CharSequence, openIdx: Int): Int? {
        var depth = 0
        var i = openIdx
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
                '"', '\'', '`' -> i = skipString(text, i)
            }
            i++
        }
        return null
    }

    private fun skipString(text: CharSequence, start: Int): Int {
        val quote = text[start]
        var j = start + 1
        while (j < text.length) {
            val c = text[j]
            if (c == '\\') { j += 2; continue }
            if (c == quote) return j
            j++
        }
        return j
    }

    private fun readExpressionEnd(text: CharSequence, start: Int): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (depth == 0 && (c == ';' || c == '\n')) return i
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    if (depth == 0) return i
                    depth--
                }
                '"', '\'', '`' -> i = skipString(text, i)
            }
            i++
        }
        return i
    }

    /**
     * If [body] is a linear expression in [param] (after stripping `Math.xxx`
     * wrappers and `Math.abs(param)`), returns `(unit, unitSource)`. Otherwise
     * null.
     */
    private fun analyseLinearBody(
        body: String,
        param: String,
        numericConsts: Map<String, Double>,
    ): Pair<Double, String>? {
        var expr = body.trim()
        // Strip outer Math.floor/ceil/round wrappers, possibly chained.
        while (true) {
            val m = Regex("^Math\\.(floor|ceil|round)\\s*\\((.*)\\)\\s*$").matchEntire(expr) ?: break
            expr = m.groupValues[2].trim()
        }
        // Two shapes: `UNIT * PARAM[modifiers]` or `PARAM[modifiers] * UNIT`.
        val mult = Regex("^(.+?)\\s*\\*\\s*(.+)$").matchEntire(expr) ?: return null
        val left = mult.groupValues[1].trim()
        val right = mult.groupValues[2].trim()
        val paramSide = "(?:$param|Math\\.abs\\s*\\(\\s*$param\\s*\\))".toRegex()
        val (unitToken, _) = when {
            paramSide.matches(right) -> left to right
            paramSide.matches(left) -> right to left
            else -> return null
        }
        val unit = unitToken.toDoubleOrNull() ?: numericConsts[unitToken] ?: return null
        if (unit == 0.0) return null
        return unit to unitToken
    }

    /** Collects `const NAME = NUMBER` at top level for unit resolution. */
    private fun collectNumericConsts(text: CharSequence): Map<String, Double> {
        val map = HashMap<String, Double>()
        val regex = Regex(
            "(?m)^\\s*(?:export\\s+)?const\\s+([A-Za-z_$][\\w$]*)\\s*" +
                "(?::\\s*[^=\\n]+?)?\\s*=\\s*(-?\\d*\\.?\\d+)\\s*;?\\s*$"
        )
        for (m in regex.findAll(text)) {
            val n = m.groupValues[2].toDoubleOrNull() ?: continue
            map[m.groupValues[1]] = n
        }
        return map
    }
}
