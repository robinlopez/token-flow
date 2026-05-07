package fr.fsh.tokendesigner.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * A scope groups together a list of source-of-truth paths that apply to a
 * specific area of the project.
 *
 *  - `name` is the user-facing label (e.g. "Mobile", "Desktop", "Common").
 *  - `rootPath` (relative to project base) defines where the scope applies:
 *    when an editor file is inside `rootPath`, this scope's tokens are active.
 *    An empty `rootPath` means "common" — the scope is always active.
 *  - `sourcePaths` lists files or folders containing tokens for this scope
 *    (same semantics as the previous flat `sourcePaths` setting).
 */
class Scope() {
    @JvmField var name: String = ""
    @JvmField var rootPath: String = ""
    @JvmField var sourcePaths: MutableList<String> = mutableListOf()

    constructor(name: String, rootPath: String, sourcePaths: List<String>) : this() {
        this.name = name
        this.rootPath = rootPath
        this.sourcePaths = sourcePaths.toMutableList()
    }

    val isCommon: Boolean get() = rootPath.isBlank()

    fun copy(
        name: String = this.name,
        rootPath: String = this.rootPath,
        sourcePaths: List<String> = this.sourcePaths,
    ): Scope = Scope(name, rootPath, sourcePaths)
}

@State(
    name = "DesignTokenSelectorSettings",
    storages = [Storage("designTokenSelector.xml")],
)
@Service(Service.Level.PROJECT)
class TokenSelectorSettings : PersistentStateComponent<TokenSelectorSettings.State> {

    class State {
        @JvmField var scopes: MutableList<Scope> = mutableListOf()
        @JvmField var openOnHover: Boolean = false
        @JvmField var hoverDelayMs: Int = 700
        @JvmField var autocompleteEnabled: Boolean = true
        /** Tool-window icon variant — value must match an [IconVariant] enum name. */
        @JvmField var iconVariant: String = "DEFAULT"

        // Legacy single-list configuration. Kept for backward compatibility:
        // on first load it is migrated into a single common Scope and emptied.
        @JvmField var sourcePaths: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
        migrateLegacyIfNeeded()
    }

    private fun migrateLegacyIfNeeded() {
        if (state.scopes.isEmpty() && state.sourcePaths.isNotEmpty()) {
            state.scopes.add(Scope(name = "Common", rootPath = "", sourcePaths = state.sourcePaths))
            state.sourcePaths = mutableListOf()
        }
    }

    var scopes: List<Scope>
        get() = state.scopes.toList()
        set(value) {
            state.scopes = value.toMutableList()
        }

    var openOnHover: Boolean
        get() = state.openOnHover
        set(value) { state.openOnHover = value }

    var hoverDelayMs: Int
        get() = state.hoverDelayMs.coerceIn(100, 5000)
        set(value) { state.hoverDelayMs = value.coerceIn(100, 5000) }

    var autocompleteEnabled: Boolean
        get() = state.autocompleteEnabled
        set(value) { state.autocompleteEnabled = value }

    var iconVariantName: String
        get() = state.iconVariant
        set(value) { state.iconVariant = value }

    /**
     * Listeners are notified after [iconVariantName] changes so live UI
     * surfaces (tool window stripe, action toolbars) can refresh their icon
     * without requiring an IDE restart.
     */
    private val iconChangeListeners = mutableListOf<() -> Unit>()
    fun addIconChangeListener(listener: () -> Unit) { iconChangeListeners += listener }
    fun fireIconChanged() { iconChangeListeners.toList().forEach { it() } }

    /**
     * Backwards compatibility: returns every distinct source path across all
     * scopes. Used when no editor file is available (e.g. dashboard "all").
     */
    val allSourcePaths: List<String>
        get() = state.scopes.flatMap { it.sourcePaths }.distinct()

    companion object {
        fun getInstance(project: Project): TokenSelectorSettings = project.service()
    }
}
