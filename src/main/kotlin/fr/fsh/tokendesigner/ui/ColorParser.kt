package fr.fsh.tokendesigner.ui

import java.awt.Color

object ColorParser {

    private val HEX = Regex("^#([0-9a-fA-F]{3,8})$")
    private val RGB = Regex(
        "^rgba?\\(\\s*(\\d+)\\s*[, ]\\s*(\\d+)\\s*[, ]\\s*(\\d+)\\s*(?:[,/]\\s*([0-9.]+%?))?\\s*\\)$"
    )
    private val HSL = Regex(
        "^hsla?\\(\\s*([0-9.]+)(?:deg)?\\s*[, ]\\s*([0-9.]+)%\\s*[, ]\\s*([0-9.]+)%\\s*(?:[,/]\\s*([0-9.]+%?))?\\s*\\)$"
    )

    private val NAMED = mapOf(
        "transparent" to Color(0, 0, 0, 0),
        "black" to Color.BLACK,
        "white" to Color.WHITE,
        "red" to Color.RED,
        "green" to Color.GREEN,
        "blue" to Color.BLUE,
        "yellow" to Color.YELLOW,
        "orange" to Color.ORANGE,
        "pink" to Color.PINK,
        "gray" to Color.GRAY,
        "grey" to Color.GRAY,
    )

    fun parse(value: String): Color? {
        val v = value.trim().lowercase()
        NAMED[v]?.let { return it }

        HEX.matchEntire(v)?.let { return parseHex(it.groupValues[1]) }
        RGB.matchEntire(v)?.let { return parseRgb(it.groupValues) }
        HSL.matchEntire(v)?.let { return parseHsl(it.groupValues) }
        return null
    }

    private fun parseHex(hex: String): Color? = when (hex.length) {
        3 -> Color(
            hex[0].digitToInt(16) * 17,
            hex[1].digitToInt(16) * 17,
            hex[2].digitToInt(16) * 17,
        )
        4 -> Color(
            hex[0].digitToInt(16) * 17,
            hex[1].digitToInt(16) * 17,
            hex[2].digitToInt(16) * 17,
            hex[3].digitToInt(16) * 17,
        )
        6 -> Color(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
        )
        8 -> Color(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
            hex.substring(6, 8).toInt(16),
        )
        else -> null
    }

    private fun parseRgb(g: List<String>): Color? = try {
        val r = g[1].toInt().coerceIn(0, 255)
        val gr = g[2].toInt().coerceIn(0, 255)
        val b = g[3].toInt().coerceIn(0, 255)
        val a = parseAlpha(g.getOrNull(4))
        Color(r, gr, b, a)
    } catch (_: NumberFormatException) { null }

    private fun parseHsl(g: List<String>): Color? = try {
        val h = g[1].toFloat() / 360f
        val s = g[2].toFloat() / 100f
        val l = g[3].toFloat() / 100f
        val rgb = hslToRgb(h, s, l)
        val a = parseAlpha(g.getOrNull(4))
        Color(rgb[0], rgb[1], rgb[2], a)
    } catch (_: NumberFormatException) { null }

    private fun parseAlpha(raw: String?): Int {
        if (raw.isNullOrEmpty()) return 255
        val v = if (raw.endsWith("%")) raw.dropLast(1).toFloat() / 100f else raw.toFloat()
        return (v.coerceIn(0f, 1f) * 255).toInt()
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
        if (s == 0f) {
            val v = (l * 255).toInt()
            return intArrayOf(v, v, v)
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        return intArrayOf(
            (hueToRgb(p, q, h + 1f / 3f) * 255).toInt(),
            (hueToRgb(p, q, h) * 255).toInt(),
            (hueToRgb(p, q, h - 1f / 3f) * 255).toInt(),
        )
    }

    private fun hueToRgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0) t += 1
        if (t > 1) t -= 1
        return when {
            t < 1f / 6f -> p + (q - p) * 6 * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6
            else -> p
        }
    }
}
