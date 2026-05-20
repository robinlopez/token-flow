package fr.fsh.tokendesigner.model

/**
 * Sub-classification of a token's semantic *purpose* inside a category.
 *
 * Where [TokenCategory] answers "what kind of value is this?" (color, length,
 * shadow…), [TokenRole] answers "what does the design system use it for?".
 * Used by the suggestion engine to disambiguate same-value tokens — e.g.
 * `background: #005bff` should prefer a token containing `-surface-` in its
 * name, while `color: #005bff` should prefer one containing `-content-`.
 *
 * `null` means the role couldn't be inferred from the token name. Roles are
 * intentionally narrow: only common DS conventions are listed.
 */
enum class TokenRole {
    /** Background / fill / canvas surfaces. */
    SURFACE,
    /** Foreground text & iconography. */
    CONTENT,
    /** Borders, outlines, dividers. */
    STROKE,
    /** Effects: shadow, focus ring, glow. */
    EFFECT,
}
