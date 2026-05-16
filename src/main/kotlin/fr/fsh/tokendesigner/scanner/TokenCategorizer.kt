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

        val nameHint = nameHints(n)
        
        // Disambiguation: "stroke" usually means COLOR in Figma, but if its value is a length (e.g. 1px), it's a BORDER width.
        if (nameHint == TokenCategory.COLOR && contains(n, "stroke", "border") && LENGTH_REGEX.matches(v)) {
            return TokenCategory.BORDER
        }

        nameHint?.let { return it }
        return valueHints(v) ?: TokenCategory.OTHER
    }

    private fun nameHints(name: String): TokenCategory? = when {
        // High priority composites (prevent collision with root words)
        contains(name, "border-color") -> TokenCategory.COLOR
        contains(name, "border-width", "border-style", "stroke-width") -> TokenCategory.BORDER
        contains(name, "box-shadow", "drop-shadow") -> TokenCategory.SHADOW
        contains(name, "line-height") -> TokenCategory.TYPOGRAPHY
        contains(name, "min-width", "max-width") -> TokenCategory.SIZING

        // Specific / Restricted Categories (Priority 2)
        contains(name, "z-index", "zindex", "layer", "depth", "elevation") -> TokenCategory.Z_INDEX
        contains(name, "opacity", "alpha") -> TokenCategory.OPACITY
        contains(name, "icon", "glyph") -> TokenCategory.ICON

        // General Categories
        contains(name, "color", "colour", "bg", "background", "fill", "stroke", "surface", "gradient", "tint", "shade") -> TokenCategory.COLOR
        contains(name, "font", "text", "type", "weight", "leading", "letter", "family", "tracking", "kerning", "decoration") -> TokenCategory.TYPOGRAPHY
        contains(name, "shadow") -> TokenCategory.SHADOW
        contains(name, "radius", "rounded") -> TokenCategory.RADIUS
        contains(name, "duration", "transition", "delay", "ease", "motion", "animation", "timing", "speed") -> TokenCategory.DURATION
        contains(name, "effect", "focus", "blur", "outline") -> TokenCategory.EFFECTS
        contains(name, "grid", "column", "row", "breakpoint", "media", "screen", "layout", "viewport", "container") -> TokenCategory.LAYOUT
        contains(name, "size", "width", "height", "sizing", "dimension", "scale", "ratio") -> TokenCategory.SIZING
        contains(name, "space", "spacing", "gap", "margin", "padding", "inset", "top", "bottom", "left", "right", "position") -> TokenCategory.SPACING
        
        else -> null
    }

    private fun valueHints(value: String): TokenCategory? = when {
        COLOR_REGEX.matches(value) -> TokenCategory.COLOR
        value.lowercase() in NAMED_COLORS -> TokenCategory.COLOR
        DURATION_REGEX.matches(value) -> TokenCategory.DURATION
        SHADOW_HINT.containsMatchIn(value) && value.contains(",") -> TokenCategory.SHADOW
        LENGTH_REGEX.matches(value) -> TokenCategory.SPACING
        else -> null
    }

    private fun contains(name: String, vararg needles: String): Boolean =
        needles.any { name.contains(it) }
}
