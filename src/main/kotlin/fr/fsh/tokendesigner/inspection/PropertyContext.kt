package fr.fsh.tokendesigner.inspection

import fr.fsh.tokendesigner.model.TokenCategory

/**
 * Maps CSS property names to the [TokenCategory] one would expect to see
 * assigned to them. Used both by:
 *   - the completion contributor, to boost matching tokens in the lookup
 *   - the hardcoded-value inspection, to prioritise the right token when a
 *     literal value matches several categories (e.g. `12px` matches both a
 *     spacing AND a font-size token).
 *
 * Returns `null` when no clear category mapping can be inferred.
 */
object PropertyContext {

    private val PROP_REGEX = Regex("([a-zA-Z][a-zA-Z-]*)\\s*:")

    /** Extracts the property at [offset] by scanning back to the nearest `{`, `}` or `;`. */
    fun detectAt(text: CharSequence, offset: Int): TokenCategory? {
        val name = detectPropertyNameAt(text, offset) ?: return null
        return categoryFor(name)
    }

    /** Returns the raw CSS property name (e.g. `font-size`, `padding-left`) at [offset], or null. */
    fun detectPropertyNameAt(text: CharSequence, offset: Int): String? {
        val start = lookBackToSeparator(text, offset)
        val window = text.subSequence(start, offset)
        val match = PROP_REGEX.findAll(window).lastOrNull() ?: return null
        return match.groupValues[1].lowercase()
    }

    fun categoryFor(property: String): TokenCategory? {
        val p = property.lowercase()
        return when {
            p == "color" || p.endsWith("-color") || p in COLOR_PROPS -> TokenCategory.COLOR
            p == "z-index" -> TokenCategory.Z_INDEX
            p.contains("radius") -> TokenCategory.RADIUS
            p.contains("shadow") -> TokenCategory.SHADOW
            p.startsWith("font") || p in TYPO_PROPS -> TokenCategory.TYPOGRAPHY
            p.contains("transition") || p.contains("animation") || p == "duration" || p.endsWith("-delay") -> TokenCategory.DURATION
            p in SPACING_PROPS || p.startsWith("padding") || p.startsWith("margin") || p.startsWith("inset") -> TokenCategory.SPACING
            else -> null
        }
    }

    private fun lookBackToSeparator(text: CharSequence, offset: Int): Int {
        var i = offset - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '{' || c == '}' || c == ';') return i + 1
            i--
        }
        return 0
    }

    private val COLOR_PROPS = setOf(
        "background", "fill", "stroke", "outline",
        "caret-color", "accent-color", "column-rule-color", "scrollbar-color",
    )
    private val SPACING_PROPS = setOf(
        "gap", "row-gap", "column-gap",
        "top", "left", "right", "bottom",
        "width", "height",
        "min-width", "min-height", "max-width", "max-height",
    )
    private val TYPO_PROPS = setOf(
        "line-height", "letter-spacing", "word-spacing", "text-indent",
    )
}
