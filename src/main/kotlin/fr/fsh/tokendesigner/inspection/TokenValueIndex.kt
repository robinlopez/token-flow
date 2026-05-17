package fr.fsh.tokendesigner.inspection

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.ui.ColorParser

/**
 * Builds a normalised lookup `value → tokens` from a list of [DesignToken].
 *
 *  - **Colors** are normalised to lowercase `#rrggbb` (or `#rrggbbaa`),
 *    so `#FFF`, `#ffffff`, `rgb(255,255,255)` and `white` collide.
 *  - **Lengths** (px/rem/em) are converted to a canonical px value assuming
 *    a 16px root font-size, so `12px`, `0.75rem` and `0.75em` all map to the
 *    same key. Other units (%, vh, vw…) and durations (ms/s) keep their raw
 *    lowercase form because no obvious unit conversion exists.
 *  - Other categories: lowercased trim only.
 */
class TokenValueIndex(tokens: Collection<DesignToken>) {

    /**
     * Per-category lookup. Indexing by category prevents semantic leakage
     * across kinds — a SHADOW blur token whose value happens to be `6px`
     * must NOT surface as a suggestion for `padding: 6px`. Cross-category
     * fallback within length-bearing categories is opt-in via [LENGTH_FAMILY].
     */
    private val byCategory: Map<TokenCategory, Map<String, List<DesignToken>>>

    init {
        val grouped = HashMap<TokenCategory, HashMap<String, MutableList<DesignToken>>>()
        for (token in tokens) {
            val key = normalize(token.resolvedValue, token.category) ?: continue
            grouped
                .getOrPut(token.category) { HashMap() }
                .getOrPut(key) { mutableListOf() } += token
        }
        byCategory = grouped
    }

    /**
     * Returns tokens that resolve to [literal] **within [category]**. When
     * [category] belongs to [LENGTH_FAMILY] the lookup widens to every other
     * length-bearing category, so a `12px` literal under a `border-radius`
     * context can still match a SPACING token with the same value — but the
     * result will never leak into SHADOW, EFFECTS, DURATION, OPACITY or
     * COLOR, which is the source of the spurious suggestions we used to ship.
     */
    fun lookup(literal: String, category: TokenCategory): List<DesignToken> {
        val key = normalize(literal, category) ?: return emptyList()
        val categories = if (category in LENGTH_FAMILY) LENGTH_FAMILY else listOf(category)
        val out = mutableListOf<DesignToken>()
        for (c in categories) {
            byCategory[c]?.get(key)?.let { out += it }
        }
        return out
    }

    companion object {
        private const val ROOT_FONT_SIZE_PX = 16.0
        private val LENGTH_REGEX = Regex("^(-?\\d*\\.?\\d+)(px|rem|em)$")

        /**
         * Categories whose values are conventionally lengths (px/rem/em). A
         * spacing token at 12px is a defensible fallback for a typography or
         * radius context, and vice versa — but SHADOW / EFFECTS / DURATION /
         * OPACITY / Z_INDEX / COLOR sit outside this family.
         */
        private val LENGTH_FAMILY = listOf(
            TokenCategory.SPACING,
            TokenCategory.RADIUS,
            TokenCategory.SIZING,
            TokenCategory.TYPOGRAPHY,
            TokenCategory.BORDER,
        )

        fun normalize(value: String, category: TokenCategory): String? {
            val v = value.trim().lowercase()
            if (v.isEmpty()) return null
            return when (category) {
                TokenCategory.COLOR -> normalizeColor(v)
                TokenCategory.SPACING,
                TokenCategory.RADIUS,
                TokenCategory.TYPOGRAPHY -> normalizeLength(v) ?: v
                else -> v
            }
        }

        private fun normalizeColor(value: String): String? {
            val color = ColorParser.parse(value) ?: return value
            val r = color.red; val g = color.green; val b = color.blue; val a = color.alpha
            val base = "#%02x%02x%02x".format(r, g, b)
            return if (a == 255) base else base + "%02x".format(a)
        }

        /**
         * Converts a px/rem/em length to a canonical px string. Returns `null`
         * when the value does not parse as one of those units (e.g. `100%`,
         * `100vh`, multi-value like `12px 16px`).
         */
        private fun normalizeLength(value: String): String? {
            val match = LENGTH_REGEX.matchEntire(value) ?: return null
            val n = match.groupValues[1].toDoubleOrNull() ?: return null
            val px = when (match.groupValues[2]) {
                "px" -> n
                "rem", "em" -> n * ROOT_FONT_SIZE_PX
                else -> n
            }
            return formatPx(px) + "px"
        }

        private fun formatPx(d: Double): String {
            // Preserve sub-pixel precision (e.g. 0.5px, 1.25px) but strip trailing zeros.
            if (d == d.toLong().toDouble()) return d.toLong().toString()
            return ("%.4f".format(d)).trimEnd('0').trimEnd('.')
        }
    }
}
