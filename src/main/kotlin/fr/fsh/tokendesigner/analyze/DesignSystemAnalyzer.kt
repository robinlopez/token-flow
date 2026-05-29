package fr.fsh.tokendesigner.analyze

import fr.fsh.tokendesigner.util.readAction
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
import fr.fsh.tokendesigner.analyze.Ambiguity
import fr.fsh.tokendesigner.model.TokenCategory
import fr.fsh.tokendesigner.scanner.DynamicCssVarIndex
import fr.fsh.tokendesigner.scanner.TokenCategorizer
import fr.fsh.tokendesigner.scanner.TokenIndex
import fr.fsh.tokendesigner.settings.ScopeResolver
import fr.fsh.tokendesigner.settings.TokenSelectorSettings

/**
 * Computes a [AnalysisReport] for the project's tokens & code.
 *
 * Heavy bits (codebase walk, regex scans) all run inside a read action so
 * callers must invoke this off the EDT (typically inside a `Task.Backgroundable`).
 */
@Service(Service.Level.PROJECT)
class DesignSystemAnalyzer(private val project: Project) {

    fun analyze(scopeFile: VirtualFile? = null): AnalysisReport {
        val started = System.currentTimeMillis()
        val tokens = readAction { TokenIndex.getInstance(project).get(scopeFile) }
        val ignoredNames = collectIgnoredNames(scopeFile)

        val incoherences = detectIncoherences(tokens)
        val ambiguities = detectAmbiguities(tokens, incoherences)
        val duplicates = detectDuplicates(tokens)
        val coverage = computeCoverage(tokens, scopeFile, ignoredNames)
        val broken = coverage.brokenReferences
        val hardcodedScan = collectHardcodedScan(tokens, coverage.scannedFiles, ignoredNames)
        val hardcoded = hardcodedScan.clusters
        val hardcodedValues = hardcodedScan.values
        val unused = tokens.filter { it.name !in coverage.referencedNames }.sortedBy { it.name }

        val subScores = computeSubScores(tokens, incoherences, duplicates, coverage, hardcoded, hardcodedValues)
        val score = subScores.weightedAverage()
        val grade = grade(score)

        return AnalysisReport(
            score = score,
            grade = grade,
            subScores = subScores,
            incoherences = incoherences,
            ambiguities = ambiguities,
            duplicateClusters = duplicates,
            hardcodedClusters = hardcoded,
            hardcodedValues = hardcodedValues,
            coverage = coverage.report,
            brokenReferences = broken,
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
            // The categorizer may have already reconciled a name/value clash
            // (e.g. `--stroke-default: 1px` is assigned BORDER, not COLOR,
            // because the value is a length). When the assigned category
            // matches the actual value family, the user's intent is clear and
            // the literal name reading is misleading — don't flag.
            if (categoryMatchesValueFamily(token.category, actual)) continue
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
     * Detects tokens that are not outright incoherent but whose **name is
     * ambiguous**: it maps plausibly to more than one value family, and the
     * actual resolved value matches the *less obvious* interpretation.
     *
     * Examples:
     *  - `--text-default: #45566` → `text` commonly signals typography (size,
     *    weight), but a hex colour is also valid as a text colour — ambiguous.
     *  - `--icon-primary: 24px` → `icon` usually means a colour or an asset
     *    reference, a raw length is unexpected.
     *
     * Tokens that already appear in [incoherences] are excluded — they have a
     * stronger signal and are surfaced there instead.
     */
    private fun detectAmbiguities(
        tokens: List<DesignToken>,
        incoherences: List<Incoherence>,
    ): List<Ambiguity> {
        val incoherentNames = incoherences.map { it.token.name }.toSet()
        val out = mutableListOf<Ambiguity>()

        for (token in tokens) {
            if (token.name in incoherentNames) continue
            val raw = token.resolvedValue.trim()
            if (raw.isBlank() || isUnresolvedReference(raw) || isCssKeyword(raw)) continue
            val family = valueFamily(raw) ?: continue

            val n = token.name.lowercase().trimStart('-', '$')

            // ── Rule 1: text/label/caption with a colour value ──────────────
            // These words can suggest typography (size, font) OR a text/label
            // colour — both are valid, which is exactly the ambiguity.
            // Guard: if the name also contains a word that unambiguously
            // signals colour intent (surface, foreground, disabled, hover…)
            // then the token is NOT ambiguous — its intent is obvious.
            if (TYPO_AMBIGUOUS_NAME_RE.containsMatchIn(n)
                && family == ValueFamily.COLOR
                && !COLOR_NAME_RE.containsMatchIn(n)
                && !COLOR_CONTEXT_ESCAPE_RE.containsMatchIn(n)
            ) {
                out += Ambiguity(
                    token = token,
                    reason = "The name suggests a typography token (font size, weight, family) " +
                        "but the value is a colour (`${raw.take(30)}`). " +
                        "If this is intentional (e.g. a text colour), consider renaming to " +
                        "`--text-color-*` or `--*-color` to make the intent explicit.",
                    alternativeInterpretation = "Could be a text-colour token — valid, but the name " +
                        "does not make the colour intent obvious.",
                )
                continue
            }

            // ── Rule 2: icon/glyph with a raw length value ───────────────────
            // `--icon-primary` or `--icon-size` is fine as a length, but a bare
            // `--icon-*` without a size/color sub-word holding `px` is strange.
            if (ICON_AMBIGUOUS_NAME_RE.containsMatchIn(n)
                && !ICON_SIZE_OVERRIDE_RE.containsMatchIn(n)
                && !COLOR_NAME_RE.containsMatchIn(n)
                && family == ValueFamily.LENGTH
            ) {
                out += Ambiguity(
                    token = token,
                    reason = "The name suggests an icon asset or colour token, but the value " +
                        "is a length (`${raw.take(30)}`). If this is a size token, consider " +
                        "renaming to `--icon-size-*` to make the intent clear.",
                    alternativeInterpretation = "Could be an icon-size token — valid, but \"icon\" " +
                        "without a size sub-word is ambiguous.",
                )
                continue
            }

            // ── Rule 3: generic/role names with no value-family hint ─────────
            // Names like `--primary`, `--default`, `--base`, `--global` are
            // intentionally catch-all. When we *can* determine the value family,
            // we note that the name conveys no semantic intent.
            if (GENERIC_ROLE_NAME_RE.matches(n)) {
                val familyLabel = when (family) {
                    ValueFamily.COLOR   -> "colour"
                    ValueFamily.LENGTH  -> "length / metric"
                    ValueFamily.DURATION -> "duration / animation"
                    ValueFamily.SHADOW  -> "shadow"
                    ValueFamily.NUMBER  -> "number"
                }
                out += Ambiguity(
                    token = token,
                    reason = "The token name carries no semantic hint about the kind of value " +
                        "it holds. The resolved value looks like a $familyLabel. " +
                        "Adding a category segment (e.g. `--primary-color`, " +
                        "`--default-spacing`) makes intent self-documenting.",
                    alternativeInterpretation = "A purely semantic/role name is a valid choice " +
                        "in some design systems, but it reduces discoverability.",
                )
            }
        }
        return out.sortedBy { it.token.name }
    }

    /**
     * Does [category] (the categorizer's verdict, after its own
     * name-vs-value disambiguation) line up with [valueFamily] (the shape of
     * the resolved value)? Used to suppress incoherence false-positives where
     * the categorizer already overrode the name hint.
     */
    private fun categoryMatchesValueFamily(category: TokenCategory, valueFamily: ValueFamily): Boolean = when (valueFamily) {
        ValueFamily.COLOR -> category == TokenCategory.COLOR
        ValueFamily.DURATION -> category == TokenCategory.DURATION
        ValueFamily.SHADOW -> category == TokenCategory.SHADOW
        ValueFamily.NUMBER -> category == TokenCategory.Z_INDEX || category == TokenCategory.OPACITY
        ValueFamily.LENGTH -> category in setOf(
            TokenCategory.SPACING,
            TokenCategory.RADIUS,
            TokenCategory.SIZING,
            TokenCategory.TYPOGRAPHY,
            TokenCategory.BORDER,
            TokenCategory.LAYOUT,
            TokenCategory.EFFECTS,
        )
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
            // Metric-family tokens: size, width, height, spacing, gap, margin,
            // padding, radius, border-width, stroke-width. A colour value here
            // is a clear mismatch (high-confidence). A length value is expected
            // and never flagged. Numbers (unitless scale values) are also OK.
            METRIC_NAME_RE.containsMatchIn(n) -> setOf(ValueFamily.LENGTH, ValueFamily.NUMBER)
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
        val brokenReferences: List<BrokenReference>,
    )

    private fun computeCoverage(tokens: List<DesignToken>, scopeFile: VirtualFile?, ignoredNames: Set<String>): CoverageScan {
        val searchScope = GlobalSearchScope.projectScope(project)
        // Compute which **roots** the analyser is allowed to scan. When the
        // user picks a specific scope (i.e. `scopeFile` is non-null), we
        // restrict the file walk to that scope's `rootPath` plus every
        // common scope — anything outside is irrelevant to the chosen scope
        // and would dilute the report. With `scopeFile == null` ("All
        // project") we keep the historic behaviour: every file in the
        // project's source extensions is fair game.
        val activeScopes = ScopeResolver.activeScopesFor(project, scopeFile)
        val rootRestrictions = activeScopes
            .filter { !it.isCommon }
            .mapNotNull { ScopeResolver.absolutize(project, it.rootPath) }
        // Exclude **only the active scopes' source paths** — when the user
        // analyses scope A, scope B's catalog files are unrelated noise. The
        // previous code excluded *all* configured source paths globally,
        // which suppressed useful hits in the scope under analysis.
        val excluded = (
            activeScopes.flatMap { it.sourcePaths } +
                activeScopes.flatMap { it.analysisExcludedPaths }
            ).mapNotNull { ScopeResolver.absolutize(project, it) }
        val files = mutableListOf<VirtualFile>()
        readAction {
            for (ext in COVERAGE_EXTS) {
                FilenameIndex.getAllFilesByExt(project, ext, searchScope).forEach { vf ->
                    if (rootRestrictions.isNotEmpty() && !isInsideAny(vf, rootRestrictions)) return@forEach
                    if (isExcluded(vf, excluded)) return@forEach
                    files += vf
                }
            }
        }

        var tokenised = 0
        var literal = 0
        val literalsByFile = mutableMapOf<String, List<LiteralFinder.Hit>>()
        val referenced = mutableSetOf<String>()
        val broken = mutableListOf<BrokenReference>()
        val tokenNames = tokens.map { it.name }.toSet()
        val settings = TokenSelectorSettings.getInstance(project)
        val inspectVariableDeclarations = settings.inspectVariableDeclarations
        val detectRuntimeInjected = settings.detectRuntimeInjectedCssVars
        // Union of external prefixes across every scope active for the chosen
        // analysis target. `var(--p-foo)` matched by any of them is treated as
        // a known-external reference and counted as tokenised (not broken).
        val externalPrefixes = activeScopes.flatMap { it.externalPrefixes }.distinct()
        // CSS vars declared at runtime by component code (Angular host
        // bindings, React/Vue inline styles, `setProperty`). Pulled from the
        // cached project-wide index so .html templates outside [COVERAGE_EXTS]
        // are covered too.
        val dynamicVarNames: Set<String> = if (detectRuntimeInjected) {
            DynamicCssVarIndex.getInstance(project).get()
        } else {
            emptySet()
        }

        for (vf in files) {
            val text = try {
                readAction { VfsUtilCore.loadText(vf) }
            } catch (_: Exception) { continue }

            val hits = LiteralFinder.findIn(text)
            // Vue: confine literal & reference detection to `<style>` blocks
            // — every other section is JS / HTML and would yield false hits
            // (e.g. `var(--foo)` in a JS template string).
            val styleRanges = if (vf.extension?.lowercase() == "vue") {
                fr.fsh.tokendesigner.scanner.VueStyleBlockExtractor.styleRanges(text)
            } else {
                null
            }
            val rangedHits = if (styleRanges == null) hits else hits.filter { h ->
                styleRanges.any { h.startOffset in it }
            }
            val filteredHits = rangedHits.filter {
                it.kind != LiteralFinder.Kind.REFERENCE &&
                    it.text.lowercase() !in ignoredNames &&
                    !(it.isDeclaration && (!inspectVariableDeclarations || (it.declarationName != null && it.declarationName in tokenNames)))
            }

            literalsByFile[vf.path] = filteredHits
            literal += filteredHits.size

            rangedHits.filter { it.kind == LiteralFinder.Kind.REFERENCE }.forEach { hit ->
                val name = extractTokenName(hit.text) ?: return@forEach
                tokenised++
                referenced += name

                if (name !in tokenNames && name !in ignoredNames && !name.startsWith("$")) {
                    if (externalPrefixes.isNotEmpty() && externalPrefixes.any { name.startsWith(it) }) {
                        // Known external — neutral, not broken, not counted as
                        // referenced (no canonical token to point at).
                        return@forEach
                    }
                    // Declared at runtime by component code (Angular host
                    // binding, React/Vue inline style, setProperty). Count as
                    // referenced so an owning token won't show up as unused.
                    if (name in dynamicVarNames) {
                        referenced += name
                        return@forEach
                    }
                    val resolved = resolveReferenceMatch(name, tokenNames, ignoredNames, externalPrefixes)
                    if (resolved == null) {
                        broken += BrokenReference(
                            name = hit.text,
                            filePath = vf.path,
                            offset = hit.startOffset,
                            line = lineFor(text, hit.startOffset),
                        )
                    } else {
                        referenced += resolved
                    }
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
        return CoverageScan(report, literalsByFile, files, referenced, broken)
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

    private fun isExcluded(vf: VirtualFile, excluded: List<String>): Boolean {
        val path = vf.path
        return excluded.any { path == it || path.startsWith("$it/") }
    }

    private fun isInsideAny(vf: VirtualFile, roots: List<String>): Boolean {
        val path = vf.path
        return roots.any { path == it || path.startsWith("$it/") }
    }

    // ─── Hardcoded clusters & values ─────────────────────────────────────

    /**
     * Split outcome of the hardcoded scan:
     *  - [clusters]: literals repeating >= MIN_HARDCODED_CLUSTER times with
     *    **no** matching token in the active scope — opportunity to create a
     *    new token in the DS.
     *  - [values]: literals (any occurrence count) where a matching token
     *    already exists for the same `(value, category)` pair — actionable
     *    debt, the user just needs to apply the existing token.
     */
    data class HardcodedScan(
        val clusters: List<HardcodedCluster>,
        val values: List<HardcodedValue>,
    )

    private fun collectHardcodedScan(
        tokens: List<DesignToken>,
        scannedFiles: List<VirtualFile>,
        ignoredNames: Set<String>,
    ): HardcodedScan {
        val valueIndex = TokenValueIndex(tokens)
        val settings = TokenSelectorSettings.getInstance(project)
        val inspectVariableDeclarations = settings.inspectVariableDeclarations
        // Bucket by (literal + category) so the same value used under two
        // different property families (12px padding vs 12px font-size) lands
        // in two separate groups with their own most-relevant suggestion.
        data class BucketKey(val literal: String, val category: TokenCategory?)
        data class Hit(
            val file: VirtualFile,
            val line: Int,
            val offset: Int,
            val kind: LiteralFinder.Kind,
            val expectedRole: fr.fsh.tokendesigner.model.TokenRole?,
            val propertyName: String?,
        )
        val byBucket = LinkedHashMap<BucketKey, MutableList<Hit>>()
        val tokenNames = tokens.map { it.name }.toSet()
        // Pure-declaration files: paths that *only* declare tokens (JSON/TS/JS
        // catalogs). Their entire content is a token definition, so flagging
        // any literal inside as "hardcoded" is pure noise. SCSS / CSS / Vue
        // are left in the scan even when they declare tokens, because they
        // commonly mix declarations with consumption rules.
        val pureDeclarationExtensions = setOf("ts", "tsx", "js", "jsx", "json")
        val pureDeclarationSources = tokens.asSequence()
            .filter { it.filePath.substringAfterLast('.').lowercase() in pureDeclarationExtensions }
            .map { it.filePath }
            .toSet()

        for (vf in scannedFiles) {
            if (vf.path in pureDeclarationSources) continue
            val text = try {
                readAction { VfsUtilCore.loadText(vf) }
            } catch (_: Exception) { continue }
            for (h in LiteralFinder.findIn(text)) {
                if (h.insidePartialString) continue
                if (h.kind == LiteralFinder.Kind.REFERENCE) continue
                // Skip token-map values (SCSS `$map: (...)` entries) and any
                // detected variable / object-property declaration. The latter
                // bypasses `inspectVariableDeclarations` when the declaration
                // sits in a token-source file — those declarations are *never*
                // consumption, so the setting shouldn't unhide them.
                if (h.insideTokenMap) continue
                val isCatalogFile = vf.path in pureDeclarationSources
                if (h.isDeclaration && (
                        isCatalogFile ||
                            !inspectVariableDeclarations ||
                            (h.declarationName != null && h.declarationName in tokenNames)
                        )
                ) continue

                val literal = h.text.lowercase()
                if (literal in ignoredNames) continue

                // Property-context first: most accurate. Fall back to the
                // literal's Kind so JS/TS hits (no CSS property around them)
                // still get a coarse bucket.
                val ctxCategory = PropertyContext.detectAt(text, h.startOffset)
                val role = PropertyContext.detectRoleAt(text, h.startOffset)
                val propertyName = PropertyContext.detectPropertyNameAt(text, h.startOffset)
                val category = ctxCategory ?: kindFallbackCategory(h.kind)
                val key = BucketKey(literal, category)

                byBucket.getOrPut(key) { mutableListOf() }
                    .add(Hit(vf, lineFor(text, h.startOffset), h.startOffset, h.kind, role, propertyName))
            }
        }

        val clusters = mutableListOf<HardcodedCluster>()
        val values = mutableListOf<HardcodedValue>()

        for ((key, occurrences) in byBucket) {
            val (literal, category) = key
            val firstHit = occurrences.first()
            // Synthetic Hit for the SuggestionEngine — offsets are irrelevant
            // here, we only need text + kind to drive the lookup.
            val syntheticHit = LiteralFinder.Hit(literal, 0, 0, firstHit.kind)
            val suggestions = SuggestionEngine.findSuggestions(
                syntheticHit, valueIndex, tokens, category, firstHit.expectedRole
            )
            // Strict category match for "value" classification: TokenValueIndex
            // widens lookups across the length family (SPACING/SIZING/RADIUS/
            // TYPOGRAPHY/BORDER) so a `12px` query under SIZING also returns
            // typography tokens — useful for surfacing a closest-fit suggestion,
            // but misleading when claiming "this value already has a matching
            // token". A width:20px shouldn't be flagged as actionable debt
            // because a typography token happens to hold 20px. Require the
            // exact match to live in the same category as the bucket; when
            // the bucket has no detected category (JS object, no CSS context)
            // accept any exact match.
            val exact = suggestions.firstOrNull { it.exact &&
                (category == null || it.token.category == category) &&
                isTokenUsableInAll(it.token, occurrences.map { it.file }) }?.token

            val occList = occurrences.map {
                HardcodedOccurrence(it.file.path, it.offset, it.line, it.propertyName)
            }

            if (exact != null) {
                // A token already covers this literal → actionable debt.
                values += HardcodedValue(
                    literal = literal,
                    category = category,
                    suggestedToken = exact,
                    occurrences = occList,
                )
            } else if (occurrences.size >= MIN_HARDCODED_CLUSTER) {
                clusters += HardcodedCluster(
                    literal = literal,
                    category = category,
                    occurrences = occList,
                    matchingTokenName = null,
                )
            }
        }

        // No `.take(…)` cap here: the UI uses a `+more` expander to truncate
        // the visible list, so the full set must reach the report — otherwise
        // a hard 50-row ceiling at the analyser layer artificially capped the
        // counter even when many more hits existed.
        return HardcodedScan(
            clusters = clusters.sortedByDescending { it.occurrences.size },
            values = values.sortedByDescending { it.occurrences.size },
        )
    }

    /**
     * True when [token] can be referenced from every file in [files] given
     * the file extension's binding format. CSS custom properties / SCSS
     * variables aren't reachable from a `.ts` / `.tsx` / `.js` / `.jsx`
     * source — suggesting a `var(--foo)` token in a React Native style
     * object is wrong because that file can't resolve CSS variables. Only
     * JS-flavoured token kinds (object paths, runtime property accesses,
     * runtime helpers) are valid there.
     *
     * Returns true when [files] is empty so an exact match always survives
     * if no occurrence files were captured (shouldn't happen in practice).
     */
    private fun isTokenUsableInAll(
        token: DesignToken,
        files: List<VirtualFile>,
    ): Boolean {
        if (files.isEmpty()) return true
        return files.all { isTokenUsableIn(token, it.extension?.lowercase()) }
    }

    private fun isTokenUsableIn(token: DesignToken, ext: String?): Boolean = when (ext) {
        "ts", "tsx", "js", "jsx" -> token.kind in JS_BINDABLE_KINDS
        "json" -> token.kind in JS_BINDABLE_KINDS
        "css" -> token.kind == fr.fsh.tokendesigner.model.TokenKind.CSS_CUSTOM_PROPERTY
        // SCSS / Sass / Vue: anything goes — these files routinely consume
        // both `$var` and `var(--…)`, and `<script>` blocks in Vue accept
        // JS-style tokens too.
        "scss", "sass", "vue", "html", null -> true
        else -> true
    }

    private fun kindFallbackCategory(kind: LiteralFinder.Kind): TokenCategory? = when (kind) {
        LiteralFinder.Kind.COLOR -> TokenCategory.COLOR
        LiteralFinder.Kind.LENGTH -> TokenCategory.SPACING
        LiteralFinder.Kind.DURATION -> TokenCategory.DURATION
        LiteralFinder.Kind.NUMBER -> TokenCategory.SPACING
        LiteralFinder.Kind.REFERENCE -> null
    }

    private fun lineFor(text: CharSequence, offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }

    fun collectIgnoredNames(scopeFile: VirtualFile?): Set<String> {
        val scopes = ScopeResolver.activeScopesFor(project, scopeFile)
        val excluded = scopes.flatMap { it.excludedPaths }.distinct()
        if (excluded.isEmpty()) return emptySet()

        val out = mutableSetOf<String>()
        val fs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        for (path in excluded) {
            val abs = ScopeResolver.absolutize(project, path) ?: continue
            val vf = fs.findFileByPath(abs) ?: continue
            VfsUtilCore.iterateChildrenRecursively(vf, null) { child ->
                if (!child.isDirectory) {
                    val text = try {
                        readAction { VfsUtilCore.loadText(child) }
                    } catch (_: Exception) { "" }
                    CSS_REF.findAll(text).forEach { out += "--" + it.groupValues[1] }
                    SCSS_REF.findAll(text).forEach { out += "$" + it.groupValues[1] }
                    JS_PATH_REF.findAll(text).forEach { out += it.groupValues[2] }
                    DT_REF.findAll(text).forEach { out += it.groupValues[2] }
                }
                true
            }
        }
        return out
    }

    // ─── Score aggregation ───────────────────────────────────────────────

    private fun computeSubScores(
        tokens: List<DesignToken>,
        incoherences: List<Incoherence>,
        duplicates: List<DuplicateCluster>,
        coverage: CoverageScan,
        hardcoded: List<HardcodedCluster>,
        hardcodedValues: List<HardcodedValue>,
    ): List<SubScore> {
        val totalTokens = tokens.size.coerceAtLeast(1)

        val coherenceScore = (100 - (100.0 * incoherences.size / totalTokens)).coerceIn(0.0, 100.0).toInt()
        val coverageScore = (coverage.report.ratio * 100).toInt().coerceIn(0, 100)
        val duplicateOffenders = duplicates.sumOf { it.tokens.size - 1 } // each cluster keeps one
        val duplicateScore = (100 - (100.0 * duplicateOffenders / totalTokens)).coerceIn(0.0, 100.0).toInt()

        val literalsTotal = coverage.report.literalAssignments.coerceAtLeast(1)
        // Opportunity: clusters of values with NO matching token — every hit
        // pads the deficit linearly. These are design opportunities, not bugs,
        // so weighting is moderate.
        val opportunityHits = hardcoded.sumOf { it.occurrences.size }
        val opportunityScore = (100 - (100.0 * opportunityHits / literalsTotal)).coerceIn(0.0, 100.0).toInt()
        // Debt: literals whose token already exists. Penalty per hit is x2 vs
        // opportunity — the fix is immediate and the user has no excuse not to
        // apply the existing token.
        val debtHits = hardcodedValues.sumOf { it.occurrences.size }
        val debtScore = (100 - (100.0 * debtHits / literalsTotal) * 2).coerceIn(0.0, 100.0).toInt()

        // Broken references score: penalise relative to the total number of
        // token references attempted. A broken ref is a real bug (typo, removed
        // token, wrong path), so a small ratio drags the score down fast.
        val brokenCount = coverage.brokenReferences.size
        val refTotal = coverage.report.tokenisedAssignments.coerceAtLeast(1)
        val referenceIntegrityScore = (100 - (100.0 * brokenCount / refTotal) * 4)
            .coerceIn(0.0, 100.0).toInt()

        return listOf(
            SubScore(
                Axis.SEMANTIC_COHERENCE, coherenceScore, weight = 20,
                caption = if (incoherences.isEmpty()) "All token names align with their values."
                else "${incoherences.size} token(s) with mismatched name/value semantics.",
            ),
            SubScore(
                Axis.USAGE_COVERAGE, coverageScore, weight = 20,
                caption = "${coverage.report.tokenisedAssignments} tokenised vs " +
                    "${coverage.report.literalAssignments} literal references.",
            ),
            SubScore(
                Axis.DUPLICATION, duplicateScore, weight = 15,
                caption = if (duplicates.isEmpty()) "No duplicate values detected."
                else "${duplicates.size} cluster(s), $duplicateOffenders extra token(s).",
            ),
            SubScore(
                Axis.HARDCODED_OPPORTUNITY, opportunityScore, weight = 15,
                caption = "${hardcoded.size} repeated literal(s) without a matching token.",
            ),
            SubScore(
                Axis.HARDCODED_DEBT, debtScore, weight = 10,
                caption = if (hardcodedValues.isEmpty()) "No literal usages of an already-tokenised value."
                else "${hardcodedValues.size} value(s) replaceable by an existing token ($debtHits hits).",
            ),
            SubScore(
                Axis.REFERENCE_INTEGRITY, referenceIntegrityScore, weight = 20,
                caption = if (brokenCount == 0) "All token references resolve cleanly."
                else "$brokenCount broken token reference(s) detected.",
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
        private val COVERAGE_EXTS = listOf("scss", "sass", "css", "vue", "ts", "tsx", "js", "jsx")
        /** Token kinds reachable from a `.ts` / `.tsx` / `.js` / `.jsx` / `.json` source. */
        private val JS_BINDABLE_KINDS = setOf(
            fr.fsh.tokendesigner.model.TokenKind.JS_OBJECT_PATH,
            fr.fsh.tokendesigner.model.TokenKind.JS_RUNTIME_PROPERTY,
            fr.fsh.tokendesigner.model.TokenKind.JS_RUNTIME_FUNCTION,
        )
        // Each pattern's group 1 captures the bare token name so we can fold
        // the references into a single `Set<String>` for unused-token detection.
        private val CSS_REF = Regex("var\\(\\s*--([A-Za-z_][A-Za-z0-9_-]*)(?:\\s*,.*)?\\)")
        private val SCSS_REF = Regex("(?<![A-Za-z0-9_-])\\$([A-Za-z_][A-Za-z0-9_-]*)")
        private val JS_PATH_REF = Regex("(['\"`])\\{([A-Za-z_][A-Za-z0-9_.-]*)\\}\\1")
        private val DT_REF = Regex("dt\\(\\s*(['\"`])([A-Za-z_][A-Za-z0-9_.-]*)\\1\\s*\\)")

        fun extractTokenName(text: String): String? {
            return when {
                text.startsWith("var(") -> "--" + CSS_REF.find(text)?.groupValues?.get(1)
                text.startsWith("$") -> SCSS_REF.find(text)?.groupValues?.get(1)?.let { "$" + it }
                text.startsWith("'") || text.startsWith("\"") || text.startsWith("`") ->
                    JS_PATH_REF.find(text)?.groupValues?.get(2) ?: DT_REF.find(text)?.groupValues?.get(2)
                text.startsWith("dt(") -> DT_REF.find(text)?.groupValues?.get(2)
                else -> null
            }
        }

        /**
         * Resolves a reference name to a known token (or ignored library
         * symbol). Thin wrapper over [TokenNameParser.resolveReference] that
         * also accepts the `ignoredNames` set, and recognises
         * [externalPrefixes] — variables injected at runtime by an external
         * framework (PrimeNG, Ionic, Material, …) — as legitimate references
         * by returning the original name unchanged so callers stop flagging
         * them as broken. The returned string for those cases is just `name`
         * itself (we don't have a canonical entry to point at).
         */
        fun resolveReferenceMatch(
            name: String,
            tokenNames: Set<String>,
            ignoredNames: Set<String> = emptySet(),
            externalPrefixes: List<String> = emptyList(),
        ): String? {
            fr.fsh.tokendesigner.scanner.TokenNameParser
                .resolveReference(name, tokenNames)?.let { return it.tokenName }
            if (ignoredNames.isNotEmpty()) {
                fr.fsh.tokendesigner.scanner.TokenNameParser
                    .resolveReference(name, ignoredNames)?.let { return it.tokenName }
            }
            if (externalPrefixes.isNotEmpty() && externalPrefixes.any { name.startsWith(it) }) {
                return name
            }
            return null
        }



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
        // Metric-family names: tokens whose name strongly implies a length or
        // unitless number value. A colour value here is clearly wrong.
        // Explicitly excludes `border-color` / `stroke-color` sub-words so
        // composite names don't false-positive (that case is already covered by
        // COLOR_NAME_RE hitting first in the when-chain).
        private val METRIC_NAME_RE = Regex(
            "(?<![a-z])(size|width|height|spacing|gap|margin|padding|radius|border-width|stroke-width|stroke-size)(?![a-z])"
        )
        // Ambiguity: typography-leaning names that could also carry a colour.
        // Deliberately narrow: only words that *primarily* evoke typographic
        // properties (size, weight, family) yet are also routinely used as
        // text/label colour tokens — making the intent genuinely unclear.
        // Words like `body`, `title`, `heading` are excluded because in
        // component token systems they almost always denote a UI zone (table
        // body, modal title…) rather than a typographic property, so flagging
        // them generates far too many false positives.
        private val TYPO_AMBIGUOUS_NAME_RE = Regex(
            "(?<![a-z])(text|label|caption|font)(?![a-z])"
        )
        // If ANY of these words co-occurs with a typo-ambiguous word, the
        // colour intent is considered self-evident and the token is NOT flagged.
        // Covers:
        //  • Colour-layer concepts: surface, foreground/fg, background/bg,
        //    fill, overlay, border, outline, tint, shade, on (as in on-surface),
        //    canvas, backdrop, layer, theme
        //  • UI interaction / state / status words: disabled, hover, focus, active,
        //    selected, checked, pressed, visited, placeholder, muted, faint, dim,
        //    idle, rest, inverse, contrast, link, error, success, warning, info,
        //    danger, alert, safe, critical, branding
        // Together these capture the vast majority of intentional colour tokens
        // that happen to contain a typo-leaning segment.
        private val COLOR_CONTEXT_ESCAPE_RE = Regex(
            "(?<![a-z])(surface|foreground|fg|background|bg|fill|overlay|" +
                "border|outline|tint|shade|on|layer|theme|canvas|backdrop)(?![a-z])" +
                "|(?<![a-z])(disabled|hover|focus|active|selected|checked|" +
                "pressed|visited|placeholder|muted|faint|dim|idle|rest|" +
                "inverse|contrast|link|error|success|warning|info|danger|" +
                "alert|safe|critical|branding)(?![a-z])"
        )
        // Ambiguity: icon names that would be odd with a raw length.
        private val ICON_AMBIGUOUS_NAME_RE = Regex(
            "(?<![a-z])(icon|glyph|symbol|pictogram)(?![a-z])"
        )
        // Sub-words that make an icon token's length value self-explanatory.
        // Includes size dimensions and metric/layout concepts (spacing, border, etc.)
        // to avoid flagging spacing around icons as ambiguous.
        private val ICON_SIZE_OVERRIDE_RE = Regex(
            "(?<![a-z])(size|width|height|dimension|spacing|gap|padding|margin|offset|radius|border|stroke)(?![a-z])"
        )
        // Pure role/slot names with no value-family hint whatsoever.
        private val GENERIC_ROLE_NAME_RE = Regex(
            "^(primary|secondary|tertiary|default|base|global|generic|neutral|accent|muted|subtle|main|core|normal)$"
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
