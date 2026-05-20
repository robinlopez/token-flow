package fr.fsh.tokendesigner.inspection

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.model.TokenRole
import fr.fsh.tokendesigner.ui.ColorParser
import java.awt.Color
import kotlin.math.abs

enum class TokenTier { PRIMITIVE, SEMANTIC, COMPONENT }

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
        expectedRole: TokenRole? = null,
    ): List<TokenSuggestion> {
        // Prefer the CSS-property-derived category over the literal's natural
        // one when known: `padding: 6px` looks up under SPACING, `font-size:
        // 12px` under TYPOGRAPHY, etc. Falling back to the literal's category
        // keeps the lookup working in JS object literals where the property
        // context is ambiguous.
        val lookupCategory = expectedCategory ?: hit.kind.toCategory()
        val exact = valueIndex.lookup(hit.text, lookupCategory)
        // Helper-aware exact matches: a hardcoded `12` or `12px` can be
        // produced by a `spacing(value)` helper whose unit is `8` (call it
        // with `1.5`). Compose them with the direct exact matches so the
        // user sees both options.
        val helperMatches = helperSuggestionsFor(hit, allTokens)
        if (exact.isNotEmpty() || helperMatches.isNotEmpty()) {
            val direct = exact.map { TokenSuggestion(it, exact = true, delta = 0.0) }
            return (direct + helperMatches)
                .sortedBy { score(it, expectedCategory, expectedRole) }
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
                // Primary: color distance. Secondary: semantic score, so when
                // two tokens sit at near-identical distance the one with the
                // right role (e.g. -surface- on a `background`) wins.
                .sortedWith(compareBy({ it.delta }, { score(it, expectedCategory, expectedRole) }))
                .take(MAX_SUGGESTIONS)
                .toList()
        }
        if (hit.kind == LiteralFinder.Kind.REFERENCE) {
            val fallback = extractFallback(hit.text)
            if (fallback != null) {
                // If the broken reference has a fallback value (e.g. #f0f0f0),
                // try to suggest tokens that match that specific value first.
                val kind = if (ColorParser.parse(fallback) != null) LiteralFinder.Kind.COLOR else LiteralFinder.Kind.NUMBER
                val suggestions = findSuggestions(LiteralFinder.Hit(fallback, 0, 0, kind), valueIndex, allTokens, expectedCategory, expectedRole)
                if (suggestions.isNotEmpty()) return suggestions
            }

            val extractedName = fr.fsh.tokendesigner.analyze.DesignSystemAnalyzer.extractTokenName(hit.text) ?: hit.text
            val name = extractedName.lowercase().removePrefix("--").removePrefix("$")

            return allTokens.asSequence()
                .filter { token -> expectedCategory == null || token.category == expectedCategory }
                .map { token ->
                    val tokenClean = token.name.lowercase().removePrefix("--").removePrefix("$")
                    val distance = levenshtein(name, tokenClean)
                    val similarity = 1.0 - (distance.toDouble() / maxOf(name.length, tokenClean.length).coerceAtLeast(1))
                    TokenSuggestion(token, exact = false, delta = 1.0 - similarity)
                }
                .filter { it.delta < 0.4 }
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

    /**
     * Multi-criterion ranking. Lower = better.
     *
     * Weights are calibrated so that, in order of dominance:
     *  1. category match beats anything else,
     *  2. role match/mismatch dominates over tier,
     *  3. tier (semantic > component > primitive) sorts equal-role candidates,
     *  4. token-name length is only a final tiebreaker.
     *
     * The primitive penalty is intentionally *relative*: when no semantic
     * alternative shares the same value, primitives still surface — they're
     * just outranked when a semantic sibling exists (the canonical case is
     * `--units-xl` vs `--spacing-xl`, both 32px).
     */
    private fun score(
        s: TokenSuggestion,
        expectedCategory: TokenCategory?,
        expectedRole: TokenRole?,
    ): Int {
        var n = 0
        val nameNorm = s.token.name.lowercase().trimStart('-', '$')

        // 1. Category alignment with the surrounding CSS property.
        if (expectedCategory != null && s.token.category == expectedCategory) n -= 100

        // 2. Role alignment (surface / content / stroke / effect).
        if (expectedRole != null) {
            val role = roleOf(nameNorm)
            when {
                role == expectedRole -> n -= 80
                role == null -> Unit                // unknown — neutral
                else -> n += 60                     // wrong role — actively demote
            }
        }

        // 3. Token tier: prefer semantic > component > primitive.
        when (tierOf(nameNorm)) {
            TokenTier.SEMANTIC -> n -= 30
            TokenTier.COMPONENT -> n -= 10
            TokenTier.PRIMITIVE -> n += 40
        }

        // 4. Exact-value matches beat fuzzy / helper-derived ones.
        if (!s.exact) n += 5

        // 5. Name length — only kicks in as a last tiebreaker (divided so it
        //    can't outweigh a tier or role decision).
        n += s.token.name.length / 4

        return n
    }

    private val PRIMITIVE_PREFIXES = setOf(
        "units", "unit", "palette", "base", "primitive", "primitives",
        "core", "scale", "raw",
    )
    private val COMPONENT_PREFIXES = setOf(
        "comp", "component", "components",
    )

    /**
     * Classifies a token name into a DS tier based on its leading segment.
     * Conservative: only flags as PRIMITIVE names that *clearly* belong to a
     * raw-scale layer (e.g. `units-`, `palette-`). When in doubt, falls back
     * to SEMANTIC so we don't accidentally demote legitimate suggestions.
     */
    fun tierOf(rawName: String): TokenTier {
        val n = rawName.lowercase().trimStart('-', '$')
        val head = n.substringBefore('-')
        return when {
            head in PRIMITIVE_PREFIXES -> TokenTier.PRIMITIVE
            n.startsWith("token-") || head in COMPONENT_PREFIXES -> TokenTier.COMPONENT
            else -> TokenTier.SEMANTIC
        }
    }

    /**
     * Extracts the role segment from a token name. Looks for `-surface-`,
     * `-content-`, `-stroke-`, etc. as hyphen-delimited segments anywhere in
     * the name (also matches at the start, e.g. `surface-default`). Returns
     * `null` when no recognised role marker is present.
     */
    fun roleOf(rawName: String): TokenRole? {
        val n = rawName.lowercase().trimStart('-', '$')
        val segments = n.split('-')
        for (seg in segments) {
            when (seg) {
                "surface", "background", "bg", "fill", "canvas" -> return TokenRole.SURFACE
                "content", "text", "foreground", "fg", "label", "icon" -> return TokenRole.CONTENT
                "stroke", "border", "outline", "divider" -> return TokenRole.STROKE
                "shadow", "focus", "effect", "effects", "glow" -> return TokenRole.EFFECT
            }
        }
        return null
    }

    private fun colorDistance(a: Color, b: Color): Double {
        val dr = (a.red - b.red) / 255.0
        val dg = (a.green - b.green) / 255.0
        val db = (a.blue - b.blue) / 255.0
        val da = (a.alpha - b.alpha) / 255.0
        return Math.sqrt(dr * dr + dg * dg + db * db + da * da) / 2.0
    }

    private fun extractFallback(text: String): String? {
        if (!text.startsWith("var(")) return null
        val comma = text.indexOf(',')
        if (comma == -1) return null
        val lastParen = text.lastIndexOf(')')
        if (lastParen == -1 || lastParen < comma) return null
        return text.substring(comma + 1, lastParen).trim()
    }

    private fun levenshtein(s: String, t: String): Int {
        if (s == t) return 0
        if (s.isEmpty()) return t.length
        if (t.isEmpty()) return s.length
        val v0 = IntArray(t.length + 1) { it }
        val v1 = IntArray(t.length + 1)
        for (i in s.indices) {
            v1[0] = i + 1
            for (j in t.indices) {
                val cost = if (s[i] == t[j]) 0 else 1
                v1[j + 1] = minOf(v1[j] + 1, v0[j + 1] + 1, v0[j] + cost)
            }
            v1.copyInto(v0)
        }
        return v0[t.length]
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
        LiteralFinder.Kind.REFERENCE -> TokenCategory.COLOR // arbitrary, won't be used for value lookup
    }
}
