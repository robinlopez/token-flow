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
    @JvmField var excludedPaths: MutableList<String> = mutableListOf()
    /**
     * Folders or files inside the scope's root that the Analyser must skip
     * entirely (different from [excludedPaths] which whitelists external vars).
     * Lets the user carve unrelated sub-modules out of a wide root.
     */
    @JvmField var analysisExcludedPaths: MutableList<String> = mutableListOf()

    constructor(
        name: String,
        rootPath: String,
        sourcePaths: List<String>,
        excludedPaths: List<String> = emptyList(),
        analysisExcludedPaths: List<String> = emptyList(),
    ) : this() {
        this.name = name
        this.rootPath = rootPath
        this.sourcePaths = sourcePaths.toMutableList()
        this.excludedPaths = excludedPaths.toMutableList()
        this.analysisExcludedPaths = analysisExcludedPaths.toMutableList()
    }

    val isCommon: Boolean get() = rootPath.isBlank()

    fun copy(
        name: String = this.name,
        rootPath: String = this.rootPath,
        sourcePaths: List<String> = this.sourcePaths,
        excludedPaths: List<String> = this.excludedPaths,
        analysisExcludedPaths: List<String> = this.analysisExcludedPaths,
    ): Scope = Scope(name, rootPath, sourcePaths, excludedPaths, analysisExcludedPaths)
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
        /**
         * When true, the completion contributor also triggers on typed values
         * (e.g. `padding: 4`) and suggests matching design tokens.
         */
        @JvmField var valueCompletionEnabled: Boolean = true
        /**
         * Minimum number of characters that must be typed after the property
         * colon (`:`) before value-based completion is triggered.
         * 0 = trigger immediately (even on just `property: `).
         */
        @JvmField var valueCompletionMinChars: Int = 0
        /** Tool-window icon variant — value must match an [IconVariant] enum name. */
        @JvmField var iconVariant: String = "DEFAULT"
        /** Dashboard view mode - "LIST" or "GRID" */
        @JvmField var dashboardViewMode: String = "LIST"
        /**
         * When true, the Library panel inserts family / sub-family headers
         * inside each big category, derived from the structure of token names.
         * Off by default — naming conventions vary widely, so let the user
         * opt in from the panel toolbar rather than impose a layout.
         */
        @JvmField var librarySubfamilyGrouping: Boolean = false
        /**
         * When true, the Hardcoded Values panel hides rows that have no
         * matching token suggestion. Default: ON — those rows are typically
         * noise (a literal that just doesn't map to anything in the system),
         * and surfacing only actionable rows is what users want most of the
         * time. Toggled from the panel toolbar.
         */
        @JvmField var hardcodedHideUnmatched: Boolean = true
        /**
         * When false (default), hardcoded values that are part of a variable
         * declaration (e.g. `$color: #fff`) are ignored by the inspection.
         * Useful since tokens must be defined somewhere!
         */
        @JvmField var inspectVariableDeclarations: Boolean = false

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

    var valueCompletionEnabled: Boolean
        get() = state.valueCompletionEnabled
        set(value) { state.valueCompletionEnabled = value }

    /** Minimum characters typed after `:` before value-based completion fires. */
    var valueCompletionMinChars: Int
        get() = state.valueCompletionMinChars.coerceAtLeast(0)
        set(value) { state.valueCompletionMinChars = value.coerceAtLeast(0) }

    var iconVariantName: String
        get() = state.iconVariant
        set(value) { state.iconVariant = value }

    var dashboardViewMode: String
        get() = state.dashboardViewMode
        set(value) { state.dashboardViewMode = value }

    var librarySubfamilyGrouping: Boolean
        get() = state.librarySubfamilyGrouping
        set(value) { state.librarySubfamilyGrouping = value }

    var hardcodedHideUnmatched: Boolean
        get() = state.hardcodedHideUnmatched
        set(value) { state.hardcodedHideUnmatched = value }

    var inspectVariableDeclarations: Boolean
        get() = state.inspectVariableDeclarations
        set(value) { state.inspectVariableDeclarations = value }

    /**
     * Listeners are notified after [iconVariantName] changes so live UI
     * surfaces (tool window stripe, action toolbars) can refresh their icon
     * without requiring an IDE restart.
     */
    private val iconChangeListeners = mutableListOf<() -> Unit>()
    fun addIconChangeListener(listener: () -> Unit) { iconChangeListeners += listener }
    fun fireIconChanged() { iconChangeListeners.toList().forEach { it() } }

    /**
     * Notified after the user adds/removes/edits a scope and clicks **Apply** in
     * the settings dialog. Live panels (Analyze, Dashboard) subscribe so their
     * scope-aware widgets — combos, filter chips, cached results — refresh
     * without needing the IDE restart users used to do in older versions.
     *
     * Listeners run on the EDT (settings apply is dispatched there); keep them
     * cheap or push heavy work into a background task.
     */
    private val scopesChangeListeners = mutableListOf<() -> Unit>()
    fun addScopesChangeListener(listener: () -> Unit) { scopesChangeListeners += listener }
    fun removeScopesChangeListener(listener: () -> Unit) { scopesChangeListeners -= listener }
    fun fireScopesChanged() { scopesChangeListeners.toList().forEach { it() } }

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
