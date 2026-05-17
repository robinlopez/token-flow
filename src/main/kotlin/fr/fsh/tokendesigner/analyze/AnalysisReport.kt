package fr.fsh.tokendesigner.analyze

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenCategory

/**
 * Snapshot of a Design System health analysis. Produced by
 * [DesignSystemAnalyzer] on demand and rendered by `AnalyzePanel`.
 *
 * All sections are pre-computed so the UI can render without re-walking the
 * codebase or recomputing aggregates.
 */
data class AnalysisReport(
    val score: Int,                              // 0..100 global health score
    val grade: String,                           // A / B / C / D / F derived from [score]
    val subScores: List<SubScore>,               // axis-by-axis breakdown
    val incoherences: List<Incoherence>,
    val duplicateClusters: List<DuplicateCluster>,
    val hardcodedClusters: List<HardcodedCluster>,
    val coverage: Coverage,
    val brokenReferences: List<BrokenReference>, // references (var, $) that don't exist
    val unusedTokens: List<DesignToken>,         // declared but never referenced anywhere
    val totalTokens: Int,
    val scannedFiles: Int,
    val tookMs: Long,
)

/** One axis of the global score. Each axis weights into the final note. */
data class SubScore(
    val axis: Axis,
    val score: Int,                              // 0..100
    val weight: Int,                             // weight in the global score (sum across all = 100)
    val caption: String,                         // user-facing one-liner explaining the value
)

enum class Axis(val displayName: String) {
    SEMANTIC_COHERENCE("Semantic coherence"),
    USAGE_COVERAGE("Usage coverage"),
    DUPLICATION("Duplication"),
    HARDCODED_PRESSURE("Hardcoded pressure"),
    REFERENCE_INTEGRITY("Reference integrity"),
}

/** A token whose declared name doesn't match the kind of value it carries. */
data class Incoherence(
    val token: DesignToken,
    val expectedCategory: TokenCategory,         // what the *name* implies
    val actualCategory: TokenCategory,           // what the *value* implies
    val rationale: String,                       // human-friendly explanation for the row
)

/** A group of tokens sharing the same resolved value — i.e. duplicates. */
data class DuplicateCluster(
    val resolvedValue: String,
    val category: TokenCategory,
    val tokens: List<DesignToken>,
    /**
     * The "least-bad" canonical name (shortest, most semantic) the analyser
     * recommends keeping. Purely advisory.
     */
    val suggestedCanonical: DesignToken,
)

/** A literal repeated across the codebase that isn't tokenised. */
data class HardcodedCluster(
    val literal: String,
    val category: TokenCategory?,                // best-guess category (COLOR for hex, SPACING for px…)
    val occurrences: List<HardcodedOccurrence>,
    /** Existing token whose value matches this literal, if any. */
    val matchingTokenName: String?,
)

data class HardcodedOccurrence(
    val filePath: String,
    val offset: Int,
    val line: Int,
)

data class Coverage(
    val tokenisedAssignments: Int,               // CSS prop assignments using a token reference
    val literalAssignments: Int,                 // CSS prop assignments using a hardcoded value
    val ratio: Double,                           // tokenised / (tokenised + literal); 0 when total = 0
    val sources: List<TokenSourceUsage>,         // per source-file usage breakdown
)

/**
 * Usage statistics for a single token-source file (e.g. `_tokens-semantics.scss`,
 * `tokens/semantics.ts`). Lets the user see at a glance which catalogs are
 * pulling their weight and which ones drag dead-weight.
 */
/** A token reference (var(--name), $name) found in code that doesn't exist in the design system. */
data class BrokenReference(
    val name: String,
    val filePath: String,
    val offset: Int,
    val line: Int,
)

data class TokenSourceUsage(
    val filePath: String,
    val declared: Int,                           // tokens declared in this file
    val used: Int,                               // declared tokens referenced *somewhere* in the project
) {
    val ratio: Double get() = if (declared == 0) 1.0 else used.toDouble() / declared
}
