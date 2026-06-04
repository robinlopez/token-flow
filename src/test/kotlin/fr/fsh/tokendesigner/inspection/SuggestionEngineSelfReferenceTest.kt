package fr.fsh.tokendesigner.inspection

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.model.TokenKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for issue #23 — the suggestion engine must never offer the token
 * being defined as the replacement for its own literal (a `var(--x): var(--x)`
 * self-reference loop).
 */
class SuggestionEngineSelfReferenceTest {

    private fun colorToken(name: String, value: String, kind: TokenKind) =
        DesignToken(
            name = name,
            rawValue = value,
            resolvedValue = value,
            category = TokenCategory.COLOR,
            kind = kind,
            filePath = "/tokens.css",
            offset = 0,
        )

    private fun colorHit(value: String, declarationName: String?) =
        LiteralFinder.Hit(
            text = value,
            startOffset = 0,
            endOffsetExclusive = value.length,
            kind = LiteralFinder.Kind.COLOR,
            isDeclaration = declarationName != null,
            declarationName = declarationName,
        )

    @Test
    fun `definition does not suggest its own CSS variable`() {
        // --color-bg-page: #e5e9eb;  → must NOT offer var(--color-bg-page)
        val self = colorToken("--color-bg-page", "#e5e9eb", TokenKind.CSS_CUSTOM_PROPERTY)
        val index = TokenValueIndex(listOf(self))

        val suggestions = SuggestionEngine.findSuggestions(
            colorHit("#e5e9eb", declarationName = "--color-bg-page"),
            index,
            listOf(self),
            TokenCategory.COLOR,
        )

        assertTrue(
            "engine suggested the token being defined: ${suggestions.map { it.token.name }}",
            suggestions.none { it.token.name == "--color-bg-page" },
        )
    }

    @Test
    fun `definition still suggests a different token with the same value`() {
        // Another token holds the same colour → that one is a valid suggestion.
        val self = colorToken("--color-bg-page", "#e5e9eb", TokenKind.CSS_CUSTOM_PROPERTY)
        val sibling = colorToken("--surface-muted", "#e5e9eb", TokenKind.CSS_CUSTOM_PROPERTY)
        val index = TokenValueIndex(listOf(self, sibling))

        val suggestions = SuggestionEngine.findSuggestions(
            colorHit("#e5e9eb", declarationName = "--color-bg-page"),
            index,
            listOf(self, sibling),
            TokenCategory.COLOR,
        )

        assertTrue(suggestions.any { it.token.name == "--surface-muted" })
        assertFalse(suggestions.any { it.token.name == "--color-bg-page" })
    }

    @Test
    fun `non-declaration usage still suggests the matching token`() {
        // background: #e5e9eb;  (no declaration name) → var(--color-bg-page) is fine.
        val token = colorToken("--color-bg-page", "#e5e9eb", TokenKind.CSS_CUSTOM_PROPERTY)
        val index = TokenValueIndex(listOf(token))

        val suggestions = SuggestionEngine.findSuggestions(
            colorHit("#e5e9eb", declarationName = null),
            index,
            listOf(token),
            TokenCategory.COLOR,
        )

        assertTrue(suggestions.any { it.token.name == "--color-bg-page" })
    }

    @Test
    fun `SCSS self-definition is excluded`() {
        val self = colorToken("\$color-bg-page", "#e5e9eb", TokenKind.SCSS_VARIABLE)
        val index = TokenValueIndex(listOf(self))

        val suggestions = SuggestionEngine.findSuggestions(
            colorHit("#e5e9eb", declarationName = "\$color-bg-page"),
            index,
            listOf(self),
            TokenCategory.COLOR,
        )

        assertTrue(suggestions.none { it.token.name == "\$color-bg-page" })
    }

    @Test
    fun `JS object path self-definition is excluded by trailing key`() {
        // colors.bg: '#e5e9eb' — declaration walker reports the bare leaf `bg`.
        val self = DesignToken(
            name = "colors.bg",
            rawValue = "#e5e9eb",
            resolvedValue = "#e5e9eb",
            category = TokenCategory.COLOR,
            kind = TokenKind.JS_RUNTIME_PROPERTY,
            filePath = "/theme.ts",
            offset = 0,
        )
        val index = TokenValueIndex(listOf(self))

        val suggestions = SuggestionEngine.findSuggestions(
            colorHit("#e5e9eb", declarationName = "bg"),
            index,
            listOf(self),
            TokenCategory.COLOR,
        )

        assertTrue(suggestions.none { it.token.name == "colors.bg" })
    }
}
