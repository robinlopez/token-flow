package fr.fsh.tokendesigner.ui

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory

/**
 * Renders a token's variants as a vertical, justified list — one row per
 * variant, label on the left, swatch / glyph + resolved value right-aligned.
 *
 *     themeOne · light   ● #001a70
 *     themeOne · dark    ● #ffffff
 *     themeTwo · light   ● #f3f3f3
 *     @media ≥1024       16px
 *
 * Replaces the previous horizontal table which blew up width-wise as soon as
 * a token had more than two or three variants. The vertical layout fits any
 * tooltip box and makes side-by-side comparison easier (one variant per line,
 * aligned at the value column).
 */
object VariantTableHtml {

    /** Build the inner markup (no `<html>` wrapper). */
    fun buildBody(token: DesignToken): String {
        val rows = collectRows(token)
        val themes = rows.mapNotNull { it.theme }.distinct()
        return if (themes.size >= 2) buildGrouped(token, rows) else buildFlat(token, rows)
    }

    /** Same as [buildBody] wrapped in `<html>` for direct use on a JLabel / tooltip. */
    fun build(token: DesignToken): String = "<html>${buildBody(token)}</html>"

    // ─── Row model ────────────────────────────────────────────────────────

    private data class Row(val theme: String?, val sub: String, val value: String)

    private fun collectRows(token: DesignToken): List<Row> {
        val rows = mutableListOf<Row>()
        val (pt, ps) = parseCondition(token.primaryConditionLabel ?: "")
        rows += Row(pt, ps, token.resolvedValue)
        for (v in token.variants) {
            val (t, s) = parseCondition(v.condition)
            rows += Row(t, s, v.value)
        }
        return rows
    }

    /**
     * Splits a condition chain (e.g. `themeOne light`, `:root.dark`,
     * `:root @media (min-width: 1024px)`) into a `(theme, sub)` pair. `sub`
     * becomes the user-facing variant label (`light`, `dark`, `≥1024`,
     * `default`); `theme` is the SCSS-map theme key when one is present.
     */
    private fun parseCondition(condition: String): Pair<String?, String> {
        val s = condition.trim()
        if (s.isBlank() || s.equals("(top level)", ignoreCase = true)) return null to "default"

        Regex("min-width\\s*:\\s*(\\d+)\\s*px").find(s)?.let { return null to "≥${it.groupValues[1]}" }
        Regex("max-width\\s*:\\s*(\\d+)\\s*px").find(s)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return@let
            return null to "<${n + 1}"
        }

        if (s.matches(Regex("[\\w- ]+"))) {
            val parts = s.split(' ').filter { it.isNotBlank() }
            val modeWord = parts.firstOrNull { isModeWord(it) }
            val themeWord = parts.firstOrNull { !isModeWord(it) && !it.equals("default", ignoreCase = true) }
            return when {
                themeWord != null && modeWord != null -> themeWord to modeWord.lowercase()
                modeWord != null -> null to modeWord.lowercase()
                themeWord != null -> themeWord to "default"
                else -> null to (parts.lastOrNull()?.take(24) ?: "default")
            }
        }

        Regex("(?i)(?<![A-Za-z0-9_-])(dark[\\w-]*|light[\\w-]*)(?![A-Za-z0-9_-])")
            .find(s)?.let { return null to it.value.lowercase() }
        val cleaned = s
            .replace(":root", "")
            .replace(Regex("@media\\s*"), "")
            .trim()
            .trim('(', ')')
            .trim()
            .trimStart('.', ':', '&', ' ')
        return null to cleaned.ifBlank { "default" }.take(32)
    }

    private fun isModeWord(s: String): Boolean {
        val l = s.lowercase()
        return l == "light" || l == "dark" || l == "auto"
    }

    // ─── Renderers ────────────────────────────────────────────────────────

    private fun buildFlat(token: DesignToken, rows: List<Row>): String = buildString {
        append(tableOpen())
        for (r in rows) append(rowLine(label = r.sub, value = r.value, token = token))
        append("</table>")
    }

    /**
     * Multi-theme tokens get a subtle theme heading row before each block of
     * mode variants, so a reader can scan `themeOne / light / dark` together
     * before jumping to `themeTwo`. The heading row has no value cell — it's
     * purely an organising label.
     */
    private fun buildGrouped(token: DesignToken, rows: List<Row>): String {
        // Group by first appearance of theme key, preserving order.
        data class Group(val theme: String?, val members: MutableList<Row> = mutableListOf())
        val groups = mutableListOf<Group>()
        for (r in rows) {
            val last = groups.lastOrNull()
            if (last != null && last.theme == r.theme) last.members.add(r)
            else groups.add(Group(r.theme, mutableListOf(r)))
        }
        return buildString {
            append(tableOpen())
            for ((idx, g) in groups.withIndex()) {
                if (idx > 0) append(spacerRow())
                append(themeRow(g.theme ?: "—"))
                for (r in g.members) append(rowLine(label = r.sub, value = r.value, token = token))
            }
            append("</table>")
        }
    }

    // ─── HTML primitives ──────────────────────────────────────────────────

    /**
     * Outer table is rendered without borders or a collapsed grid — the
     * justified layout uses cell alignment + a wide padding gap to keep
     * label / value columns visually separated without drawn lines.
     */
    private fun tableOpen(): String =
        "<table cellspacing='0' cellpadding='0' style='border-collapse:collapse;'>"

    /** Theme heading: small bold caps, dimmed colour, spans both columns. */
    private fun themeRow(theme: String): String =
        "<tr><td colspan='2' style='padding:4px 6px 2px 6px;'>" +
            "<font color='#8a8f95' size='2'><b>${escape(theme.uppercase())}</b></font>" +
            "</td></tr>"

    /** One blank row between theme groups so the eye registers the boundary. */
    private fun spacerRow(): String =
        "<tr><td colspan='2' style='padding:4px 0 0 0;'>&nbsp;</td></tr>"

    /**
     * A justified variant row: left cell holds the variant label, right cell
     * the swatch (colors) or glyph (other categories) + resolved value.
     * Right-aligned with a generous left padding gap acts as the visual
     * separator the user asked for (`name | ----- | value`).
     */
    private fun rowLine(label: String, value: String, token: DesignToken): String {
        val isColor = token.category == TokenCategory.COLOR
        return "<tr>" +
            "<td align='left' valign='middle' style='padding:3px 24px 3px 6px;'>" +
            "<font color='#c9cdd2'>${escape(label)}</font>" +
            "</td>" +
            "<td align='right' valign='middle' style='padding:3px 6px 3px 0;white-space:nowrap;'>" +
            renderValue(value, isColor, token.category) +
            "</td>" +
            "</tr>"
    }

    private fun renderValue(value: String, isColor: Boolean, category: TokenCategory?): String {
        if (isColor) {
            val color = ColorParser.parse(value)
            if (color != null) {
                val hex = "#%02X%02X%02X".format(color.red, color.green, color.blue)
                return "<font color='$hex' size='5'>&#9679;</font>" +
                    "&nbsp;<font face='monospace' color='#dddddd'>${escape(hex)}</font>"
            }
            return "<font face='monospace' color='#dddddd'>${escape(value)}</font>"
        }
        // Non-colour: small category glyph (if any) followed by the value.
        val glyph = CategoryGlyphs.glyphFor(category)
        val glyphHtml = if (glyph.isNullOrBlank()) "" else "<font color='#888d93'>${escape(glyph)}</font>&nbsp;"
        return glyphHtml + "<font face='monospace' color='#dddddd'>${escape(value)}</font>"
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
