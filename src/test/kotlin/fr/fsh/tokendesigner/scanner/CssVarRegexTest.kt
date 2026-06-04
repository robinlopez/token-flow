package fr.fsh.tokendesigner.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for issue #25 — BEM modifier syntax (`.block__elem--mod:hover`)
 * must not be mistaken for a CSS custom-property declaration.
 *
 * The regex lives inside [fr.fsh.tokendesigner.scanner.TokenScanner]'s
 * companion object as `private`, so we mirror it here exactly. If the
 * production pattern changes, this constant has to track it — the tests below
 * exist precisely to lock the BEM-safe behaviour in place.
 */
class CssVarRegexTest {

    private val CSS_VAR_REGEX = Regex(
        "(?<![A-Za-z0-9_&-])--([A-Za-z_][A-Za-z0-9_-]*)\\s*:\\s*([^;}\\n]+)\\s*;?"
    )

    /** Mirrors the production loop guard: a real value never contains `{`. */
    private fun declaredNames(src: String): List<String> =
        CSS_VAR_REGEX.findAll(src)
            .filter { '{' !in it.groupValues[2] }
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `BEM modifier in a selector is not captured`() {
        val src = """
            .card__slot--closeable:hover .card__close-btn {
              opacity: 1;
            }
        """.trimIndent()
        val names = CSS_VAR_REGEX.findAll(src).map { it.groupValues[1] }.toList()
        assertTrue("BEM modifier leaked as a CSS var: $names", names.isEmpty())
    }

    @Test
    fun `nested BEM modifier in SCSS parent ref is not captured`() {
        val src = """
            .card {
              &__slot--closeable:hover &__close-btn {
                opacity: 1;
              }
            }
        """.trimIndent()
        assertTrue(CSS_VAR_REGEX.findAll(src).none())
    }

    @Test
    fun `legitimate top-level CSS custom property is still captured`() {
        val src = ":root { --brand-primary: #FE5716; }"
        val match = CSS_VAR_REGEX.find(src)
        assertEquals("brand-primary", match?.groupValues?.get(1))
        assertEquals("#FE5716", match?.groupValues?.get(2)?.trim())
    }

    @Test
    fun `custom property declared inside a rule body is captured`() {
        val src = """
            .button {
              --button-bg: var(--brand-primary);
              padding: 8px;
            }
        """.trimIndent()
        val names = CSS_VAR_REGEX.findAll(src).map { it.groupValues[1] }.toList()
        assertEquals(listOf("button-bg"), names)
    }

    @Test
    fun `multiple BEM modifiers in one selector chain are all ignored`() {
        val src = ".btn--primary--large:hover .icon--inverted { color: red; }"
        assertTrue(CSS_VAR_REGEX.findAll(src).none())
    }

    @Test
    fun `mixed file with BEM selector and real custom property keeps only the property`() {
        val src = """
            :root { --space-md: 12px; }
            .card__slot--closeable:hover { opacity: 1; }
        """.trimIndent()
        val names = CSS_VAR_REGEX.findAll(src).map { it.groupValues[1] }.toList()
        assertEquals(listOf("space-md"), names)
    }

    @Test
    fun `custom property declared at line start with no leading whitespace is captured`() {
        // Edge case: lookbehind anchored to nothing (start of input) must succeed.
        val match = CSS_VAR_REGEX.find("--foo: red;")
        assertEquals("foo", match?.groupValues?.get(1))
    }

    @Test
    fun `pseudo-class on a BEM modifier never declares a variable`() {
        val src = ".btn--disabled:focus-visible { outline: 2px solid #000; }"
        assertFalse(CSS_VAR_REGEX.containsMatchIn(src))
    }

    // ─── Issue #25 follow-up: SCSS parent-ref (`&`) BEM modifiers ──────────

    @Test
    fun `SCSS parent-ref BEM modifier is not captured`() {
        val src = """
            &--selected:not(.is-zone-highlighted) .wi-breakdown__fill {
              box-shadow: 0 0 6px 0 var(--wi-fill-color, var(--color-primary));
            }
        """.trimIndent()
        assertTrue("`&--selected` leaked: ${declaredNames(src)}", declaredNames(src).isEmpty())
    }

    @Test
    fun `var() references inside a value are not treated as declarations`() {
        // The box-shadow value references two real vars but declares none.
        val src = "box-shadow: 0 0 6px 0 var(--wi-fill-color, var(--color-primary));"
        assertTrue(declaredNames(src).isEmpty())
    }

    @Test
    fun `parent-ref modifier followed by brace on same line is guarded by the brace check`() {
        // Even if the lookbehind ever regressed, the `{` guard catches it.
        val src = "&--active:hover { color: red; }"
        assertTrue(declaredNames(src).isEmpty())
    }

    @Test
    fun `real custom property still captured next to a parent-ref modifier`() {
        val src = """
            .wi-breakdown {
              --wi-fill-color: #4563a0;
              &--selected:not(.is-zone-highlighted) { opacity: 1; }
            }
        """.trimIndent()
        assertEquals(listOf("wi-fill-color"), declaredNames(src))
    }
}
