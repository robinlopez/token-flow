package fr.fsh.tokendesigner.settings

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * JSON import/export for [Scope] lists. The on-disk format is intentionally
 * small and human-readable so a Lead Designer can hand the file to teammates,
 * commit it next to a project, or diff it across branches.
 *
 * Wrapper carries a [version] tag so a future schema bump can refuse — or
 * migrate — incompatible files instead of crashing on missing fields.
 */
object ScopeConfigIO {

    // Schema version 2 adds `externalPrefixes` per scope (framework CSS vars
    // like PrimeNG/Ionic). Version 1 files load fine — missing fields default
    // to empty lists — so older configs remain importable.
    const val CURRENT_VERSION: Int = 2

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun export(scopes: List<Scope>): String {
        val payload = ScopeConfigFile(
            version = CURRENT_VERSION,
            generator = "Token Flow",
            scopes = scopes.map { ScopeDto.from(it) },
        )
        return gson.toJson(payload)
    }

    /**
     * Returns the parsed scopes or throws [ImportException] with a
     * user-readable message describing what's wrong with the file.
     */
    fun import(json: String): List<Scope> {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonSyntaxException) {
            throw ImportException("Invalid JSON: ${e.message ?: "could not parse file"}.")
        }
        if (!root.isJsonObject) throw ImportException("Expected a JSON object at the root.")
        val parsed = try {
            gson.fromJson(root, ScopeConfigFile::class.java)
        } catch (e: JsonSyntaxException) {
            throw ImportException("Schema mismatch: ${e.message ?: "unexpected structure"}.")
        }
        val version = parsed.version
        if (version != null && version > CURRENT_VERSION) {
            throw ImportException(
                "Config file version $version is newer than this plugin (supported: $CURRENT_VERSION). Update Token Flow."
            )
        }
        val scopes = parsed.scopes ?: throw ImportException("Missing \"scopes\" array.")
        return scopes.map { it.toScope() }
    }

    class ImportException(message: String) : RuntimeException(message)

    private data class ScopeConfigFile(
        val version: Int? = null,
        val generator: String? = null,
        val scopes: List<ScopeDto>? = null,
    )

    private data class ScopeDto(
        val name: String = "",
        val rootPath: String = "",
        val sourcePaths: List<String> = emptyList(),
        val excludedPaths: List<String> = emptyList(),
        val analysisExcludedPaths: List<String> = emptyList(),
        val externalPrefixes: List<String> = emptyList(),
    ) {
        fun toScope(): Scope = Scope(
            name = name,
            rootPath = rootPath,
            sourcePaths = sourcePaths,
            excludedPaths = excludedPaths,
            analysisExcludedPaths = analysisExcludedPaths,
            externalPrefixes = externalPrefixes,
        )

        companion object {
            fun from(s: Scope): ScopeDto = ScopeDto(
                name = s.name,
                rootPath = s.rootPath,
                sourcePaths = s.sourcePaths.toList(),
                excludedPaths = s.excludedPaths.toList(),
                analysisExcludedPaths = s.analysisExcludedPaths.toList(),
                externalPrefixes = s.externalPrefixes.toList(),
            )
        }
    }
}

/**
 * Merges [incoming] scopes into [current]: scopes whose [Scope.name] already
 * exists are replaced, otherwise the new scope is appended. Name matching is
 * case-insensitive and trims surrounding whitespace so "mobile " and "Mobile"
 * collapse to the same slot.
 */
fun mergeScopes(current: List<Scope>, incoming: List<Scope>): List<Scope> {
    val byKey = current.associateBy { it.name.trim().lowercase() }.toMutableMap()
    val ordered = current.toMutableList()
    for (scope in incoming) {
        val key = scope.name.trim().lowercase()
        val existing = byKey[key]
        if (existing != null) {
            val idx = ordered.indexOf(existing)
            ordered[idx] = scope
            byKey[key] = scope
        } else {
            ordered.add(scope)
            byKey[key] = scope
        }
    }
    return ordered
}
