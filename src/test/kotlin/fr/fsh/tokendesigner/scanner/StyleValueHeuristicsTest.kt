package fr.fsh.tokendesigner.scanner

import fr.fsh.tokendesigner.scanner.StyleValueHeuristics.ValueClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleValueHeuristicsTest {

    @Test
    fun `colours classify as style`() {
        for (v in listOf("#fff", "#FE5716", "#aabbcc88", "rgb(0,0,0)", "rgba(0,0,0,.5)", "hsl(12, 80%, 55%)")) {
            assertEquals(v, ValueClass.STYLE, StyleValueHeuristics.classify(v))
        }
    }

    @Test
    fun `dimensions classify as style`() {
        for (v in listOf("12px", "1rem", "0.5em", "100%", "1.5vh", "200ms", "0.3s", "90deg")) {
            assertEquals(v, ValueClass.STYLE, StyleValueHeuristics.classify(v))
        }
    }

    @Test
    fun `aliases classify as style`() {
        assertEquals(ValueClass.STYLE, StyleValueHeuristics.classify("{primitive.primary.500}"))
        assertEquals(ValueClass.STYLE, StyleValueHeuristics.classify("colors.PRIMARY_500"))
        assertEquals(ValueClass.STYLE, StyleValueHeuristics.classify("var(--brand)"))
    }

    @Test
    fun `compound style values classify as style`() {
        assertEquals(ValueClass.STYLE, StyleValueHeuristics.classify("0 1px 2px rgba(0,0,0,0.1)"))
        assertEquals(ValueClass.STYLE, StyleValueHeuristics.classify("1px solid #ccc"))
        assertEquals(ValueClass.STYLE, StyleValueHeuristics.classify("linear-gradient(90deg, #fff, #000)"))
    }

    @Test
    fun `style keywords classify as style`() {
        for (v in listOf("bold", "italic", "dashed", "uppercase", "none", "transparent")) {
            assertEquals(v, ValueClass.STYLE, StyleValueHeuristics.classify(v))
        }
    }

    @Test
    fun `bare numbers classify as numeric`() {
        for (v in listOf("0", "8", "16", "-2", "1.5", ".25")) {
            assertEquals(v, ValueClass.NUMERIC, StyleValueHeuristics.classify(v))
        }
    }

    @Test
    fun `arbitrary application strings classify as non-style`() {
        for (v in listOf(
            "ENTITY_CREATED", "STATS_REFRESH", "string", "object", "array",
            "danger", "INDICATOR", "#/\$defs/LayoutRow",
            "http://json-schema.org/draft-07/schema#", "^#[0-9a-fA-F]{3,8}\$",
        )) {
            assertEquals(v, ValueClass.NON_STYLE, StyleValueHeuristics.classify(v))
        }
    }

    @Test
    fun `event-name enum is rejected`() {
        // export const ENTITY_EVENTS = { CREATED: 'ENTITY_CREATED', … }
        val values = listOf("ENTITY_CREATED", "ENTITY_UPDATED", "ENTITY_DELETED", "ENTITY_BLOCKED", "ENTITY_STATUS_CHANGED")
        assertFalse(StyleValueHeuristics.looksLikeTokenObject(values))
    }

    @Test
    fun `json-schema body is rejected`() {
        // A sample of the leaf values produced by walking a JSON-Schema object.
        val values = listOf("string", "object", "array", "0", "1", "4", "string", "boolean", "danger", "info")
        assertFalse(StyleValueHeuristics.looksLikeTokenObject(values))
    }

    @Test
    fun `colour palette is kept`() {
        val values = listOf("#FE5716", "#1a1a1a", "#ffffff", "rgba(0,0,0,0.5)")
        assertTrue(StyleValueHeuristics.looksLikeTokenObject(values))
    }

    @Test
    fun `unitless numeric scale is kept`() {
        // e.g. spacing multipliers / font weights with no unit.
        assertTrue(StyleValueHeuristics.looksLikeTokenObject(listOf("4", "8", "12", "16", "24")))
    }

    @Test
    fun `numeric typography object with weight keyword is kept`() {
        // { fontSize: 16, fontWeight: 'bold', lineHeight: 1.5 }
        assertTrue(StyleValueHeuristics.looksLikeTokenObject(listOf("16", "bold", "1.5")))
    }

    @Test
    fun `palette with a stray label is kept`() {
        assertTrue(StyleValueHeuristics.looksLikeTokenObject(listOf("#fff", "#000", "#ccc", "Primary")))
    }

    @Test
    fun `config object with a single stray colour is rejected`() {
        // One colour drowned in arbitrary strings → not a token dictionary.
        assertFalse(StyleValueHeuristics.looksLikeTokenObject(listOf("#fff", "GET", "POST", "DELETE", "PATCH")))
    }

    @Test
    fun `empty object is rejected`() {
        assertFalse(StyleValueHeuristics.looksLikeTokenObject(emptyList()))
    }
}
