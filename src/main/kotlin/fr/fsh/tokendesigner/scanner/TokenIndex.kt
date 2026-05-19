package fr.fsh.tokendesigner.scanner

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.settings.Scope
import fr.fsh.tokendesigner.settings.ScopeResolver
import fr.fsh.tokendesigner.settings.TokenSelectorSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-project, scope-aware token cache.
 *
 *  - When scopes are configured: each scope is scanned and cached separately;
 *    [get] returns the union of tokens from scopes active for the supplied
 *    file (specific scopes shadow the common ones on name collisions).
 *  - When no scope is configured: legacy behaviour — every `.scss/.sass/.css`
 *    file in the project is scanned and cached as a single bucket.
 *  - Cache is invalidated on any VFS change to a token-bearing file and on
 *    settings changes (callers must call [invalidate] from the configurable).
 */
@Service(Service.Level.PROJECT)
class TokenIndex(private val project: Project) : Disposable {

    private val scopeCache = ConcurrentHashMap<String, List<DesignToken>>()
    private val globalCache = AtomicReference<List<DesignToken>?>(null)

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { isRelevant(it) }) invalidate()
                }
            })
    }

    /**
     * Returns the tokens visible to [file]. Pass `null` to get every token
     * from every configured scope (for the dashboard tool window for example).
     */
    fun get(file: VirtualFile? = null): List<DesignToken> {
        val configuredScopes = TokenSelectorSettings.getInstance(project).scopes
        if (configuredScopes.isEmpty()) return getGlobal()

        val active = ScopeResolver.activeScopesFor(project, file)
        if (active.isEmpty()) return emptyList()

        // Specific scopes are searched first so that, on name collisions, they
        // shadow common scopes (the "more specific wins" rule).
        val ordered = active.sortedBy { if (it.isCommon) 1 else 0 }
        val byName = LinkedHashMap<String, DesignToken>()
        for (scope in ordered) {
            for (token in getScope(scope)) {
                byName.putIfAbsent(token.name, token)
            }
        }
        return byName.values.toList()
    }

    private fun getScope(scope: Scope): List<DesignToken> =
        scopeCache.computeIfAbsent(scope.cacheKey()) {
            TokenScanner.getInstance(project).scanPaths(scope.sourcePaths)
        }

    private fun getGlobal(): List<DesignToken> {
        globalCache.get()?.let { return it }
        val computed = TokenScanner.getInstance(project).scanPaths(emptyList())
        globalCache.compareAndSet(null, computed)
        return globalCache.get() ?: computed
    }

    fun invalidate() {
        scopeCache.clear()
        globalCache.set(null)
    }

    override fun dispose() {
        invalidate()
    }

    private fun isRelevant(event: VFileEvent): Boolean {
        val path = event.path
        return TARGET_SUFFIXES.any { path.endsWith(it, ignoreCase = true) }
    }

    private fun Scope.cacheKey(): String = "$name|$rootPath|${sourcePaths.joinToString(",")}"

    companion object {
        private val TARGET_SUFFIXES = listOf(
            ".scss", ".sass", ".css", ".vue",
            ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
        )

        fun getInstance(project: Project): TokenIndex =
            project.getService(TokenIndex::class.java)
    }
}
