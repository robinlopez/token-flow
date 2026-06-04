package fr.fsh.tokendesigner.scanner

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import fr.fsh.tokendesigner.util.readAction

/**
 * Project-wide index of every CSS custom-property name *declared somewhere*
 * in the codebase outside the token source files registered in Settings.
 *
 * Two families of declaration are recognised:
 *
 *  1. **Runtime injection** by component code — the value lands on a host
 *     element when the framework runs.
 *     Scanned in `.ts`, `.tsx`, `.js`, `.jsx`, `.html`, `.vue`:
 *      - Angular host bindings: `[style.--var-name]: 'expr'`
 *      - Angular property syntax: `style.--var-name="expr"`
 *      - DOM API: `setProperty('--var-name', expr)`
 *      - React / Vue inline-style object keys: `'--var-name': expr`
 *      - Vue `:style` shorthand: `:style="{ '--var-name': … }"`
 *
 *  2. **Contextual CSS overrides** — the "CSS Custom Property API" pattern
 *     where a generic component reads `var(--c, fallback)` and consumer
 *     components set the variable locally in their own stylesheets.
 *     Scanned in `.css`, `.scss`, `.sass`, `.vue` (`<style>` blocks):
 *      - `--var-name: value;` declarations anywhere in a rule body
 *
 * Each match is stored as a [CssVarOccurrence] carrying file path, line,
 * raw value text and (for CSS declarations) the innermost enclosing
 * selector — enough for the alternatives popup to render a navigable list
 * of "where this variable is set". The whole map is computed lazily and
 * cached against [PsiModificationTracker] so subsequent calls are free
 * until the user edits something.
 */
@Service(Service.Level.PROJECT)
class DynamicCssVarIndex(private val project: Project) {

    @Volatile private var cache: Map<String, List<CssVarOccurrence>>? = null
    @Volatile private var stamp: Long = -1

    /** Set of every declared name (with leading `--`). Kept for backwards compatibility with the broken-ref check. */
    fun get(): Set<String> = occurrences().keys

    /**
     * Full occurrence map — every place where each `--name` is declared.
     * The first call after a modification walks the project once; further
     * calls are O(1) until any PSI modification bumps the tracker.
     */
    fun occurrences(): Map<String, List<CssVarOccurrence>> {
        val tracker = PsiModificationTracker.getInstance(project)
        val current = tracker.modificationCount
        cache?.let { if (stamp == current) return it }
        val computed = compute()
        cache = computed
        stamp = current
        return computed
    }

    /** Convenience accessor used by the alternatives popup. */
    fun occurrencesOf(name: String): List<CssVarOccurrence> =
        occurrences()[name].orEmpty()

    private fun compute(): Map<String, List<CssVarOccurrence>> {
        val scope = GlobalSearchScope.projectScope(project)
        val out = HashMap<String, MutableList<CssVarOccurrence>>()
        readAction {
            for (ext in ALL_EXTS) {
                FilenameIndex.getAllFilesByExt(project, ext, scope).forEach { vf ->
                    collectFrom(vf, out)
                }
            }
        }
        return out
    }

    private fun collectFrom(vf: VirtualFile, out: MutableMap<String, MutableList<CssVarOccurrence>>) {
        val ext = vf.extension?.lowercase() ?: return
        val text = try { VfsUtilCore.loadText(vf) } catch (_: Exception) { return }
        collect(text, ext, vf.path) { occ ->
            out.getOrPut(occ.name) { mutableListOf() }.add(occ)
        }
    }

