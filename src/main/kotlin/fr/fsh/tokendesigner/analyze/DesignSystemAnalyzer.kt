package fr.fsh.tokendesigner.analyze

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import fr.fsh.tokendesigner.inspection.LiteralFinder
import fr.fsh.tokendesigner.inspection.PropertyContext
import fr.fsh.tokendesigner.inspection.SuggestionEngine
import fr.fsh.tokendesigner.inspection.TokenValueIndex
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.scanner.TokenCategorizer
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.settings.ScopeResolver
import fr.fsh.tokendesigner.settings.TokenSelectorSettings

/**
 * Computes a [AnalysisReport] for the project's tokens & code.
 *
 * Heavy bits (codebase walk, regex scans) all live behind [runReadAction]
 * so callers must invoke this off the EDT (typically inside a
 * `Task.Backgroundable`).
 */
@Service(Service.Level.PROJECT)
class DesignSystemAnalyzer(private val project: Project) {

    fun analyze(scopeFile: VirtualFile? = null): AnalysisReport {
        val started = System.currentTimeMillis()
        val tokens = runReadAction { TokenIndex.getInstance(project).get(scopeFile) }

        val incoherences = detectIncoherences(tokens)
        val duplicates = detectDuplicates(tokens)
        val coverage = computeCoverage(tokens)
        val hardcoded = collectHardcodedClusters(tokens, coverage.scannedFiles)
        val unused = tokens.filter { it.name !in coverage.referencedNames }
            .sortedBy { it.name }

        val subScores = computeSubScores(tokens, incoherences, duplicates, coverage, hardcoded)
        val score = subScores.weightedAverage()
        val grade = grade(score)

        return AnalysisReport(
            score = score,
            grade = grade,
            subScores = subScores,
            incoherences = incoherences,
            duplicateClusters = duplicates,
            hardcodedClusters = hardcoded,
            coverage = coverage.report,
            unusedTokens = unused,
            totalTokens = tokens.size,
            scannedFiles = coverage.scannedFiles.size,
            tookMs = System.currentTimeMillis() - started,
        )
    }

    // ─── Incoherence detection ───────────────────────────────────────────

    /**
     * Compares the **value family** expected from the token's name with the
     * one observed in its resolved value. Only high-signal mismatches are
     * reported (e.g. a `*-color-*` token holding a length, a `*-duration-*`
     * token holding a colour) — a `--radius-default: 16px` is **not** flagged
     * because radius and spacing share the same length family even though they
     * map to different `TokenCategory` values.
     *
     * Categories whose expected value family is too broad to check reliably
     * (typography, generic spacing/radius/size) are deliberately skipped.
     */
    private fun detectIncoherences(tokens: List<DesignToken>): List<Incoherence> {
        val out = mutableListOf<Incoherence>()
        for (token in tokens) {
            val expected = expectedFamilyFromName(token.name) ?: continue
            // Skip if the resolved value fell through to an unresolved
            // reference — we'd just be reporting noise, not a real mismatch.
            val raw = token.resolvedValue.trim()
            if (raw.isBlank() || isUnresolvedReference(raw) || isCssKeyword(raw)) continue
            val actual = valueFamily(raw) ?: continue
            if (expected.contains(actual)) continue
            out += Incoherence(
                token = token,
                expectedCategory = expected.first().tokenCategoryHint,
                actualCategory = actual.tokenCategoryHint,
                rationale = describeMismatch(expected, actual, raw),
            )
        }
        return out.sortedBy { it.token.name }
    }

    /**
     * Coarse-grained "shape" of a value. We deliberately collapse spacing /
     * radius / size into a single [LENGTH] bucket because their value space is
     * identical (`px`, `rem`, …) — distinguishing them would only generate
     * false positives.
     */
    private enum class ValueFamily(val tokenCategoryHint: TokenCategory) {
        COLOR(TokenCategory.COLOR),
        LENGTH(TokenCategory.SPACING),
        DURATION(TokenCategory.DURATION),
        SHADOW(TokenCategory.SHADOW),
        NUMBER(TokenCategory.Z_INDEX),
    }

