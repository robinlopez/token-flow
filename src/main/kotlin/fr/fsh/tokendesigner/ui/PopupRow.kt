package fr.fsh.tokendesigner.ui

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.scanner.TokenNameParser
import fr.fsh.tokendesigner.scanner.TokenStructure

sealed interface PopupRow {
    val filterKey: String
}

data class TokenPopupRow(val token: DesignToken) : PopupRow {
    override val filterKey: String get() = token.name
}

data class SeparatorPopupRow(
    val title: String,
    val collapsible: Boolean = false,
    val collapsed: Boolean = false,
    val groupKey: String = title,
) : PopupRow {
    override val filterKey: String get() = title
}

object RowGrouping {

    /**
     * Builds the list of rows displayed in the popup, with [SeparatorPopupRow]
     * inserted between groups. Two tokens belong to the same group when they
     * share every structural segment except the trailing one (the state).
     *
     * Example with pivot `--token-actions-low-stroke-hover`:
     *
     *   [Stroke]
     *     --token-actions-low-stroke-default
     *     --token-actions-low-stroke-hover
     *     ...
     *   [Content]
     *     --token-actions-low-content-default
     *     ...
     *   [Surface]
     *     ...
     *   [High → stroke]
     *     --token-actions-high-stroke-default
     *     ...
     */
    /** Builds rows grouped by category, used by the dashboard tool window. */
    fun byCategory(tokens: List<DesignToken>, collapsedKeys: Set<String> = emptySet()): List<PopupRow> {
        if (tokens.isEmpty()) return emptyList()
        val rows = mutableListOf<PopupRow>()
        val grouped = tokens.groupBy { it.category }.toSortedMap(compareBy { it.name })
        for ((category, list) in grouped) {
            val key = category.name
            val collapsed = key in collapsedKeys
            val title = "${category.name.lowercase().replaceFirstChar { it.titlecase() }} · ${list.size}"
            rows += SeparatorPopupRow(title = title, collapsible = true, collapsed = collapsed, groupKey = key)
            if (!collapsed) {
                list.sortedBy { it.name }.forEach { rows += TokenPopupRow(it) }
            }
        }
        return rows
    }

    fun buildRows(sorted: List<DesignToken>, pivot: DesignToken?): List<PopupRow> {
        if (sorted.isEmpty()) return emptyList()
        val rows = mutableListOf<PopupRow>()
        val pivotStruct = pivot?.let(::structureOf)
        var lastGroupKey: String? = null

        for (token in sorted) {
            val struct = structureOf(token)
            val groupKey = groupKeyOf(struct)
            if (groupKey != lastGroupKey) {
                rows += SeparatorPopupRow(labelOf(struct, pivotStruct))
                lastGroupKey = groupKey
            }
            rows += TokenPopupRow(token)
        }
        return rows
    }

    /** Group key = every segment but the trailing state. */
    private fun groupKeyOf(struct: TokenStructure): String {
        val segs = struct.segments
        return when {
            segs.size <= 1 -> struct.raw
            else -> segs.dropLast(1).joinToString("-")
        }
    }

    /**
     * Builds a human-readable group label showing only the segments that diverge
     * from the pivot, joined with `›`. Falls back to the last segment when there
     * is no pivot or when both prefixes match completely.
     */
    private fun labelOf(struct: TokenStructure, pivot: TokenStructure?): String {
        val groupSegs = struct.segments.dropLast(1)
        if (pivot == null) {
            return capitalize(groupSegs.lastOrNull() ?: struct.raw)
        }
        val pivotSegs = pivot.segments.dropLast(1)
        var i = 0
        while (i < groupSegs.size && i < pivotSegs.size && groupSegs[i].equals(pivotSegs[i], true)) {
            i++
        }
        if (i >= groupSegs.size) return capitalize(groupSegs.lastOrNull() ?: "Other")
        return groupSegs.subList(i, groupSegs.size)
            .joinToString(" › ") { it.lowercase() }
            .replaceFirstChar { it.titlecase() }
    }

    private fun capitalize(s: String): String =
        s.lowercase().replaceFirstChar { it.titlecase() }

    private fun structureOf(token: DesignToken): TokenStructure =
        TokenNameParser.parse(token.name)
}
