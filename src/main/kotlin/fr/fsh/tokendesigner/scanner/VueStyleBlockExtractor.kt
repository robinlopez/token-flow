package fr.fsh.tokendesigner.scanner

/**
 * Locates `<style …>…</style>` blocks inside a Vue Single File Component.
 *
 * The plugin is Community-compatible, where `.vue` is a plain text file (no
 * Vue PSI injection), so detection must be text-based to work consistently
 * across both Community and Ultimate. This extractor is also reused by the
 * Hardcoded Value inspection and completion contributors to restrict their
 * scan window to the style blocks — the `<template>` and `<script>` sections
 * must stay invisible to token detection or we'd flag `var(--foo)` literals
 * in JS template strings as CSS declarations.
 *
 * The returned [Block.startOffset] is the offset of the block's first content
 * character *in the original file*, so callers can extract tokens from the
 * inner text and add `startOffset` to relocate them at file-absolute
 * coordinates — gutter swatches, go-to-declaration and the locator stay
 * accurate.
 */
object VueStyleBlockExtractor {

    /**
     * One `<style …>` block. [lang] is normalised to lowercase (`css`,
     * `scss`, `sass`, `postcss`, `less`, `stylus`, …); empty defaults to
     * `css`. [contentStart]/[contentEnd] mark the inner text range, exclusive
     * of the surrounding tags.
     */
    data class Block(
        val lang: String,
        val scoped: Boolean,
        val module: Boolean,
        val src: String?,
        val text: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    /**
     * Returns every `<style …>…</style>` block found in [fileText]. Blocks
     * with a `src="…"` attribute are returned but have no inline text — the
     * referenced file is scanned independently by the normal extension-based
     * walker, so callers can skip them when extracting tokens.
     */
    fun extract(fileText: CharSequence): List<Block> {
        val result = mutableListOf<Block>()
        val openMatches = OPEN_TAG_REGEX.findAll(fileText)
        for (open in openMatches) {
            val attrs = open.groupValues[1]
            val tagEnd = open.range.last + 1
            // Match the matching `</style>` after this opening tag. We accept
            // arbitrary whitespace inside the close so `</ style >` is still
            // honoured.
            val closeMatch = CLOSE_TAG_REGEX.find(fileText, startIndex = tagEnd) ?: continue
            val contentStart = tagEnd
            val contentEnd = closeMatch.range.first
            if (contentEnd <= contentStart) continue
            val text = fileText.substring(contentStart, contentEnd)
            result += Block(
                lang = parseAttr(attrs, "lang")?.lowercase() ?: "css",
                scoped = ATTR_FLAG.matches("scoped", attrs),
                module = ATTR_FLAG.matches("module", attrs),
                src = parseAttr(attrs, "src"),
                text = text,
                startOffset = contentStart,
                endOffset = contentEnd,
            )
        }
        return result
    }

    /**
     * Returns the absolute file ranges covered by `<style>` blocks — meant for
     * features that scan the raw file text (Hardcoded inspection, completion)
     * so they can restrict their analysis window. For non-Vue file types the
     * caller should fall back to "the whole file"; this helper only knows
     * about Vue SFCs.
     */
    fun styleRanges(fileText: CharSequence): List<IntRange> =
        extract(fileText)
            .filter { it.src == null && it.text.isNotEmpty() }
            .map { it.startOffset until it.endOffset }

    /**
     * Resolves the effective stylesheet "extension" at a given offset of a
     * `.vue` file: `"scss"` for `<style lang="scss">`, `"sass"` for sass,
     * `"css"` otherwise. Returns null when [offset] sits outside any inline
     * `<style>` block (template / script / `src="…"` blocks) — callers should
     * treat that as "do nothing here" so we don't apply CSS-like behaviour
     * inside Vue templates or JS code.
     */
    fun effectiveStyleExtAt(fileText: CharSequence, offset: Int): String? {
        val block = extract(fileText).firstOrNull {
            it.src == null && offset in it.startOffset..it.endOffset
        } ?: return null
        return when (block.lang) {
            "scss" -> "scss"
            "sass" -> "sass"
            else -> "css"
        }
    }

    private fun parseAttr(attrs: String, name: String): String? {
        val rx = Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
        val m = rx.find(attrs) ?: return null
        return m.groupValues.drop(2).firstOrNull { it.isNotEmpty() }
    }

    private object ATTR_FLAG {
        /** Matches the bare presence of `name` (e.g. `scoped`) as a boolean attribute. */
        fun matches(name: String, attrs: String): Boolean =
            Regex("""\b$name\b(?!\s*=)""", RegexOption.IGNORE_CASE).containsMatchIn(attrs)
    }

    /**
     * Opens with `<style` followed by either whitespace+attrs or `>`. The lazy
     * `(.*?)` then `>` captures any attribute soup without crossing into the
     * content. `DOT_MATCHES_ALL` so newlines inside attributes are tolerated.
     */
    private val OPEN_TAG_REGEX = Regex(
        """<style((?:\s+[^>]*)?)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val CLOSE_TAG_REGEX = Regex(
        """</\s*style\s*>""",
        RegexOption.IGNORE_CASE,
    )
}
