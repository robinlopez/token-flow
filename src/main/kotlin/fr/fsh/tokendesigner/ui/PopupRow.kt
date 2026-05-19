package fr.fsh.tokendesigner.ui

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.scanner.TokenNameParser
import fr.fsh.tokendesigner.scanner.TokenStructure

sealed interface PopupRow {
    val filterKey: String
}

data class TokenPopupRow(
    val token: DesignToken,
    /**
     * Visual indent step applied by the renderer. 0 = no indent (default,
     * popup / flat list). 1 or 2 = nested under a family / sub-family header
     * in the Library panel — keeps the token name aligned with its header
     * label instead of with the accordion chevron.
     */
    val indentLevel: Int = 0,
) : PopupRow {
    override val filterKey: String get() = token.name
}

data class SeparatorPopupRow(
    val title: String,
    val collapsible: Boolean = false,
    val collapsed: Boolean = false,
    val groupKey: String = title,
    /**
     * Visual hierarchy.
     *  - `0` = top-level category header (chevron, full-width divider,
     *    bold uppercase, count on the right).
     *  - `1` = family header (medium emphasis, no chevron).
     *  - `2` = sub-family header (small italic dim text, deepest indent).
     */
    val level: Int = 0,
    /** Token count shown on the right of a category header. Ignored for nested levels. */
    val count: Int? = null,
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
    fun byCategory(
        tokens: List<DesignToken>,
        collapsedKeys: Set<String> = emptySet(),
        subfamilyGrouping: Boolean = false,
    ): List<PopupRow> {
        if (tokens.isEmpty()) return emptyList()
        val rows = mutableListOf<PopupRow>()
        val grouped = tokens.groupBy { it.category }.toSortedMap(compareBy { it.name })
        for ((category, list) in grouped) {
            val key = category.name
            val collapsed = key in collapsedKeys
            val plainTitle = category.name.lowercase().replaceFirstChar { it.titlecase() }
            rows += SeparatorPopupRow(
                title = plainTitle,
                collapsible = true,
                collapsed = collapsed,
                groupKey = key,
                level = 0,
                count = list.size,
            )
            if (collapsed) continue
            val sorted = list.sortedBy { it.name }
            val buckets = if (subfamilyGrouping) detectSubfamilies(sorted) else null
            if (buckets == null) {
                sorted.forEach { rows += TokenPopupRow(it) }
                continue
            }
            var lastFamilyKey: String? = "__sentinel__"
            for (bucket in buckets) {
                // New family ⇒ emit a level-1 header (skipped for the
                // anonymous "Other" bucket which has family == null).
                if (bucket.familyKey != lastFamilyKey) {
                    if (bucket.familyLabel != null) {
                        rows += SeparatorPopupRow(
                            title = bucket.familyLabel,
                            level = 1,
                            groupKey = "$key/${bucket.familyKey}",
                        )
                    }
                    lastFamilyKey = bucket.familyKey
                }
                // Sub-family header is omitted when the family has a single
                // bucket: an empty sub-bucket label means "no inner split".
                if (bucket.subfamilyLabel != null) {
                    rows += SeparatorPopupRow(
                        title = bucket.subfamilyLabel,
                        level = 2,
                        groupKey = "$key/${bucket.familyKey}/${bucket.subfamilyLabel}",
                    )
                }
                val indent = bucket.indentLevel
                bucket.tokens.forEach { rows += TokenPopupRow(it, indentLevel = indent) }
            }
        }
        return rows
    }

    /**
     * Splits [sorted] (one category's worth of tokens) into a hierarchical
     * family / sub-family layout. The algorithm is entirely name-structural —
     * no hard-coded vocabulary — so it adapts to any naming convention.
     *
     * Pipeline:
     *  1. Parse every name into segments and strip the longest segment-aligned
     *     prefix shared by all tokens.
     *  2. Trim the trailing state (e.g. `default` / `hover`) so structural
     *     differences drive the grouping, not variant suffixes.
     *  3. The remaining segments form a hierarchy path:
     *       segs[0]        → family   (e.g. `high`, `low`, `primary`)
     *       segs[1..n]     → sub-family path (e.g. `surface`, `content`)
     *  4. Buckets are sorted family-first by first appearance, then
     *     sub-family alphabetically inside each family. Single-token buckets
     *     fold back into a tail "Other" bucket so we don't render rows of one.
     *
     * Returns null when grouping wouldn't add information (too few tokens,
     * single family with single sub-family, all noise).
     */
    private fun detectSubfamilies(sorted: List<DesignToken>): List<HierBucket>? {
        if (sorted.size < 4) return null
        val parsed = sorted.map { it to TokenNameParser.parse(it.name).segments }
        if (parsed.any { it.second.isEmpty() }) return null

        // Longest common prefix segments across every token in this category.
        val minLen = parsed.minOf { it.second.size }
        var commonLen = 0
        while (commonLen < minLen) {
            val pivot = parsed[0].second[commonLen]
            if (parsed.all { it.second[commonLen].equals(pivot, ignoreCase = true) }) commonLen++ else break
        }

        data class Key(val family: String?, val sub: List<String>)

        val grouping = linkedMapOf<Key, MutableList<DesignToken>>()
        val others = mutableListOf<DesignToken>()
        for ((token, segs) in parsed) {
            val remainder = segs.drop(commonLen)
            // Drop the trailing state segment so e.g. `surface.default` and
            // `surface.hover` land in the same bucket.
            val structural = if (remainder.size >= 2) remainder.dropLast(1) else remainder
            if (structural.isEmpty()) {
                others += token
                continue
            }
            val family = structural[0].lowercase()
            val sub = structural.drop(1).map { it.lowercase() }
            grouping.getOrPut(Key(family, sub)) { mutableListOf() } += token
        }

        // Demote single-token buckets into the "Other" pile to avoid one-row groups.
        val keysToDrop = grouping.filterValues { it.size < 2 }.keys.toList()
        for (k in keysToDrop) others += grouping.remove(k)!!

        if (grouping.isEmpty()) return null

        // If only one family and only one sub-bucket inside it, the grouping
        // would add a single header on top of a flat list — not worth it.
        val familiesCount = grouping.keys.mapNotNull { it.family }.toSet().size
        if (familiesCount <= 1 && grouping.size == 1 && others.isEmpty()) return null

        // Emit buckets ordered by (family-first-appearance, sub-alphabetical).
        val byFamily = linkedMapOf<String?, MutableList<Pair<Key, List<DesignToken>>>>()
        for ((k, v) in grouping) {
            byFamily.getOrPut(k.family) { mutableListOf() } += k to v
        }
        val buckets = mutableListOf<HierBucket>()
        for ((family, entries) in byFamily) {
            entries.sortBy { it.first.sub.joinToString("/") }
            val familyLabel = family?.replaceFirstChar { it.titlecase() }
            val familyHasMultipleSubs = entries.size > 1
            for ((key, tokens) in entries) {
                val subLabel = if (key.sub.isEmpty()) {
                    null
                } else {
                    key.sub.joinToString(" › ") { it.replaceFirstChar { c -> c.titlecase() } }
                }
                // Hide the sub-header when there is exactly one sub-bucket
                // under this family AND that bucket has no inner segments —
                // i.e. the family header is enough on its own.
                val keepSubHeader = subLabel != null && (familyHasMultipleSubs || key.sub.isNotEmpty())
                buckets += HierBucket(
                    familyKey = family,
                    familyLabel = familyLabel,
                    subfamilyLabel = if (keepSubHeader) subLabel else null,
                    indentLevel = when {
                        keepSubHeader -> 2
                        family != null -> 1
                        else -> 0
                    },
                    tokens = tokens.sortedBy { it.name },
                )
            }
        }
        if (others.isNotEmpty()) {
            buckets += HierBucket(
                familyKey = null,
                familyLabel = null,
                subfamilyLabel = null,
                indentLevel = 0,
                tokens = others.sortedBy { it.name },
            )
        }
        return buckets
    }

    private data class HierBucket(
        val familyKey: String?,
        val familyLabel: String?,
        val subfamilyLabel: String?,
        val indentLevel: Int,
        val tokens: List<DesignToken>,
    )

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