    /**
     * What value family the [name] *demands*. Returns a set: e.g. a `shadow`
     * token can legitimately resolve to a single `0 4px 12px rgba(...)`
     * shadow **or** to a `none` keyword (filtered out earlier). Returning
     * `null` means we have no opinion and the token isn't checked.
     */
    private fun expectedFamilyFromName(name: String): Set<ValueFamily>? {
        val n = name.lowercase().trimStart('-', '$')
        // Only the tokens whose name unambiguously demands a specific family
        // are kept. Generic words like "size" or "value" are intentionally
        // out — they're routinely used for fonts, weights, lengths, etc.
        return when {
            // Strong colour cues: a token named `*-color-*`, `*-fill-*`,
            // `*-stroke-*` should resolve to a colour. We avoid bare `bg` /
            // `background` because they sometimes hold an image URL.
            COLOR_NAME_RE.containsMatchIn(n) -> setOf(ValueFamily.COLOR)
            DURATION_NAME_RE.containsMatchIn(n) -> setOf(ValueFamily.DURATION)
            SHADOW_NAME_RE.containsMatchIn(n) -> setOf(ValueFamily.SHADOW, ValueFamily.LENGTH)
            ZINDEX_NAME_RE.containsMatchIn(n) -> setOf(ValueFamily.NUMBER)
            // Other heuristics (radius / spacing / size / typography) are
            // skipped: too ambiguous to flag confidently.
            else -> null
        }
    }

    private fun valueFamily(value: String): ValueFamily? {
        val v = value.trim()
        return when {
            COLOR_VALUE_RE.containsMatchIn(v) || HEX_VALUE_RE.matches(v) -> ValueFamily.COLOR
            DURATION_VALUE_RE.matches(v) -> ValueFamily.DURATION
            SHADOW_VALUE_RE.containsMatchIn(v) -> ValueFamily.SHADOW
            LENGTH_VALUE_RE.matches(v) -> ValueFamily.LENGTH
            NUMBER_VALUE_RE.matches(v) -> ValueFamily.NUMBER
            else -> null
        }
    }

    private fun describeMismatch(expected: Set<ValueFamily>, actual: ValueFamily, raw: String): String {
        val want = expected.joinToString("/") { it.name.lowercase() }
        return "Name implies a $want value but the resolved value is " +
            "${actual.name.lowercase()} (`${raw.take(40)}`)."
    }

    private fun isUnresolvedReference(v: String): Boolean =
        v.startsWith("var(") || v.startsWith("$") || v.startsWith("{") ||
            v.contains("#{") /* SCSS interpolation */

    private fun isCssKeyword(v: String): Boolean = v.lowercase() in CSS_KEYWORDS

    // ─── Duplicate detection ─────────────────────────────────────────────

    /**
     * A "duplicate" requires:
     *  - identical full signature (primary + every `(condition, value)` variant),
     *  - **and** the cluster has to span at least two distinct source files.
     *
     * Why the cross-file rule? A single token catalog (e.g.
     * `_tokens-semantics.scss`) is a deliberately-curated matrix where many
     * roles (`global-*`, `token-*`, `nav-*`, …) can legitimately share the
     * same colour for the same mode. Flagging those as duplicates would just
     * add noise — the designer made the choice on purpose. The actual
     * redundancy worth surfacing is when *separate* files end up redeclaring
     * the same value, which usually means a missed import.
     */
    private fun detectDuplicates(tokens: List<DesignToken>): List<DuplicateCluster> {
        val grouped = tokens.groupBy { fullValueSignature(it) }
            .filter { (key, list) ->
                key.isNotBlank() &&
                    list.size > 1 &&
                    list.map { it.filePath }.distinct().size > 1
            }
        return grouped.map { (_, list) ->
            // Pick the shortest name as the canonical recommendation — it tends
            // to be the most semantic / least specialised.
            val canonical = list.minBy { it.name.length }
            DuplicateCluster(
                resolvedValue = list.first().resolvedValue,
                category = list.first().category,
                tokens = list.sortedBy { it.name },
                suggestedCanonical = canonical,
            )
        }.sortedByDescending { it.tokens.size }
    }

