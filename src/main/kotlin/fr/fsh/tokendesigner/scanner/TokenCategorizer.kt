package fr.fsh.tokendesigner.scanner

import fr.fsh.tokendesigner.model.TokenCategory

object TokenCategorizer {

    private val COLOR_REGEX = Regex(
        "^(#[0-9a-fA-F]{3,8}|(rgb|rgba|hsl|hsla|hwb|lab|lch|oklab|oklch|color)\\s*\\(.*\\))\\s*$"
    )
    private val NAMED_COLORS = setOf(
        "transparent", "currentcolor", "black", "white", "red", "green", "blue",
        "yellow", "orange", "purple", "pink", "gray", "grey",
    )
    private val LENGTH_REGEX = Regex(
        "^-?\\d*\\.?\\d+(px|rem|em|%|vh|vw|vmin|vmax|ch|ex)\\s*$"
    )
    private val DURATION_REGEX = Regex("^-?\\d*\\.?\\d+(ms|s)\\s*$")
    private val SHADOW_HINT = Regex("\\d+(px|rem|em).*\\d+(px|rem|em)")

    fun categorize(name: String, resolvedValue: String): TokenCategory {
        val n = name.lowercase().trimStart('-', '$')
        val v = resolvedValue.trim()

        nameHints(n)?.let { return it }
        return valueHints(v) ?: TokenCategory.OTHER
    }

    private fun nameHints(name: String): TokenCategory? = when {
        contains(name, "color", "colour", "bg", "background", "border-color", "fill", "stroke") -> TokenCategory.COLOR
        contains(name, "font", "text", "type", "weight", "leading", "line-height", "letter") -> TokenCategory.TYPOGRAPHY
        contains(name, "shadow", "elevation") -> TokenCategory.SHADOW
        contains(name, "radius", "rounded") -> TokenCategory.RADIUS
        contains(name, "duration", "transition", "delay", "ease", "motion") -> TokenCategory.DURATION
        contains(name, "z-index", "zindex", "layer") -> TokenCategory.Z_INDEX
        contains(name, "space", "spacing", "gap", "margin", "padding", "size", "inset") -> TokenCategory.SPACING
        else -> null
    }

    private fun valueHints(value: String): TokenCategory? = when {
        COLOR_REGEX.matches(value) -> TokenCategory.COLOR
        value.lowercase() in NAMED_COLORS -> TokenCategory.COLOR
        DURATION_REGEX.matches(value) -> TokenCategory.DURATION
        SHADOW_HINT.containsMatchIn(value) && value.contains(",") -> TokenCategory.SHADOW
        LENGTH_REGEX.matches(value) -> TokenCategory.SPACING
        value.toIntOrNull() != null -> TokenCategory.Z_INDEX
        else -> null
    }

    private fun contains(name: String, vararg needles: String): Boolean =
        needles.any { name.contains(it) }
}
