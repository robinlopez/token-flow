package fr.fsh.tokendesigner.inspection

/**
 * Returns the nearest enclosing CSS/SCSS selector at [offset], used to group
 * hardcoded-value rows in the dashboard ("which class are we talking about?").
 *
 * Walks back from [offset], counting `{` / `}` to find the unmatched opener,
 * then extracts the text between the previous `}` / `;` / start-of-file and
 * that opener. Whitespace-collapsed.
 *
 * Returns "(top level)" when the offset is outside any block.
 */
object SelectorContext {

    fun selectorAt(text: CharSequence, offset: Int): String {
        val openerIndex = findOpeningBraceBefore(text, offset) ?: return TOP_LEVEL
        return extractSelectorBefore(text, openerIndex)
    }

    private fun findOpeningBraceBefore(text: CharSequence, offset: Int): Int? {
        var depth = 0
        var i = offset - 1
        while (i >= 0) {
            when (text[i]) {
                '}' -> depth++
                '{' -> {
                    if (depth == 0) return i
                    depth--
                }
            }
            i--
        }
        return null
    }

    private fun extractSelectorBefore(text: CharSequence, braceIndex: Int): String {
        var end = braceIndex - 1
        while (end >= 0 && text[end].isWhitespace()) end--
        if (end < 0) return TOP_LEVEL
        var start = end
        while (start > 0) {
            val c = text[start - 1]
            if (c == '}' || c == ';' || c == '{') break
            start--
        }
        val raw = text.subSequence(start, end + 1).toString()
        // Skip @-rules (e.g. @media (min-width: 768px)) and treat them as a group of their own.
        return raw.trim().replace(Regex("\\s+"), " ").take(120)
    }

    const val TOP_LEVEL = "(top level)"
}