    companion object {
        /** Host-language extensions (component code + templates). */
        private val JS_EXTS = setOf("ts", "tsx", "js", "jsx", "html")
        /** Stylesheet extensions. */
        private val CSS_EXTS = setOf("css", "scss", "sass")
        /** Every extension we need to walk at least once. */
        private val ALL_EXTS = JS_EXTS + CSS_EXTS + setOf("vue")

        // ─── Runtime-injection patterns (TS/JS/HTML/Vue) ────────────────────
        // Each capture group 1 = the bare name (without `--`).
        // Group 2, when present, = the value/binding expression text.

        /** Angular host or template binding key: `[style.--name]`, `[attr.style.--name]`. */
        private val ANGULAR_BRACKET = Regex(
            "\\[(?:attr\\.)?style\\.--([A-Za-z_][A-Za-z0-9_-]*)\\]"
        )
        /** Angular property syntax: `style.--name="expr"`. Value optional. */
        private val ANGULAR_DOT = Regex(
            "(?<![A-Za-z0-9_])style\\.--([A-Za-z_][A-Za-z0-9_-]*)(?:\\s*=\\s*[\"']([^\"']*)[\"'])?"
        )
        /** `setProperty('--name', value)` — vanilla DOM / CSSOM, any quote style. */
        private val SET_PROPERTY = Regex(
            "setProperty\\s*\\(\\s*['\"`]--([A-Za-z_][A-Za-z0-9_-]*)['\"`]\\s*,\\s*([^)]*)\\)"
        )
        /**
         * Object-literal key form used by React / Vue inline styles and
         * Angular host bindings:
         * `'--name': value`, `"--name": value`, `` `--name`: value ``.
         */
        private val OBJECT_KEY = Regex(
            "['\"`]--([A-Za-z_][A-Za-z0-9_-]*)['\"`]\\s*:\\s*([^,}\\n]*)"
        )

        // ─── Static CSS-declaration pattern (CSS/SCSS/SASS/Vue <style>) ─────

        /**
         * A bare `--name: value` custom-property declaration inside a CSS
         * rule body. Group 1 = name, group 2 = value (everything up to the
         * statement terminator). The negative lookbehind on `[A-Za-z0-9_&-]`
         * rules out BEM identifiers in selectors — `.block__elem--mod:hover`
         * (preceded by an alphanumeric) *and* SCSS parent-ref modifiers like
         * `&--selected:not(.x)` (preceded by `&`). See issue #25.
         */
        private val CSS_DECL = Regex(
            "(?<![A-Za-z0-9_&-])--([A-Za-z_][A-Za-z0-9_-]*)\\s*:\\s*([^;\\n}]*)"
        )

        fun getInstance(project: Project): DynamicCssVarIndex =
            project.getService(DynamicCssVarIndex::class.java)

        /**
         * Runs every pattern relevant for [extension] against [text] and
         * delivers each match as a [CssVarOccurrence] via [emit]. The
         * caller decides how to store / dedupe — keeps this helper
         * allocation-free at its own level.
         */
        fun collect(
            text: CharSequence,
            extension: String,
            filePath: String,
            emit: (CssVarOccurrence) -> Unit,
        ) {
            val ext = extension.lowercase()
            if (ext in JS_EXTS || ext == "vue") {
                ANGULAR_BRACKET.findAll(text).forEach { m ->
                    emit(buildOccurrence(text, filePath, m, "--${m.groupValues[1]}", "", isRuntime = true, selector = null))
                }
                ANGULAR_DOT.findAll(text).forEach { m ->
                    val value = m.groupValues.getOrNull(2).orEmpty().trim()
                    emit(buildOccurrence(text, filePath, m, "--${m.groupValues[1]}", value, isRuntime = true, selector = null))
                }
                SET_PROPERTY.findAll(text).forEach { m ->
                    val value = m.groupValues.getOrNull(2)?.trim().orEmpty()
                    emit(buildOccurrence(text, filePath, m, "--${m.groupValues[1]}", value, isRuntime = true, selector = null))
                }
                OBJECT_KEY.findAll(text).forEach { m ->
                    // Skip object-literal hits that fall inside a `var(--…)` argument list — we
                    // don't want to count `var(--foo, '--bar')` as a declaration of --bar.
                    if (insideVarCall(text, m.range.first)) return@forEach
                    val value = m.groupValues.getOrNull(2)?.trim().orEmpty()
                    emit(buildOccurrence(text, filePath, m, "--${m.groupValues[1]}", value, isRuntime = true, selector = null))
                }
            }
            if (ext in CSS_EXTS || ext == "vue") {
                CSS_DECL.findAll(text).forEach { m ->
                    val value = m.groupValues.getOrNull(2)?.trim().orEmpty()
                    val selector = innermostSelector(text, m.range.first)
                    emit(buildOccurrence(text, filePath, m, "--${m.groupValues[1]}", value, isRuntime = false, selector = selector))
                }
            }
        }

        /** Builds a populated occurrence record from a regex match. */
        private fun buildOccurrence(
            text: CharSequence,
            filePath: String,
            match: MatchResult,
            name: String,
            value: String,
            isRuntime: Boolean,
            selector: String?,
        ): CssVarOccurrence = CssVarOccurrence(
            name = name,
            value = value,
            filePath = filePath,
            offset = match.range.first,
            line = lineFor(text, match.range.first),
            selector = selector,
            isRuntime = isRuntime,
        )

        private fun lineFor(text: CharSequence, offset: Int): Int {
            var line = 1
            val n = offset.coerceAtMost(text.length)
            for (i in 0 until n) if (text[i] == '\n') line++
            return line
        }

        /**
         * Returns true when [offset] sits inside a `var(…)` argument list.
         * Used to suppress false positives where the `OBJECT_KEY` regex
         * would match a string-shaped fallback inside a `var(--x, '--y')`.
         */
        private fun insideVarCall(text: CharSequence, offset: Int): Boolean {
            // Walk backwards looking for the nearest unmatched `(`; check if
            // it's preceded by `var`.
            var depth = 0
            var i = offset - 1
            while (i >= 0) {
                val c = text[i]
                if (c == ')') depth++
                else if (c == '(') {
                    if (depth == 0) {
                        // Found the open paren — is the prefix "var"?
                        return i >= 3 &&
                            text[i - 3] == 'v' && text[i - 2] == 'a' && text[i - 1] == 'r'
                    } else {
                        depth--
                    }
                } else if (c == ';' || c == '{' || c == '}') {
                    return false
                }
                i--
            }
            return false
        }

        /**
         * Best-effort innermost selector text for a CSS declaration at
         * [declOffset]. Walks backwards keeping a `}/{` brace depth; when
         * an unmatched `{` is found, the selector text lives between the
         * previous boundary (`{` / `}` / `;` / file start) and that `{`.
         *
         * Returns `null` when no enclosing rule is found (declaration at
         * the top level of a Sass indent file, malformed input, …).
         * Nested SCSS rules return the innermost selector only — walking
         * the full parent chain would require a real parser; this is good
         * enough to let the user identify the declaration site at a glance.
         */
        private fun innermostSelector(text: CharSequence, declOffset: Int): String? {
            var depth = 0
            var i = declOffset - 1
            while (i >= 0) {
                when (val c = text[i]) {
                    '}' -> depth++
                    '{' -> {
                        if (depth == 0) {
                            var j = i - 1
                            while (j >= 0 && text[j] !in BOUNDARY_CHARS) j--
                            val raw = text.subSequence(j + 1, i).toString()
                                .replace('\n', ' ')
                                .replace(Regex("\\s+"), " ")
                                .trim()
                            return raw.takeIf { it.isNotEmpty() }
                        }
                        depth--
                    }
                    else -> {}
                }
                i--
            }
            return null
        }

        private val BOUNDARY_CHARS = charArrayOf('{', '}', ';').toSet()
    }
}

/**
 * One concrete declaration site for a `--var-name` outside registered
 * token sources. Used to power the "where is this variable set?" popup
 * surfaced by Alt+T on contextually-declared CSS variables.
 *
 *  - [name]      always includes the leading `--`.
 *  - [value]     the raw value text as written by the author. For runtime
 *                injection where no value is parseable (e.g. a bare
 *                Angular host-binding key), this is an empty string.
 *  - [filePath]  absolute VFS path of the file.
 *  - [offset]    byte offset of the *match start* in the file — used to
 *                open the editor at the right position on click.
 *  - [line]      1-based line number for display.
 *  - [selector]  innermost CSS selector for static CSS declarations,
 *                `null` for runtime-injection occurrences.
 *  - [isRuntime] `true` for TS/JS/HTML/Vue runtime patterns, `false` for
 *                static CSS declarations.
 */
data class CssVarOccurrence(
    val name: String,
    val value: String,
    val filePath: String,
    val offset: Int,
    val line: Int,
    val selector: String?,
    val isRuntime: Boolean,
)