    /** Normalises a resolved value for equality: lowercases, collapses whitespace. */
    private fun canonicalValue(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Concatenates the primary value with every (condition, value) variant pair. */
    private fun fullValueSignature(token: DesignToken): String {
        val primary = canonicalValue(token.resolvedValue)
        val variants = token.variants
            .map { "${it.condition.lowercase()}=${canonicalValue(it.value)}" }
            .sorted()
            .joinToString("|")
        return "$primary||$variants"
    }

    // ─── Coverage + hardcoded scan (shared single pass) ──────────────────

    private data class CoverageScan(
        val report: Coverage,
        val literalsByFile: Map<String, List<LiteralFinder.Hit>>,
        val scannedFiles: List<VirtualFile>,
        /** Every token name found in a `var(--…)`, `$…` or `'{path}'` reference
         *  across the codebase — used downstream for unused-token detection. */
        val referencedNames: Set<String>,
    )

    private fun computeCoverage(tokens: List<DesignToken>): CoverageScan {
        val scope = GlobalSearchScope.projectScope(project)
        val excluded = excludedRoots()
        val files = mutableListOf<VirtualFile>()
        runReadAction {
            for (ext in COVERAGE_EXTS) {
                FilenameIndex.getAllFilesByExt(project, ext, scope).forEach { vf ->
                    if (!isExcluded(vf, excluded)) files += vf
                }
            }
        }

        var tokenised = 0
        var literal = 0
        val literalsByFile = mutableMapOf<String, List<LiteralFinder.Hit>>()
        val referenced = mutableSetOf<String>()

        for (vf in files) {
            val text = try {
                runReadAction { VfsUtilCore.loadText(vf) }
            } catch (_: Exception) { continue }
            val hits = LiteralFinder.findIn(text)
            literalsByFile[vf.path] = hits
            literal += hits.size

            // Collect every distinct token-reference in this file. Each match
            // contributes both as a "tokenised" assignment count and as a
            // referenced-name entry.
            CSS_REF.findAll(text).forEach {
                tokenised++
                referenced += "--" + it.groupValues[1]
            }
            SCSS_REF.findAll(text).forEach {
                tokenised++
                referenced += "$" + it.groupValues[1]
            }
            JS_PATH_REF.findAll(text).forEach {
                tokenised++
                val raw = it.groupValues[1]
                referenced += raw
                // JS preset paths embed a mode segment (`token.modeLight.x`)
                // but tokens are indexed under their canonical (mode-stripped)
                // name — fold both forms in so usage detection matches.
                fr.fsh.tokendesigner.scanner.TokenNameParser.stripModeSegment(raw)?.let {
                    canonical -> referenced += canonical
                }
            }
        }
        val ratio = if (tokenised + literal == 0) 1.0
        else tokenised.toDouble() / (tokenised + literal)

        val sources = perSourceUsage(tokens, referenced)
        val report = Coverage(
            tokenisedAssignments = tokenised,
            literalAssignments = literal,
            ratio = ratio,
            sources = sources,
        )
        return CoverageScan(report, literalsByFile, files, referenced)
    }

    /**
     * Aggregates usage by **source file** so the analyser can show the user
     * how much of each token catalog is actually referenced — the natural
     * compass for spotting bloat in the design system.
     */
    private fun perSourceUsage(
        tokens: List<DesignToken>,
        referenced: Set<String>,
    ): List<TokenSourceUsage> =
        tokens.groupBy { it.filePath }
            .map { (path, decls) ->
                val used = decls.count { it.name in referenced }
                TokenSourceUsage(filePath = path, declared = decls.size, used = used)
            }
            .sortedBy { it.ratio }

    private fun excludedRoots(): List<String> {
        val settings = TokenSelectorSettings.getInstance(project)
        return settings.allSourcePaths.mapNotNull { ScopeResolver.absolutize(project, it) }
    }

    private fun isExcluded(vf: VirtualFile, excluded: List<String>): Boolean {
        val path = vf.path
        return excluded.any { path == it || path.startsWith("$it/") }
    }

    // ─── Hardcoded clusters ──────────────────────────────────────────────

    private fun collectHardcodedClusters(
        tokens: List<DesignToken>,
        scannedFiles: List<VirtualFile>,
    ): List<HardcodedCluster> {
        val valueIndex = TokenValueIndex(tokens)
        // Re-use the per-file literal scan but bucket by canonical literal.
        data class Hit(val file: VirtualFile, val line: Int, val offset: Int)
        val byLiteral = LinkedHashMap<String, MutableList<Hit>>()
        val literalKinds = HashMap<String, LiteralFinder.Kind>()

        for (vf in scannedFiles) {
            val text = try {
                runReadAction { VfsUtilCore.loadText(vf) }
            } catch (_: Exception) { continue }
            for (h in LiteralFinder.findIn(text)) {
                if (h.insidePartialString) continue
                val key = h.text.lowercase()
                byLiteral.getOrPut(key) { mutableListOf() }
                    .add(Hit(vf, lineFor(text, h.startOffset), h.startOffset))
                literalKinds.putIfAbsent(key, h.kind)
            }
        }

        return byLiteral.entries
            .asSequence()
            .filter { it.value.size >= MIN_HARDCODED_CLUSTER }
            .mapNotNull { (lit, occurrences) ->
                val kind = literalKinds[lit]
                val cat = kind?.let {
                    when (it) {
                        LiteralFinder.Kind.COLOR -> TokenCategory.COLOR
                        LiteralFinder.Kind.LENGTH -> TokenCategory.SPACING
                        LiteralFinder.Kind.DURATION -> TokenCategory.DURATION
                    }
                }
                val matching = cat?.let { c -> valueIndex.lookup(lit, c).firstOrNull()?.name }
                // If the literal already resolves to an existing token, the
                // codebase-wide inspection / quick-fix already surfaces it.
                // Surfacing it again here pads the report with already-fixable
                // clusters; skip and keep the analyser focused on truly
                // un-tokenised values.
                if (matching != null) return@mapNotNull null
                HardcodedCluster(
                    literal = lit,
                    category = cat,
                    occurrences = occurrences.map {
                        HardcodedOccurrence(it.file.path, it.offset, it.line)
                    },
                    matchingTokenName = null,
                )
            }
            .sortedByDescending { it.occurrences.size }
            .take(50)
            .toList()
    }

    private fun lineFor(text: CharSequence, offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }

    // ─── Score aggregation ───────────────────────────────────────────────

    private fun computeSubScores(
        tokens: List<DesignToken>,
        incoherences: List<Incoherence>,
        duplicates: List<DuplicateCluster>,
        coverage: CoverageScan,
        hardcoded: List<HardcodedCluster>,
    ): List<SubScore> {
        val totalTokens = tokens.size.coerceAtLeast(1)

        val coherenceScore = (100 - (100.0 * incoherences.size / totalTokens)).coerceIn(0.0, 100.0).toInt()
        val coverageScore = (coverage.report.ratio * 100).toInt().coerceIn(0, 100)
        val duplicateOffenders = duplicates.sumOf { it.tokens.size - 1 } // each cluster keeps one
        val duplicateScore = (100 - (100.0 * duplicateOffenders / totalTokens)).coerceIn(0.0, 100.0).toInt()
        val hardcodedHits = hardcoded.sumOf { it.occurrences.size }
        val literalsTotal = coverage.report.literalAssignments.coerceAtLeast(1)
        val hardcodedScore = (100 - (100.0 * hardcodedHits / literalsTotal)).coerceIn(0.0, 100.0).toInt()

        return listOf(
            SubScore(
                Axis.SEMANTIC_COHERENCE, coherenceScore, weight = 30,
                caption = if (incoherences.isEmpty()) "All token names align with their values."
                else "${incoherences.size} token(s) with mismatched name/value semantics.",
            ),
            SubScore(
                Axis.USAGE_COVERAGE, coverageScore, weight = 30,
                caption = "${coverage.report.tokenisedAssignments} tokenised vs " +
                    "${coverage.report.literalAssignments} literal references.",
            ),
            SubScore(
                Axis.DUPLICATION, duplicateScore, weight = 20,
                caption = if (duplicates.isEmpty()) "No duplicate values detected."
                else "${duplicates.size} cluster(s), $duplicateOffenders extra token(s).",
            ),
            SubScore(
                Axis.HARDCODED_PRESSURE, hardcodedScore, weight = 20,
                caption = "${hardcoded.size} repeated literal(s) worth tokenising.",
            ),
        )
    }

    private fun List<SubScore>.weightedAverage(): Int {
        val totalWeight = sumOf { it.weight }.coerceAtLeast(1)
        val weighted = sumOf { it.score * it.weight }
        return (weighted.toDouble() / totalWeight).toInt().coerceIn(0, 100)
    }

    private fun grade(score: Int): String = when {
        score >= 90 -> "A"
        score >= 75 -> "B"
        score >= 60 -> "C"
        score >= 45 -> "D"
        else -> "F"
    }

    companion object {
        private const val MIN_HARDCODED_CLUSTER = 2
        private val COVERAGE_EXTS = listOf("scss", "sass", "css", "ts", "tsx", "js", "jsx")
        // Each pattern's group 1 captures the bare token name so we can fold
        // the references into a single `Set<String>` for unused-token detection.
        private val CSS_REF = Regex("var\\(\\s*--([A-Za-z_][A-Za-z0-9_-]*)\\s*\\)")
        private val SCSS_REF = Regex("(?<![A-Za-z0-9_])\\$([A-Za-z_][A-Za-z0-9_-]*)")
        private val JS_PATH_REF = Regex("['\"`]\\{([A-Za-z_][A-Za-z0-9_.-]*)\\}['\"`]")

        // ─── Incoherence patterns ────────────────────────────────────────
        // Word boundaries so `--text-color` matches "color" but `--scrollbar`
        // doesn't accidentally match "color" (it doesn't, but the same
        // discipline avoids future surprises with names like `border-radius`).
        // `stroke` alone = color; `stroke-width` / `stroke-size` = length → excluded.
        private val COLOR_NAME_RE = Regex(
            "(?<![a-z])(color|colour|fill|tint|shade|palette|swatch)(?![a-z])" +
                "|(?<![a-z])stroke(?!-(width|size|weight|opacity))"
        )
        private val DURATION_NAME_RE = Regex(
            "(?<![a-z])(duration|delay|easing|ease)(?![a-z])"
        )
        private val SHADOW_NAME_RE = Regex(
            "(?<![a-z])(shadow|elevation)(?![a-z])"
        )
        private val ZINDEX_NAME_RE = Regex(
            "(?<![a-z])(z-?index|layer|stack-?level)(?![a-z])"
        )

        private val HEX_VALUE_RE = Regex("^#[0-9a-fA-F]{3,8}$")
        private val COLOR_VALUE_RE = Regex(
            "^(?:rgb|rgba|hsl|hsla|hwb|lab|lch|oklab|oklch|color)\\(",
            RegexOption.IGNORE_CASE
        )
        private val DURATION_VALUE_RE = Regex("^-?\\d*\\.?\\d+(?:ms|s)$")
        // Only flag as SHADOW when at least two `<length> <length>` groups
        // appear separated by whitespace (offset/blur). A single
        // comma-separated list of lengths/colors isn't a shadow.
        private val SHADOW_VALUE_RE = Regex(
            "\\d+(?:\\.\\d+)?(?:px|rem|em)\\s+\\d+(?:\\.\\d+)?(?:px|rem|em)"
        )
        private val LENGTH_VALUE_RE = Regex(
            "^-?\\d*\\.?\\d+(?:px|rem|em|%|vh|vw|ch|ex)$"
        )
        private val NUMBER_VALUE_RE = Regex("^-?\\d+(?:\\.\\d+)?$")

        private val CSS_KEYWORDS = setOf(
            "inherit", "initial", "unset", "auto", "none", "transparent",
            "currentcolor", "normal", "revert", "revert-layer"
        )

        fun getInstance(project: Project): DesignSystemAnalyzer =
            project.getService(DesignSystemAnalyzer::class.java)
    }
}
