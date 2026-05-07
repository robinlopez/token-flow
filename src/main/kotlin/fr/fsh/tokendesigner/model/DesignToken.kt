package fr.fsh.tokendesigner.model

data class DesignToken(
    val name: String,
    val rawValue: String,
    val resolvedValue: String,
    val category: TokenCategory,
    val kind: TokenKind,
    val filePath: String,
    val offset: Int,
    /**
     * Additional declarations of the same token name found in different contexts
     * (other `@media` queries, theme classes, etc.). The primary value lives on
     * [resolvedValue]; everything else surfaces here so the dashboard can show
     * "+N variants" with their conditions.
     */
    val variants: List<TokenVariant> = emptyList(),
    /**
     * Optional override for the "default" column header in the variant table.
     * Used by JS preset tokens to surface the *mode* of the first occurrence
     * (e.g. `light`) instead of a generic `default`, so light/dark side-by-side
     * comparisons read naturally.
     */
    val primaryConditionLabel: String? = null,
) {
    val displayValue: String get() = if (rawValue == resolvedValue) rawValue else "$rawValue → $resolvedValue"
}

data class TokenVariant(
    /** Human-readable context, e.g. `@media (min-width: 1024px)`, `.dark-mode`. */
    val condition: String,
    val value: String,
)

enum class TokenKind {
    SCSS_VARIABLE,        // $name (SCSS files)
    CSS_CUSTOM_PROPERTY,  // --name (CSS / SCSS source / SCSS map keys)
    JS_OBJECT_PATH,       // path.in.object  (TS/JS preset / theme files)
}
