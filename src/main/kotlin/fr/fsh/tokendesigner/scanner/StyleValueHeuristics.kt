package fr.fsh.tokendesigner.scanner

/**
 * Value-shape heuristics that tell a *design-token tree* apart from a plain
 * application/config object.
 *
 * The TS/JS indexer is structural — it walks any `export const X = { … }` and
 * records every `path → value` leaf. That alone cannot distinguish a token
 * dictionary from arbitrary application data: a JSON-Schema definition, an
 * event-name enum (`{ CREATED: 'ENTITY_CREATED' }`) or a Storybook `args`
 * object are all *shaped* like token files.
 *
 * The discriminator used here is the **value**, not the shape: a design token,
 * by definition, holds a *style primitive* — a colour, a dimension, an alias
 * reference, or a bare number (unitless spacing / font-weight / line-height).
 * An object whose values are predominantly arbitrary strings
 * (`'ENTITY_CREATED'`, `'string'`, `'#/$defs/LayoutRow'`) is almost certainly
 * not a token dictionary and is dropped wholesale.
 *
 * Deliberately conservative: an object is kept whenever it carries a real
 * style signal, so false-negatives (dropping a genuine token object) are
 * limited to the rare pure-prose or single-font-family object.
 */
object StyleValueHeuristics {

    enum class ValueClass { STYLE, NUMERIC, NON_STYLE, EMPTY }

    /** Style-Dictionary alias literal: `{primitive.primary.500}`. */
    private val ALIAS_STYLE_DICT = Regex("^\\{[A-Za-z_][\\w.\\-]*}$")

    /** Bare runtime property-access alias: `colors.PRIMARY_500` (≥ 1 dot). */
    private val RUNTIME_REF = Regex("^[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$0-9][\\w$]*)+$")

    /** Pure number, optionally signed / decimal: `8`, `-2`, `1.5`, `.25`. */
    private val PURE_NUMBER = Regex("^-?\\d*\\.?\\d+$")

    /** Hex colour `#abc` … `#aabbcc88`, *not* preceded/followed by more hex. */
    private val HEX_COLOR = Regex("(?<![0-9a-fA-F#])#[0-9a-fA-F]{3,8}(?![0-9a-fA-F])")

    /** Colour functions: `rgb(`, `rgba(`, `hsl(`, `hsla(`. */
    private val COLOR_FN = Regex("(?i)\\b(?:rgba?|hsla?)\\s*\\(")

    /** A number immediately followed by a CSS length / time / angle unit, or `%`. */
    private val DIMENSION = Regex(
        "(?i)(?<![\\w.])\\d*\\.?\\d+" +
            "(?:px|rem|em|vh|vw|vmin|vmax|vb|vi|pt|pc|cm|mm|in|ex|ch|fr|deg|rad|grad|turn|ms|s|dpi|dpcm|dppx)(?![\\w])" +
            "|(?<![\\w.])\\d*\\.?\\d+%"
    )

    /** Style-producing CSS functions (shadows, gradients, calc, transforms…). */
    private val STYLE_FN = Regex(
        "(?i)\\b(?:var|calc|min|max|clamp|env|url|" +
            "linear-gradient|radial-gradient|conic-gradient|repeating-linear-gradient|" +
            "translate[xyz3d]*|rotate[xyz]?|scale[xyz]?|skew[xy]?|matrix|perspective|cubic-bezier)\\s*\\("
    )

    /**
     * Single-word values that are legitimate style keywords (font weights,
     * border styles, text transforms, common globals). Kept intentionally
     * narrow — layout words like `center`/`row`/`block` are *excluded* because
     * they show up just as often in application config and would let
     * non-token objects slip through. Their presence lets a numeric typography
     * object (`{ fontSize: 16, fontWeight: 'bold' }`) survive.
     */
    private val STYLE_KEYWORDS = setOf(
        "bold", "bolder", "lighter", "normal", "italic", "oblique",
        "thin", "light", "regular", "medium", "semibold", "semi-bold", "heavy", "black",
        "solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset",
        "none", "auto", "transparent", "currentcolor",
        "inherit", "initial", "unset",
        "uppercase", "lowercase", "capitalize",
        "ease", "ease-in", "ease-out", "ease-in-out", "linear",
    )

    /** Classifies a single leaf value (quotes already stripped by the parser). */
    fun classify(raw: String): ValueClass {
        val v = raw.trim()
        if (v.isEmpty()) return ValueClass.EMPTY
        if (ALIAS_STYLE_DICT.matches(v) || RUNTIME_REF.matches(v)) return ValueClass.STYLE
        if (PURE_NUMBER.matches(v)) return ValueClass.NUMERIC
        if (HEX_COLOR.containsMatchIn(v) ||
            COLOR_FN.containsMatchIn(v) ||
            DIMENSION.containsMatchIn(v) ||
            STYLE_FN.containsMatchIn(v)
        ) {
            return ValueClass.STYLE
        }
        if (v.lowercase() in STYLE_KEYWORDS) return ValueClass.STYLE
        return ValueClass.NON_STYLE
    }

    /**
     * Decides whether the leaf [values] of one exported object look like a
     * design-token dictionary.
     *
     *  - Has at least one genuine style value → keep, *provided* style values
     *    are not heavily outnumbered by arbitrary strings (`style >= nonStyle`).
     *    This keeps colour/dimension/alias objects (with the odd stray label)
     *    while rejecting a config object that merely contains one stray colour.
     *  - No style value, but purely numeric (no arbitrary strings) → keep:
     *    unitless spacing / weight / line-height scales are legitimate.
     *  - Otherwise (arbitrary-string-dominated, e.g. an event-name enum or a
     *    JSON-Schema body) → drop.
     */
    fun looksLikeTokenObject(values: List<String>): Boolean {
        var style = 0
        var numeric = 0
        var nonStyle = 0
        for (raw in values) {
            when (classify(raw)) {
                ValueClass.STYLE -> style++
                ValueClass.NUMERIC -> numeric++
                ValueClass.NON_STYLE -> nonStyle++
                ValueClass.EMPTY -> {}
            }
        }
        if (style + numeric + nonStyle == 0) return false
        return if (style > 0) style >= nonStyle else (nonStyle == 0 && numeric > 0)
    }
}
