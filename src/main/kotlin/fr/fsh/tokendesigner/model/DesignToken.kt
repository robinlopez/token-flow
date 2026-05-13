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
    /**
     * For [TokenKind.JS_RUNTIME_FUNCTION] callable helpers: the numeric unit
     * in the linear formula `unit × value`. Drives helper-aware suggestions in
     * [fr.fsh.tokendesigner.inspection.SuggestionEngine] — a hardcoded `12px`
     * yields `spacing(1.5)` when an indexed helper has `functionUnit = 8`.
     * `null` for anything that isn't a linear single-argument helper.
     */
    val functionUnit: Double? = null,
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
    /**
     * Style-Dictionary / PrimeUIX-style aliasable preset path, e.g.
     * `global.modeLight.surface.default`. Referenced in code as the literal
     * string `'{global.modeLight.surface.default}'` or `dt('…')`.
     */
    JS_OBJECT_PATH,
    /**
     * Runtime / object-based access path, e.g. `colors.PRIMARY_500` or
     * `nomTheme.radius.sm`. Used by React-Native-style theme files where the
     * declaration is a typed `const` object accessed by property at runtime
     * (no alias indirection, no string wrapping). The token name is the
     * complete JS property-access expression and is inserted verbatim.
     */
    JS_RUNTIME_PROPERTY,
    /**
     * Callable runtime helper, e.g. `spacing(value)` or `normalize(size, ref)`.
     * The token name is *either* the bare helper identifier (library entry,
     * used for display) or a fully applied call expression like `spacing(1.5)`
     * (synthetic, used for hardcoded-value suggestions and inserted verbatim).
     */
    JS_RUNTIME_FUNCTION,
}

/**
 * Centralised mapping from a [DesignToken] to the source-code expression that
 * references it. Keep this in sync whenever a new [TokenKind] is added — every
 * call site that builds a replacement string should go through here.
 */
object TokenReference {
    fun expression(token: DesignToken): String = expression(token.name, token.kind)

    fun expression(name: String, kind: TokenKind): String = when (kind) {
        TokenKind.SCSS_VARIABLE -> name                  // already includes `$`
        TokenKind.CSS_CUSTOM_PROPERTY -> "var($name)"    // wraps `--name`
        TokenKind.JS_OBJECT_PATH -> "'{$name}'"          // Style-Dictionary alias
        TokenKind.JS_RUNTIME_PROPERTY -> name            // bare property access
        TokenKind.JS_RUNTIME_FUNCTION -> name            // helper or fully-applied call
    }
}
