package fr.fsh.tokendesigner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

/**
 * Locks the colour notation conversions used by the copy-value dropdown
 * (issue #27). The OKLCH numbers come from Björn Ottosson's reference
 * sRGB → OKLab transform; we assert with a tolerance rather than exact strings.
 */
class ColorConversionsTest {

    @Test fun hexOpaqueAndAlpha() {
        assertEquals("#e5e9eb", ColorConversions.toHex(Color(0xE5, 0xE9, 0xEB)))
        assertEquals("#ff000080", ColorConversions.toHex(Color(255, 0, 0, 128)))
    }

    @Test fun rgbOpaqueAndAlpha() {
        assertEquals("rgb(229, 233, 235)", ColorConversions.toRgb(Color(0xE5, 0xE9, 0xEB)))
        assertEquals("rgba(255, 0, 0, 0.5)", ColorConversions.toRgb(Color(255, 0, 0, 128)))
    }

    @Test fun hslPrimaryColors() {
        assertEquals("hsl(0deg 100% 50%)", ColorConversions.toHsl(Color(255, 0, 0)))
        assertEquals("hsl(120deg 100% 50%)", ColorConversions.toHsl(Color(0, 255, 0)))
        assertEquals("hsl(0deg 0% 100%)", ColorConversions.toHsl(Color.WHITE))
    }

    /** Decimal separator must be '.', regardless of the default locale. */
    @Test fun usesDotDecimalSeparator() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            val oklch = ColorConversions.toOklch(Color(0x33, 0x66, 0xCC))
            assertTrue("expected '.' separator, got: $oklch", oklch.contains("."))
            assertTrue("must not contain a locale comma: $oklch", !oklch.contains(","))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test fun oklchWhiteIsLightnessOne() {
        // White → L≈1, C≈0.
        val s = ColorConversions.toOklch(Color.WHITE)
        assertTrue("white should report L=1: $s", s.startsWith("oklch(1 "))
    }

    @Test fun detectsSourceFormat() {
        assertEquals(ColorConversions.Format.HEX, ColorConversions.detect("#e5e9eb"))
        assertEquals(ColorConversions.Format.RGB, ColorConversions.detect("rgb(1,2,3)"))
        assertEquals(ColorConversions.Format.HSL, ColorConversions.detect("hsl(0deg 0% 0%)"))
        assertEquals(ColorConversions.Format.OKLCH, ColorConversions.detect("oklch(0.5 0.1 120)"))
        assertNull(ColorConversions.detect("16px"))
        assertNull(ColorConversions.detect("white"))
    }
}
