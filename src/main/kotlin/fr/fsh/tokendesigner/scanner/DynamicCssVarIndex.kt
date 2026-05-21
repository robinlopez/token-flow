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
 * Reading any of these as `var(--name)` in a stylesheet must not be flagged
 * as a broken reference — the variable exists, the declaration just doesn't
 * live in a "token catalog" file.
 *
 * Two families of declaration are recognised:
 *
 *  1. **Runtime injection** by component code — the value lands on a host
 *     element when the framework runs.
 *     Scanned in `.ts`, `.tsx`, `.js`, `.jsx`, `.html`, `.vue`:
 *      - Angular host bindings: `[style.--var-name]`, `[attr.style.--var-name]`
 *      - Angular template property syntax: `style.--var-name="…"`
 *      - DOM API: `setProperty('--var-name', …)`
 *      - React / Vue inline-style object keys: `'--var-name': value`
 *      - Vue `:style` shorthand: `:style="{ '--var-name': … }"`
 *
 *  2. **Contextual CSS overrides** — the "CSS Custom Property API" pattern
 *     where a generic component reads `var(--c, fallback)` and consumer
 *     components set the variable locally in their own stylesheets.
 *     Scanned in `.css`, `.scss`, `.sass`, `.vue` (`<style>` blocks):
 *      - `--var-name: value;` declarations anywhere in a rule body
 *      - `:root`, host selectors, BEM-style consumer selectors, …
 *
 * The set is computed lazily and cached against [PsiModificationTracker] so
 * subsequent calls are free until the user edits something. The first scan
 * after a modification walks the project once and runs cheap, context-aware
 * regexes per extension family.
 */
@Service(Service.Level.PROJECT)
class DynamicCssVarIndex(private val project: Project) {

    @Volatile private var cache: Set<String>? = null
    @Volatile private var stamp: Long = -1

    /**
     * Returns the union of every CSS variable name (with the leading `--`)
     * declared anywhere in the project — runtime injection by component
     * code or static CSS rule outside registered token sources. The first
     * call after a modification scans the relevant source files; further
     * calls are O(1) until any PSI modification bumps the tracker.
     */
    fun get(): Set<String> {
        val tracker = PsiModificationTracker.getInstance(project)
        val current = tracker.modificationCount
        cache?.let { if (stamp == current) return it }
        val computed = compute()
        cache = computed
        stamp = current
        return computed
    }

    private fun compute(): Set<String> {
        val scope = GlobalSearchScope.projectScope(project)
        val out = HashSet<String>()
        readAction {
            for (ext in ALL_EXTS) {
                FilenameIndex.getAllFilesByExt(project, ext, scope).forEach { vf ->
                    collectFrom(vf, out)
                }
            }
        }
        return out
    }

    private fun collectFrom(vf: VirtualFile, out: MutableSet<String>) {
        val ext = vf.extension?.lowercase() ?: return
        val text = try { VfsUtilCore.loadText(vf) } catch (_: Exception) { return }
        collect(text, ext, out)
    }

    companion object {
        /** Host-language extensions (component code + templates). */
        private val JS_EXTS = setOf("ts", "tsx", "js", "jsx", "html")
        /** Stylesheet extensions. */
        private val CSS_EXTS = setOf("css", "scss", "sass")
        /** Every extension we need to walk at least once. */
        private val ALL_EXTS = JS_EXTS + CSS_EXTS + setOf("vue")

        // ─── Runtime-injection patterns (TS/JS/HTML/Vue) ────────────────────

        /** Angular host or template binding: `[style.--name]`, `[attr.style.--name]`. */
        private val ANGULAR_BRACKET = Regex(
            "\\[(?:attr\\.)?style\\.--([A-Za-z_][A-Za-z0-9_-]*)\\]"
        )
        /** Angular property syntax: `style.--name="…"` (no brackets). */
        private val ANGULAR_DOT = Regex(
            "(?<![A-Za-z0-9_])style\\.--([A-Za-z_][A-Za-z0-9_-]*)"
        )
        /** `setProperty('--name', …)` — vanilla DOM / CSSOM, any quote style. */
        private val SET_PROPERTY = Regex(
            "setProperty\\s*\\(\\s*['\"`]--([A-Za-z_][A-Za-z0-9_-]*)['\"`]"
        )
        /**
         * Object-literal key form used by React / Vue inline styles:
         * `'--name': value`, `"--name": value`, `` `--name`: value ``.
         */
        private val OBJECT_KEY = Regex(
            "['\"`]--([A-Za-z_][A-Za-z0-9_-]*)['\"`]\\s*:"
        )

        // ─── Static CSS-declaration pattern (CSS/SCSS/SASS/Vue <style>) ─────

        /**
         * A bare `--name:` custom-property declaration inside a CSS rule
         * body. The negative lookbehind on `[A-Za-z0-9_-]` rules out BEM
         * identifiers like `.block__elem--mod:hover` — the `--mod` there is
         * preceded by an alphanumeric character so the regex skips it. The
         * `:` lookahead ensures we only catch the declaration syntax and
         * never the inner `--name` of a `var(--name, …)` call.
         */
        private val CSS_DECL = Regex(
            "(?<![A-Za-z0-9_-])--([A-Za-z_][A-Za-z0-9_-]*)\\s*:"
        )

        fun getInstance(project: Project): DynamicCssVarIndex =
            project.getService(DynamicCssVarIndex::class.java)

        /**
         * Runs every pattern relevant for [extension] against [text] and
         * appends each captured name (with its leading `--`) to [out].
         * Public so single-file callers (e.g. tests, on-demand scans) can
         * reuse the same logic without going through the cached service.
         */
        fun collect(text: CharSequence, extension: String, out: MutableSet<String>) {
            val ext = extension.lowercase()
            if (ext in JS_EXTS || ext == "vue") {
                ANGULAR_BRACKET.findAll(text).forEach { out += "--" + it.groupValues[1] }
                ANGULAR_DOT.findAll(text).forEach { out += "--" + it.groupValues[1] }
                SET_PROPERTY.findAll(text).forEach { out += "--" + it.groupValues[1] }
                OBJECT_KEY.findAll(text).forEach { out += "--" + it.groupValues[1] }
            }
            if (ext in CSS_EXTS || ext == "vue") {
                CSS_DECL.findAll(text).forEach { out += "--" + it.groupValues[1] }
            }
        }
    }
}
