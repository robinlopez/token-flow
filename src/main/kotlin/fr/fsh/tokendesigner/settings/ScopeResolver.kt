package fr.fsh.tokendesigner.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Paths

object ScopeResolver {

    /**
     * Returns the scopes that apply when editing [file]:
     *   - every common scope (empty `rootPath`),
     *   - plus every scope whose `rootPath` contains [file].
     *
     * If [file] is null (e.g. tool window with no editor), returns all scopes.
     * If no scopes are configured, the caller should treat this as "scan everything".
     */
    fun activeScopesFor(project: Project, file: VirtualFile?): List<Scope> {
        val all = TokenSelectorSettings.getInstance(project).scopes
        if (all.isEmpty()) return emptyList()
        if (file == null) return all

        val filePath = file.path
        val commons = mutableListOf<Scope>()
        // Track every non-common scope whose root contains the file together
        // with the absolute root length so we can pick the deepest one — when
        // a generic scope (`bo/src`) wraps a more specific one (`bo/src/app/…`)
        // the file should be considered as belonging to the specific one only.
        val specificMatches = mutableListOf<Pair<Scope, Int>>()
        for (scope in all) {
            if (scope.isCommon) {
                commons += scope
                continue
            }
            val rootAbsolute = absolutize(project, scope.rootPath) ?: continue
            if (filePath == rootAbsolute || filePath.startsWith("$rootAbsolute/")) {
                specificMatches += scope to rootAbsolute.length
            }
        }
        val deepest = specificMatches.maxByOrNull { it.second }?.first
        return if (deepest != null) commons + deepest else commons
    }

    fun absolutize(project: Project, stored: String): String? {
        if (stored.isBlank()) return null
        return if (Paths.get(stored).isAbsolute) {
            stored
        } else {
            val base = project.basePath ?: return null
            "$base/${stored.trimStart('/')}"
        }
    }
}
