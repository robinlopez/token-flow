package fr.fsh.tokendesigner.scanner

/**
 * Parses a design token name into ordered segments.
 *
 * Convention observed in the field (e.g. EDF Safety Portal design system):
 *   `[domaine]-[famille]-[niveau]-[propriété]-[état]`
 *
 *   --global-high-surface-default
 *   --token-actions-low-stroke-hover
 *   --token-form-error-content-disabled
 *
 * The parser does NOT enforce these names — it just splits on `-` and exposes
 * the segments. Sorting/grouping logic uses prefix matching to find structural
 * neighbors regardless of the exact convention.
 */
data class TokenStructure(
    val raw: String,
    val segments: List<String>,
) {
    val state: String? get() = segments.lastOrNull()
}

object TokenNameParser {

    /**
     * Display order for known states. Tokens whose state is unknown fall to the end,
     * sorted alphabetically.
     */
    private val STATE_ORDER = listOf(
        "default",
        "placeholder",
        "odd",
        "even",
        "hover",
        "focused",
        "pressed",
        "active",
        "checked",
        "checkedtext",
        "hoveractive",
        "disabled",
        "disabledchecked",
    )

    fun parse(name: String): TokenStructure {
        val stripped = name.removePrefix("--").removePrefix("$")
        // Split on both `.` (JS object paths: `colors.primary.500`) and `-`
        // (CSS / SCSS hyphenated names: `--token-actions-high-…`). Some preset
        // files mix both — splitting on either keeps grouping & family
        // detection aligned with the segment hierarchy.
        val segments = if (stripped.isEmpty()) emptyList() else stripped.split('.', '-')
            .filter { it.isNotEmpty() }
        return TokenStructure(name, segments)
    }

    /**
     * Detects a "mode" segment (`modeLight`, `modeDark`, `mode-xxx`, …).
     * These segments come from theme presets where light/dark variants live at
     * sibling paths and would otherwise look like unrelated tokens.
     */
    fun isModeSegment(segment: String): Boolean {
        val s = segment.lowercase()
        return s.startsWith("mode") && s.length > 4 && (s[4].isLetter() || s[4] == '-')
    }

    /**
     * Strips the first `modeXxx` segment from a JS/TS object path so light/dark
     * variants share a single canonical name. Returns null when [name] has no
     * mode segment (i.e. nothing to strip).
     */
    fun stripModeSegment(name: String): String? {
        if (!name.contains('.')) return null
        val parts = name.split('.')
        val idx = parts.indexOfFirst { isModeSegment(it) }
        if (idx < 0) return null
        return (parts.subList(0, idx) + parts.subList(idx + 1, parts.size)).joinToString(".")
    }

    /** Returns the mode segment of [name], lowercased and prefix-stripped (`light`, `dark`). */
    fun modeSegmentOf(name: String): String? {
        if (!name.contains('.')) return null
        val seg = name.split('.').firstOrNull(::isModeSegment) ?: return null
        return seg.removePrefix("mode").removePrefix("Mode").trimStart('-').lowercase()
    }

    /** Returns the *raw* mode segment as it appeared in [name] (`modeLight`, `modeDark`). */
    fun rawModeSegmentOf(name: String): String? {
        if (!name.contains('.')) return null
        return name.split('.').firstOrNull(::isModeSegment)
    }

    /** Returns the index (in the dot-split form of [name]) of the mode segment, or -1. */
    fun modeSegmentIndex(name: String): Int {
        if (!name.contains('.')) return -1
        return name.split('.').indexOfFirst(::isModeSegment)
    }

    /**
     * Re-injects [rawModeSegment] (e.g. `modeLight`) at [index] inside [canonical].
     * Used when replacing a JS token reference so the original mode is kept
     * (`{token.modeLight.x.y}` → `{token.modeLight.x.z}`, not `{token.x.z}`).
     */
    fun injectModeSegment(canonical: String, rawModeSegment: String, index: Int): String {
        val segs = canonical.split('.').toMutableList()
        val pos = index.coerceIn(0, segs.size)
        segs.add(pos, rawModeSegment)
        return segs.joinToString(".")
    }

