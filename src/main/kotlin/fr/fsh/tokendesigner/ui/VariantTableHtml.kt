package fr.fsh.tokendesigner.ui

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory

/**
 * Renders a token's variants as an HTML table — one column per condition
 * (mode/theme/breakpoint), one cell per resolved value. Used both as the
 * tooltip on the Library list and as the body of the hover info popup.
 *
 * For COLOR tokens each cell shows a swatch + hex code; for everything else
 * the cell shows the raw value (px, rem, ms, …).
 *
 * When a SCSS-map chain reveals **multiple themes** (e.g.
 * `$themes-config: ("themeOne": ("light": …), "dark": …), "themeTwo": (…))`),
 * the header is rendered on two rows — theme name spanning its modes — so the
 * user gets `themeOne / light | dark` instead of an ambiguous
 * `light | dark | light | dark`.
 */
object VariantTableHtml {

    /** Build the inner `<table>` markup (no `<html>` wrapper). */
    fun buildBody(token: DesignToken): String {
        val cols = collectColumns(token)
        val themes = cols.mapNotNull { it.theme }.distinct()
        return if (themes.size >= 2) buildGroupedTable(token, cols)
        else buildFlatTable(token, cols)
    }

    /** Same as [buildBody] but wrapped in `<html>` so it can be set on a tooltip / JLabel directly. */
    fun build(token: DesignToken): String = "<html>${buildBody(token)}</html>"

    // ─── Column model ─────────────────────────────────────────────────────

    private data class Col(val theme: String?, val sub: String, val value: String)

    private fun collectColumns(token: DesignToken): List<Col> {
        val cols = mutableListOf<Col>()
        val (primaryTheme, primarySub) = parseCondition(token.primaryConditionLabel ?: "")
        cols += Col(primaryTheme, primarySub, token.resolvedValue)
        for (v in token.variants) {
            val (t, s) = parseCondition(v.condition)
            cols += Col(t, s, v.value)
        }
        return cols
    }

    /**
     * Splits a condition chain (e.g. `themeOne light`, `:root.dark`,
     * `:root @media (min-width: 1024px)`) into a `(theme, sub)` pair.
     * `sub` becomes the user-facing column header (`light`, `dark`, `≥1024`,
     * `default`).
     */
    private fun parseCondition(condition: String): Pair<String?, String> {
        val s = condition.trim()
        if (s.isBlank() || s.equals("(top level)", ignoreCase = true)) return null to "default"

        // Breakpoints — no theme grouping makes sense here.
        Regex("min-width\\s*:\\s*(\\d+)\\s*px").find(s)?.let { return null to "≥${it.groupValues[1]}" }
        Regex("max-width\\s*:\\s*(\\d+)\\s*px").find(s)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return@let
            return null to "<${n + 1}"
        }

        // Pure word chain (SCSS map keys joined by space).
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

        // Selector-style chains: surface dark/light hints if any.
        Regex("(?i)(?<![A-Za-z0-9_-])(dark[\\w-]*|light[\\w-]*)(?![A-Za-z0-9_-])")
            .find(s)?.let { return null to it.value.lowercase() }
        val cleaned = s
            .replace(":root", "")
            .replace(Regex("@media\\s*"), "")
            .trim()
            .trim('(', ')')
            .trim()
            .trimStart('.', ':', '&', ' ')
        return null to cleaned.ifBlank { s }.take(24)
    }

    private fun isModeWord(s: String): Boolean {
        val l = s.lowercase()
        return l == "light" || l == "dark" || l == "auto"
    }

    // ─── Renderers ────────────────────────────────────────────────────────

    private fun buildFlatTable(token: DesignToken, cols: List<Col>): String {
        val isColor = token.category == TokenCategory.COLOR
        return buildString {
            append(tableOpen())
            append("<tr>")
            for (c in cols) append(headerCell(c.sub))
            append("</tr><tr>")
            for (c in cols) append(valueCell(c.value, isColor))
            append("</tr></table>")
        }
    }

    private fun buildGroupedTable(token: DesignToken, cols: List<Col>): String {
        val isColor = token.category == TokenCategory.COLOR
        // Group consecutive columns by theme; preserve the order they were
        // collected in (important: variants keep their declaration order).
        data class Group(val theme: String?, val members: MutableList<Col>)
        val groups = mutableListOf<Group>()
        for (c in cols) {
            val last = groups.lastOrNull()
            if (last != null && last.theme == c.theme) last.members.add(c)
            else groups.add(Group(c.theme, mutableListOf(c)))
        }
        return buildString {
            append(tableOpen())
            // Row 1: theme names spanning their members.
            append("<tr>")
            for (g in groups) {
                val span = g.members.size
                append("<td colspan='$span' align='center' bgcolor='#2b2d30' " +
                    "style='padding:4px 10px;'>")
                append("<b><font color='#dddddd'>")
                append(escape(g.theme ?: "—"))
                append("</font></b></td>")
            }
            append("</tr>")
            // Row 2: sub headers (light/dark/breakpoint/default).
            append("<tr>")
            for (c in cols) append(headerCell(c.sub))
            append("</tr>")
            // Row 3: values.
            append("<tr>")
            for (c in cols) append(valueCell(c.value, isColor))
            append("</tr></table>")
        }
    }

    private fun tableOpen(): String =
        "<table cellspacing='0' cellpadding='0' border='1' " +
            "style='border-collapse:collapse;border-color:#888;'>"

    private fun headerCell(text: String): String =
        "<td bgcolor='#3c3f41' style='padding:4px 10px;'>" +
            "<b><font color='#bbbbbb'>${escape(text)}</font></b></td>"

    private fun valueCell(value: String, isColor: Boolean): String =
        "<td style='padding:6px 10px;white-space:nowrap;'>${renderValue(value, isColor)}</td>"

    private fun renderValue(value: String, isColor: Boolean): String {
        if (!isColor) return escape(value)
        val color = ColorParser.parse(value)
            ?: return escape(value)
        val hex = "#%02X%02X%02X".format(color.red, color.green, color.blue)
        return "<font color='$hex' size='5'>&#9679;</font>" +
            "&nbsp;<font face='monospace'>${escape(hex)}</font>"
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
