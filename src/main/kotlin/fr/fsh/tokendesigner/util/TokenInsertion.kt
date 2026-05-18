package fr.fsh.tokendesigner.util

import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.model.TokenReference

/**
 * Builds the source-code text used to insert a [DesignToken] at a specific
 * offset. Most kinds defer to the canonical [TokenReference.expression], but
 * `JS_OBJECT_PATH` tokens get special-cased inside backtick template literals
 * in TS/JS files so they render as `${dt('path')}` instead of a bare
 * Style-Dictionary alias (`'{path}'`) that breaks the surrounding CSS.
 */
object TokenInsertion {

    enum class TemplateContext { OUTSIDE, INSIDE_TEMPLATE, INSIDE_INTERPOLATION }

    private val JS_EXTS = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")

    fun expressionFor(
        token: DesignToken,
        fileExtension: String?,
        text: CharSequence,
        offset: Int,
    ): String {
        val canonical = TokenReference.expression(token)
        if (token.kind != TokenKind.JS_OBJECT_PATH) return canonical
        if (fileExtension?.lowercase() !in JS_EXTS) return canonical
        return when (templateContextAt(text, offset)) {
            TemplateContext.OUTSIDE -> canonical
            TemplateContext.INSIDE_TEMPLATE -> "\${dt('${token.name}')}"
            TemplateContext.INSIDE_INTERPOLATION -> "dt('${token.name}')"
        }
    }

    /**
     * Returns the form to wrap [tokenName] (a JS object-path) with given the
     * paste position [offset]. Mirrors [expressionFor] but takes the raw path
     * already extracted from a clipboard payload.
     */
    fun wrapJsObjectPath(
        tokenName: String,
        fileExtension: String?,
        text: CharSequence,
        offset: Int,
    ): String {
        if (fileExtension?.lowercase() !in JS_EXTS) return "'{$tokenName}'"
        return when (templateContextAt(text, offset)) {
            TemplateContext.OUTSIDE -> "'{$tokenName}'"
            TemplateContext.INSIDE_TEMPLATE -> "\${dt('$tokenName')}"
            TemplateContext.INSIDE_INTERPOLATION -> "dt('$tokenName')"
        }
    }

    /**
     * Lightweight scan from the start of [text] up to [offset], tracking
     * comment / string / template-literal / interpolation state. Returns where
     * [offset] sits relative to a JS template literal.
     *
     * Heuristic — doesn't model regex literals or nested template literals.
     * Good enough for the design-token insertion case and cheaper than parsing.
     */
    fun templateContextAt(text: CharSequence, offset: Int): TemplateContext {
        var i = 0
        var inTemplate = false
        // Brace depth tracked separately for each open ${...} so a nested
        // `{}` object inside the interpolation doesn't close the wrapper.
        val braceStack = ArrayDeque<Int>()
        var inLineComment = false
        var inBlockComment = false
        var inSingleQuote = false
        var inDoubleQuote = false
        val n = minOf(offset, text.length)
        while (i < n) {
            val c = text[i]
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                i++; continue
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < n && text[i + 1] == '/') {
                    inBlockComment = false; i += 2
                } else i++
                continue
            }
            if (inSingleQuote) {
                if (c == '\\' && i + 1 < n) { i += 2; continue }
                if (c == '\'' || c == '\n') inSingleQuote = false
                i++; continue
            }
            if (inDoubleQuote) {
                if (c == '\\' && i + 1 < n) { i += 2; continue }
                if (c == '"' || c == '\n') inDoubleQuote = false
                i++; continue
            }
            if (!inTemplate) {
                when {
                    c == '/' && i + 1 < n && text[i + 1] == '/' -> { inLineComment = true; i += 2 }
                    c == '/' && i + 1 < n && text[i + 1] == '*' -> { inBlockComment = true; i += 2 }
                    c == '\'' -> { inSingleQuote = true; i++ }
                    c == '"' -> { inDoubleQuote = true; i++ }
                    c == '`' -> { inTemplate = true; i++ }
                    else -> i++
                }
            } else if (braceStack.isEmpty()) {
                // Template text (between backticks, outside any ${ ... }).
                when {
                    c == '\\' && i + 1 < n -> i += 2
                    c == '`' -> { inTemplate = false; i++ }
                    c == '$' && i + 1 < n && text[i + 1] == '{' -> {
                        braceStack.addLast(0); i += 2
                    }
                    else -> i++
                }
            } else {
                // Inside ${ ... } — JS expression mode.
                when {
                    c == '/' && i + 1 < n && text[i + 1] == '/' -> { inLineComment = true; i += 2 }
                    c == '/' && i + 1 < n && text[i + 1] == '*' -> { inBlockComment = true; i += 2 }
                    c == '\'' -> { inSingleQuote = true; i++ }
                    c == '"' -> { inDoubleQuote = true; i++ }
                    c == '`' -> {
                        // Nested template literal — model as a flat exit for
                        // the outer one. Rare in practice for our use case.
                        inTemplate = false
                        braceStack.clear()
                        i++
                    }
                    c == '{' -> {
                        braceStack[braceStack.size - 1] = braceStack.last() + 1
                        i++
                    }
                    c == '}' -> {
                        if (braceStack.last() == 0) braceStack.removeLast()
                        else braceStack[braceStack.size - 1] = braceStack.last() - 1
                        i++
                    }
                    else -> i++
                }
            }
        }
        return when {
            !inTemplate -> TemplateContext.OUTSIDE
            braceStack.isNotEmpty() -> TemplateContext.INSIDE_INTERPOLATION
            else -> TemplateContext.INSIDE_TEMPLATE
        }
    }
}