    /**
     * Resolves a reference name to a token that exists in [tokenNames], tolerating
     * three common notation drifts:
     *  1. A leading export-binding segment (`token.…`) that the indexer strips.
     *  2. A mode segment (`modeLight` / `modeDark`) that the indexer strips.
     *  3. Adjacent path segments that are split with a dot in source but live as
     *     a single camelCase segment in the token tree
     *     (e.g. `…default.high.surface.default` vs `…defaultHigh.surface.default`),
     *     and the inverse (camelCase in source, split in the tree).
     *
     * Returns the matched token name along with the binding prefix that had to
     * be stripped (`"token."` or `""`), so callers can re-inject it when
     * rebuilding a replacement string.
     */
    fun resolveReference(name: String, tokenNames: Set<String>): ResolvedReference? {
        tryMatch(name, "", tokenNames)?.let { return it }
        val canonical = stripModeSegment(name) ?: name
        if (canonical != name) tryMatch(canonical, "", tokenNames)?.let { return it }
        if (!name.contains('.')) return null

        val bindingPrefix = name.substringBefore('.') + "."
        val stripped = name.substringAfter('.')
        tryMatch(stripped, bindingPrefix, tokenNames)?.let { return it }
        val strippedCanonical = stripModeSegment(stripped) ?: stripped
        if (strippedCanonical != stripped) {
            tryMatch(strippedCanonical, bindingPrefix, tokenNames)?.let { return it }
        }
        return null
    }

    /**
     * Exact match first, then a single merge of an adjacent segment pair
     * (`a.b` → `aB`), then a single split of a camelCase segment (`aB` → `a.b`).
     * One transformation is enough for the camelCase / dot drifts seen in
     * practice; deeper combinations would blow up exponentially without buying
     * real-world coverage.
     */
    private fun tryMatch(candidate: String, prefix: String, tokenNames: Set<String>): ResolvedReference? {
        if (candidate in tokenNames) return ResolvedReference(candidate, prefix)
        val segs = candidate.split('.')
        if (segs.size >= 2) {
            for (i in 0 until segs.size - 1) {
                val merged = segs.toMutableList()
                merged[i] = merged[i] + merged[i + 1].replaceFirstChar(Char::uppercase)
                merged.removeAt(i + 1)
                val candidateMerged = merged.joinToString(".")
                if (candidateMerged in tokenNames) return ResolvedReference(candidateMerged, prefix)
            }
        }
        for (i in segs.indices) {
            val split = splitCamelCaseOnce(segs[i]) ?: continue
            val expanded = segs.toMutableList()
            expanded[i] = split.first
            expanded.add(i + 1, split.second)
            val candidateExpanded = expanded.joinToString(".")
            if (candidateExpanded in tokenNames) return ResolvedReference(candidateExpanded, prefix)
        }
        return null
    }

    /** `defaultHigh` → `("default", "high")`, `default` → null. */
    private fun splitCamelCaseOnce(segment: String): Pair<String, String>? {
        val idx = segment.indexOfFirst { it.isUpperCase() }
        if (idx <= 0) return null
        return segment.substring(0, idx) to segment.substring(idx).replaceFirstChar(Char::lowercase)
    }

    data class ResolvedReference(val tokenName: String, val bindingPrefix: String)

    /** Number of leading segments (excluding the state) that two tokens share. */
    fun commonStructuralPrefix(a: TokenStructure, b: TokenStructure): Int {
        // Compare everything except the trailing state segment to avoid favoring
        // identical states across unrelated families.
        val limit = minOf(a.segments.size, b.segments.size) - 1
        if (limit <= 0) return 0
        var n = 0
        while (n < limit && a.segments[n].equals(b.segments[n], ignoreCase = true)) n++
        return n
    }

    /** Lower index = displayed first. Unknown states share the high default index. */
    fun statePriority(state: String?): Int {
        if (state == null) return Int.MAX_VALUE
        val idx = STATE_ORDER.indexOf(state.lowercase())
        return if (idx >= 0) idx else STATE_ORDER.size
    }
}
