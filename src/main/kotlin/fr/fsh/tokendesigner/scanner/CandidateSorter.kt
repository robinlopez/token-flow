package fr.fsh.tokendesigner.scanner

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.ui.ColorParser

object CandidateSorter {

    /**
     * Returns same-category tokens ordered by structural similarity to the pivot,
     * with ties broken by their order of appearance in the source file(s).
     *
     * Priority of comparators (most relevant first):
     *  1. Number of common naming segments with the pivot (descending) — tokens
     *     sharing `[domain]-[family]-[level]-[property]` come first, then those
     *     sharing one less segment, and so on.
     *  2. Source file path then character offset — preserves the author's
     *     intentional grouping in the design system source (e.g. all `content-*`
     *     before all `surface-*` because that is how they are declared).
     *
     * When `pivot` is null, falls back to category-specific ordering (numeric for
     * spacing, hue for colors, alphabetic otherwise).
     */
    fun sort(category: TokenCategory, candidates: List<DesignToken>, pivot: DesignToken?): List<DesignToken> {
        if (pivot == null) return sortWithoutPivot(category, candidates)

        val pivotStruct = TokenNameParser.parse(pivot.name)

        return candidates.sortedWith(
            compareBy(
                { -TokenNameParser.commonStructuralPrefix(pivotStruct, TokenNameParser.parse(it.name)) },
                { it.filePath },
                { it.offset },
            )
        )
    }

    private fun sortWithoutPivot(category: TokenCategory, candidates: List<DesignToken>): List<DesignToken> {
        return when (category) {
            TokenCategory.COLOR -> candidates.sortedWith(
                compareBy(
                    { ColorParser.parse(it.resolvedValue)?.let(::toHsl)?.first ?: Float.MAX_VALUE },
                    { ColorParser.parse(it.resolvedValue)?.let(::toHsl)?.third ?: 0f },
                    { it.name },
                )
            )
            TokenCategory.SPACING,
            TokenCategory.RADIUS,
            TokenCategory.DURATION,
            TokenCategory.Z_INDEX -> candidates.sortedBy { numericPart(it.resolvedValue) ?: Float.MAX_VALUE }
            else -> candidates.sortedBy { it.name }
        }
    }

    private fun toHsl(color: java.awt.Color): Triple<Float, Float, Float> {
        val r = color.red / 255f
        val g = color.green / 255f
        val b = color.blue / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
        var h = when (max) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        h /= 6f
        return Triple(h, s, l)
    }

    private val NUMERIC_REGEX = Regex("^(-?\\d*\\.?\\d+)")
    private fun numericPart(value: String): Float? =
        NUMERIC_REGEX.find(value.trim())?.value?.toFloatOrNull()
}
