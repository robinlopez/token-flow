package fr.fsh.tokendesigner.inspection

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.ui.ColorParser
import java.awt.Color

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

    fun findSuggestions(
        hit: LiteralFinder.Hit,
        valueIndex: TokenValueIndex,
        allTokens: List<DesignToken>,
        expectedCategory: TokenCategory?,
    ): List<TokenSuggestion> {
        val literalCategory = hit.kind.toCategory()
        val exact = valueIndex.lookup(hit.text, literalCategory)
        if (exact.isNotEmpty()) {
            return exact
                .map { TokenSuggestion(it, exact = true, delta = 0.0) }
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
    }
}
