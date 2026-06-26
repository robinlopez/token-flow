package fr.fsh.tokendesigner.ui

import java.awt.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Stringifies a [java.awt.Color] back into the CSS color notations the plugin
 * cares about, and detects which notation a raw token value is already written
 * in. Used by the "copy token value" dropdown (issue #27) to offer the *other*
 * representations of a colour: when the resolved value is `#e5e9eb`, the popup
 * proposes its `rgb()`, `hsl()` and `oklch()` equivalents.
 *
 * [ColorParser] does the inverse direction (string → Color); keep the two in
 * sync if a new notation is added.
 */
object ColorConversions {

    enum class Format { HEX, RGB, HSL, OKLCH }

    /**
     * Best-effort guess of the notation a raw value is written in, so the popup
     * can skip offering the format the user already has in front of them.
     * Named colours (`white`, …) report [Format.HEX] since their canonical
     * copyable form is a hex literal.
     */
    fun detect(raw: String): Format? {
        val v = raw.trim().lowercase()
        return when {
            v.startsWith("#") -> Format.HEX
            v.startsWith("oklch") -> Format.OKLCH
            v.startsWith("hsl") -> Format.HSL
            v.startsWith("rgb") -> Format.RGB
            else -> null
        }
    }

    /** `#rrggbb`, or `#rrggbbaa` when the colour is not fully opaque. */
    fun toHex(c: Color): String {
        val base = "#%02x%02x%02x".format(c.red, c.green, c.blue)
        return if (c.alpha == 255) base else base + "%02x".format(c.alpha)
    }

    /** `rgb(r, g, b)` or `rgba(r, g, b, a)` with alpha as a 0–1 float. */
    fun toRgb(c: Color): String =
        if (c.alpha == 255) "rgb(${c.red}, ${c.green}, ${c.blue})"
        else "rgba(${c.red}, ${c.green}, ${c.blue}, ${alpha(c)})"

    /** `hsl(Hdeg S% L%)` or `hsla(…)` when translucent. */
    fun toHsl(c: Color): String {
        val r = c.red / 255.0
        val g = c.green / 255.0
        val b = c.blue / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2.0
        var h = 0.0
        var s = 0.0
        if (max != min) {
            val d = max - min
            s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
            h = when (max) {
                r -> (g - b) / d + if (g < b) 6.0 else 0.0
                g -> (b - r) / d + 2.0
                else -> (r - g) / d + 4.0
            } * 60.0
        }
        val hh = h.roundToInt()
        val ss = (s * 100).roundToInt()
        val ll = (l * 100).roundToInt()
        return if (c.alpha == 255) "hsl(${hh}deg ${ss}% ${ll}%)"
        else "hsla(${hh}deg ${ss}% ${ll}% / ${alpha(c)})"
    }

    /**
     * `oklch(L C Hdeg)` using Björn Ottosson's sRGB → OKLab transform. L is the
     * 0–1 lightness, C the chroma, H the hue in degrees. Alpha appended as
     * `/ a` when the colour is translucent.
     */
    fun toOklch(c: Color): String {
        val r = linearize(c.red / 255.0)
        val g = linearize(c.green / 255.0)
        val b = linearize(c.blue / 255.0)

        val lMs = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val mMs = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val sMs = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b

        val l_ = cbrt(lMs)
        val m_ = cbrt(mMs)
        val s_ = cbrt(sMs)

        val okL = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
        val okA = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
        val okB = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_

        val chroma = sqrt(okA * okA + okB * okB)
        var hue = Math.toDegrees(atan2(okB, okA))
        if (hue < 0) hue += 360.0

        val ls = trim3(okL)
        val cs = trim3(chroma)
        val hs = trim1(hue)
        return if (c.alpha == 255) "oklch($ls $cs ${hs}deg)"
        else "oklch($ls $cs ${hs}deg / ${alpha(c)})"
    }

    private fun linearize(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun alpha(c: Color): String = trim2(c.alpha / 255.0)

    // Locale.ROOT so the decimal separator is always '.', never a locale comma
    // (which would corrupt the CSS notation on e.g. a French IDE).
    private fun trim1(v: Double): String = stripZeros(String.format(java.util.Locale.ROOT, "%.1f", v))
    private fun trim2(v: Double): String = stripZeros(String.format(java.util.Locale.ROOT, "%.2f", v))
    private fun trim3(v: Double): String = stripZeros(String.format(java.util.Locale.ROOT, "%.3f", v))

    private fun stripZeros(s: String): String =
        if ('.' in s) s.trimEnd('0').trimEnd('.') else s
}
