package fr.fsh.tokendesigner.inspection

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.ui.ColorParser
import java.awt.Color
import kotlin.math.abs

data class TokenSuggestion(
    val token: DesignToken,
    val exact: Boolean,
    val delta: Double = 0.0,
)

/**
 * Computes the prioritised list of design tokens that could replace a
 * hardcoded literal. Used by [HardcodedValueInspection] (single-occurrence
 * quick-fix) and by the dashboard "Hardcoded values" panel (whole-file scan).
 *
 * Ordering rules:
 *  1. Tokens whose category matches the surrounding CSS property come first
 *     (e.g. `font-size: 12px` ⇒ TYPOGRAPHY tokens before SPACING tokens with
 *     the same value).
 *  2. For colors with no exact match, tokens within [COLOR_DELTA_MAX] in
 *     normalised RGBA distance are returned, sorted by proximity.
 *  3. Token name length (shorter is more semantic).
 */
object SuggestionEngine {

    const val MAX_SUGGESTIONS = 5
    const val COLOR_DELTA_MAX = 0.05
    private const val HELPER_MIN_MULTIPLIER = 0.25
    private const val HELPER_MAX_MULTIPLIER = 12.0
    private const val HELPER_MULTIPLIER_TOLERANCE = 0.05
    private const val HELPER_VALUE_TOLERANCE = 0.5

    fun findSuggestions(
        hit: LiteralFinder.Hit,
        valueIndex: TokenValueIndex,
        allTokens: List<DesignToken>,
        expectedCategory: TokenCategory?,
    ): List<TokenSuggestion> {
        val literalCategory = hit.kind.toCategory()
        val exact = valueIndex.lookup(hit.text, literalCategory)
        // Helper-aware exact matches: a hardcoded `12` or `12px` can be
        // produced by a `spacing(value)` helper whose unit is `8` (call it
        // with `1.5`). Compose them with the direct exact matches so the
        // user sees both options.
        val helperMatches = helperSuggestionsFor(hit, allTokens)
        if (exact.isNotEmpty() || helperMatches.isNotEmpty()) {
            val direct = exact.map { TokenSuggestion(it, exact = true, delta = 0.0) }
            return (direct + helperMatches)
                .sortedBy { score(it, expectedCategory) }
                .take(MAX_SUGGESTIONS)
        }
        if (hit.kind == LiteralFinder.Kind.COLOR) {
            val literalColor = ColorParser.parse(hit.text) ?: return emptyList()
            return allTokens.asSequence()
                .filter { it.category == TokenCategory.COLOR }
                .mapNotNull { token ->
                    val tokenColor = ColorParser.parse(token.resolvedValue) ?: return@mapNotNull null
                    val delta = colorDistance(literalColor, tokenColor)
                    if (delta <= COLOR_DELTA_MAX) TokenSuggestion(token, exact = false, delta = delta) else null
                }
                .sortedBy { it.delta }
                .take(MAX_SUGGESTIONS)
                .toList()
        }
        return emptyList()
    }

    /**
     * Inverse-applies every indexed callable helper (`spacing`, `radius`, …)
     * to the literal under [hit]. A helper with `functionUnit = 8` matches a
     * hardcoded `12px` when `12 / 8 = 1.5` rounds to a clean quarter step —
     * the result is exposed as a synthetic [DesignToken] named `spacing(1.5)`
     * so the existing replacement pipeline inserts the literal call verbatim.
     *
     * Only LENGTH / DURATION hits are inverted. Colour helpers would need a
     * different shape (they aren't linear) and aren't attempted here.
     */
    private fun helperSuggestionsFor(
        hit: LiteralFinder.Hit,
        allTokens: List<DesignToken>,
    ): List<TokenSuggestion> {
        if (hit.kind == LiteralFinder.Kind.COLOR) return emptyList()
        val helpers = allTokens.filter { it.kind == TokenKind.JS_RUNTIME_FUNCTION && it.functionUnit != null }
        if (helpers.isEmpty()) return emptyList()
        val literal = parseLiteralMagnitude(hit) ?: return emptyList()
        val out = mutableListOf<TokenSuggestion>()
        for (helper in helpers) {
            val unit = helper.functionUnit ?: continue
            val multiplier = literal / unit
            val snapped = snapToQuarter(multiplier) ?: continue
            val produced = unit * snapped
            if (abs(produced - literal) > HELPER_VALUE_TOLERANCE) continue
            val call = "${helper.name}(${formatMultiplier(snapped)})"
            val synthetic = helper.copy(
                name = call,
                rawValue = formatProduced(produced),
                resolvedValue = formatProduced(produced),
            )
            out += TokenSuggestion(synthetic, exact = snapped == multiplier, delta = 0.0)
        }
        return out
    }

    private fun parseLiteralMagnitude(hit: LiteralFinder.Hit): Double? {
        val text = hit.text.trim().lowercase()
        // Strip the trailing unit if any — helpers in RN are unitless or px.
        val numeric = text.trimEnd { !it.isDigit() && it != '.' && it != '-' }
        return numeric.toDoubleOrNull()
    }

    private fun snapToQuarter(m: Double): Double? {
        val rounded = Math.round(m * 4.0) / 4.0
        if (rounded < HELPER_MIN_MULTIPLIER || rounded > HELPER_MAX_MULTIPLIER) return null
        if (abs(m - rounded) > HELPER_MULTIPLIER_TOLERANCE) return null
        return rounded
    }

    private fun formatMultiplier(m: Double): String =
        if (m == m.toLong().toDouble()) m.toLong().toString() else m.toString()

    private fun formatProduced(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun score(s: TokenSuggestion, expected: TokenCategory?): Int {
        var n = 0
        if (expected != null && s.token.category == expected) n -= 100
        n += s.token.name.length
        return n
    }

    private fun colorDistance(a: Color, b: Color): Double {
        val dr = (a.red - b.red) / 255.0
        val dg = (a.green - b.green) / 255.0
        val db = (a.blue - b.blue) / 255.0
        val da = (a.alpha - b.alpha) / 255.0
        return Math.sqrt(dr * dr + dg * dg + db * db + da * da) / 2.0
    }

    private fun LiteralFinder.Kind.toCategory(): TokenCategory = when (this) {
        LiteralFinder.Kind.COLOR -> TokenCategory.COLOR
        LiteralFinder.Kind.LENGTH -> TokenCategory.SPACING
        LiteralFinder.Kind.DURATION -> TokenCategory.DURATION
        // Unitless numbers in JS objects can be spacings, font sizes, line-heights,
        // z-indexes… The category falls back to SPACING because every numeric
        // category normalises identical raw numbers to the same key, so the
        // lookup widens across all of them anyway (see `TokenValueIndex`).
        LiteralFinder.Kind.NUMBER -> TokenCategory.SPACING
    }
}
