package fr.fsh.tokendesigner.ui

import fr.fsh.tokendesigner.model.TokenCategory

/** Single source of truth for the small glyph shown alongside non-colour tokens. */
object CategoryGlyphs {

    fun glyphFor(category: TokenCategory?): String? = when (category) {
        TokenCategory.COLOR -> null            // a real swatch is shown instead
        TokenCategory.SPACING -> "↔"
        TokenCategory.RADIUS -> "◖"
        TokenCategory.SHADOW -> "▣"
        TokenCategory.TYPOGRAPHY -> "T"
        TokenCategory.DURATION -> "⏱"
        TokenCategory.Z_INDEX -> "≡"
        TokenCategory.EFFECTS -> "✨"
        TokenCategory.LAYOUT -> "⊞"
        TokenCategory.SIZING -> "⤢"
        TokenCategory.BORDER -> "◨"
        TokenCategory.OPACITY -> "◐"
        TokenCategory.ICON -> "★"
        TokenCategory.OTHER -> "·"
        null -> "?"
    }
}
