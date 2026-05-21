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
 * Project-wide index of CSS custom-property names that are *declared at
 * runtime* by component code rather than by a static stylesheet. Reading any
 * of these as `var(--name)` in a CSS/SCSS/Vue style block must not be flagged
 * as a broken reference — the variable is set on a host element when the
 * framework runs.
 *
 * Detected patterns (across `.ts`, `.tsx`, `.js`, `.jsx`, `.html`, `.vue`):
 *  - Angular host bindings: `[style.--var-name]`, `[attr.style.--var-name]`
 *  - Angular property syntax inside templates: `style.--var-name="…"`
 *  - DOM API: `setProperty('--var-name', …)`
 *  - React / Vue object-literal inline styles: `'--var-name': value`,
 *    `"--var-name": value`, `` `--var-name`: value ``
 *  - Vue `:style` shorthand: `:style="{ '--var-name': … }"`
 *
 * The set is computed lazily and cached against [PsiModificationTracker] so
 * subsequent calls are free until the user edits something.
 */
@Service(Service.Level.PROJECT)
class DynamicCssVarIndex(private val project: Project) {

    @Volatile private var cache: Set<String>? = null
    @Volatile private var stamp: Long = -1

    /**
     * Returns the union of every CSS variable name (with the leading `--`)
     * declared dynamically anywhere in the project. The first call after a
     * modification scans the relevant source files; further calls are O(1)
     * until any PSI modification bumps the tracker.
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
            for (ext in HOST_EXTS) {
                FilenameIndex.getAllFilesByExt(project, ext, scope).forEach { vf ->
                    collectFrom(vf, out)
                }
            }
        }
        return out
    }

    private fun collectFrom(vf: VirtualFile, out: MutableSet<String>) {
        val text = try { VfsUtilCore.loadText(vf) } catch (_: Exception) { return }
        collect(text, out)
    }

    companion object {
        private val HOST_EXTS = listOf("ts", "tsx", "js", "jsx", "html", "vue")

        /** Angular host or template binding: `[style.--name]`, `[attr.style.--name]`. */
        private val ANGULAR_BRACKET = Regex(
            "\\[(?:attr\\.)?style\\.--([A-Za-z_][A-Za-z0-9_-]*)\\]"
        )
        /** Angular property syntax: `style.--name="…"` (no brackets). */
        private val ANGULAR_DOT = Regex(
            "(?<![A-Za-z0-9_])style\\.--([A-Za-z_][A-Za-z0-9_-]*)"
        )
        /** `setProperty('--name', …)` (or "…" / `…`). Covers vanilla DOM and CSSOM. */
        private val SET_PROPERTY = Regex(
            "setProperty\\s*\\(\\s*['\"`]--([A-Za-z_][A-Za-z0-9_-]*)['\"`]"
        )
        /**
         * Object-literal key form used by React / Vue inline styles:
         * `'--name': value`, `"--name": value`, `` `--name`: value ``.
         * The `:` lookahead avoids matching the `--name` half of a `var(--name, …)`
         * fallback chain or other usages.
         */
        private val OBJECT_KEY = Regex(
            "['\"`]--([A-Za-z_][A-Za-z0-9_-]*)['\"`]\\s*:"
        )

        fun getInstance(project: Project): DynamicCssVarIndex =
            project.getService(DynamicCssVarIndex::class.java)

        /**
         * Pure helper exposed for direct use during one-shot scans (e.g. the
         * Analyser already iterates every project file — it can collect dynamic
         * names in the same pass without going through the cached service).
         */
        fun collect(text: CharSequence, out: MutableSet<String>) {
            ANGULAR_BRACKET.findAll(text).forEach { out += "--" + it.groupValues[1] }
            ANGULAR_DOT.findAll(text).forEach { out += "--" + it.groupValues[1] }
            SET_PROPERTY.findAll(text).forEach { out += "--" + it.groupValues[1] }
            OBJECT_KEY.findAll(text).forEach { out += "--" + it.groupValues[1] }
        }
    }
}
