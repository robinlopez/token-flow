package fr.fsh.tokendesigner.scanner

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import fr.fsh.tokendesigner.inspection.DeclarationContext
import fr.fsh.tokendesigner.model.DesignToken
import fr.fsh.tokendesigner.model.TokenKind
import fr.fsh.tokendesigner.model.TokenVariant
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class TokenScanner(private val project: Project) {

    /**
     * Scans the given list of source paths and returns the resolved tokens.
     * If [paths] is empty, falls back to scanning every `.scss/.sass/.css`
     * file in the project (the "no scope configured" case).
     */
    fun scanPaths(paths: List<String>): List<DesignToken> {
        val raw = mutableListOf<RawToken>()
        if (paths.isEmpty()) {
            scanAllProjectFiles(raw)
        } else {
            scanConfiguredPaths(raw, paths)
        }
        return resolve(raw)
    }

    private fun scanAllProjectFiles(sink: MutableList<RawToken>) {
        val scope = GlobalSearchScope.projectScope(project)
        val seen = HashSet<String>()
        for (ext in TARGET_EXTENSIONS) {
            FilenameIndex.getAllFilesByExt(project, ext, scope).forEach { vf ->
                if (seen.add(vf.path)) extractFrom(vf, sink)
            }
        }
    }

    private fun scanConfiguredPaths(sink: MutableList<RawToken>, paths: List<String>) {
        val seen = HashSet<String>()
        for (rel in paths) {
            val vf = resolveConfiguredPath(rel) ?: continue
            if (vf.isDirectory) {
                VfsUtilCore.visitChildrenRecursively(vf, object : VirtualFileVisitor<Any>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (!file.isDirectory &&
                            file.extension?.lowercase() in TARGET_EXTENSIONS_SET &&
                            seen.add(file.path)
                        ) {
                            extractFrom(file, sink)
                        }
                        return true
                    }
                })
            } else if (seen.add(vf.path)) {
                extractFrom(vf, sink)
            }
        }
    }

    private fun resolveConfiguredPath(stored: String): VirtualFile? {
        val absolute = if (Paths.get(stored).isAbsolute) {
            stored
        } else {
            val base = project.basePath ?: return null
            "$base/$stored"
        }
        return LocalFileSystem.getInstance().findFileByPath(absolute)
    }

    private fun extractFrom(file: VirtualFile, sink: MutableList<RawToken>) {
        if (file.isDirectory || file.length > MAX_FILE_BYTES) return
        val text = try {
            VfsUtilCore.loadText(file)
        } catch (_: Exception) {
            return
        }
        val isScss = file.extension?.lowercase() in SCSS_EXTS
        val path = file.path

        if (isScss) {
            for (m in SCSS_VAR_REGEX.findAll(text)) {
                sink += RawToken(
                    name = "\$" + m.groupValues[1],
                    rawValue = m.groupValues[2].trim().trimEnd(';').trim(),
                    kind = TokenKind.SCSS_VARIABLE,
                    filePath = path,
                    offset = m.range.first,
                )
            }
            // SCSS map keys: `"some-token": value,` — typical Style-Dictionary-style sources
            // where the canonical names are stored as quoted map entries. We promote these
            // to CSS_CUSTOM_PROPERTY because that is how they will be referenced (`var(--name)`).
            for (m in SCSS_MAP_KEY_REGEX.findAll(text)) {
                sink += RawToken(
                    name = "--" + m.groupValues[1],
                    rawValue = m.groupValues[2].trim(),
                    kind = TokenKind.CSS_CUSTOM_PROPERTY,
                    filePath = path,
                    offset = m.range.first,
                )
            }
        }
        for (m in CSS_VAR_REGEX.findAll(text)) {
            sink += RawToken(
                name = "--" + m.groupValues[1],
                rawValue = m.groupValues[2].trim().trimEnd(';').trim(),
                kind = TokenKind.CSS_CUSTOM_PROPERTY,
                filePath = path,
                offset = m.range.first,
            )
        }
        // TS/JS preset files: parse top-level object literals and emit a token
        // per leaf path → string value.
        if (file.extension?.lowercase() in JS_EXTS) {
            for (leaf in JsObjectTokenParser.parse(text)) {
                sink += RawToken(
                    name = leaf.path,
                    rawValue = leaf.value,
                    kind = TokenKind.JS_OBJECT_PATH,
                    filePath = path,
                    offset = leaf.offset,
                )
            }
        }
    }

    private fun resolve(raw: List<RawToken>): List<DesignToken> {
        // Group every raw declaration by name, preserving source order. The
        // first occurrence is the primary; the rest become variants tagged with
        // the @media / theme chain in which they were declared.
        // For JS object paths whose name carries a `modeLight` / `modeDark`
        // segment, collapse to the mode-stripped canonical name so sibling mode
        // declarations show up as variants of one logical token.
        val grouped = LinkedHashMap<String, MutableList<RawToken>>()
        for (token in raw) {
            val key = if (token.kind == TokenKind.JS_OBJECT_PATH) {
                TokenNameParser.stripModeSegment(token.name) ?: token.name
            } else token.name
            grouped.getOrPut(key) { mutableListOf() } += token
        }

        // Index used by alias resolution — uses the primary value.
        val firstByName = LinkedHashMap<String, RawToken>()
        for ((name, list) in grouped) firstByName[name] = list.first()

        // Cache file texts so we can run DeclarationContext without re-reading.
        val textCache = HashMap<String, CharSequence>()
        fun textOf(path: String): CharSequence = textCache.getOrPut(path) {
            try {
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
                vf?.let { com.intellij.openapi.vfs.VfsUtilCore.loadText(it) } ?: ""
            } catch (_: Exception) { "" }
        }

        return grouped.entries.map { (canonicalName, group) ->
            val primary = group.first()
            val resolved = resolveValue(primary.rawValue, firstByName, mutableSetOf(primary.name))
            val variants = buildVariants(primary, group, ::textOf, firstByName)
            val primaryLabel = if (primary.kind == TokenKind.JS_OBJECT_PATH)
                TokenNameParser.modeSegmentOf(primary.name)
            else null
            DesignToken(
                name = canonicalName,
                rawValue = primary.rawValue,
                resolvedValue = resolved,
                category = TokenCategorizer.categorize(canonicalName, resolved),
                kind = primary.kind,
                filePath = primary.filePath,
                offset = primary.offset,
                variants = variants,
                primaryConditionLabel = primaryLabel,
            )
        }
    }

    private fun buildVariants(
        primary: RawToken,
        group: List<RawToken>,
        textOf: (String) -> CharSequence,
        firstByName: Map<String, RawToken>,
    ): List<TokenVariant> {
        val isJsMode = primary.kind == TokenKind.JS_OBJECT_PATH &&
            TokenNameParser.modeSegmentOf(primary.name) != null
        val list = if (isJsMode) {
            // Light/dark presets keep every mode declaration in the same file
            // (different sibling paths). Promote *non-primary* modes to variants
            // with a human-friendly condition (`dark`), and resolve aliases so
            // the dashboard's variant table renders the correct hex preview.
            // The primary's own mode becomes the first column's header via
            // [DesignToken.primaryConditionLabel] — we don't repeat it here.
            group.drop(1).map { token ->
                val mode = TokenNameParser.modeSegmentOf(token.name) ?: "(top level)"
                val resolvedVariant = resolveValue(token.rawValue, firstByName, mutableSetOf(token.name))
                TokenVariant(condition = mode, value = resolvedVariant)
            }
        } else {
            group.drop(1).map { other ->
                val condition = DeclarationContext.describeAt(textOf(other.filePath), other.offset)
                TokenVariant(
                    condition = condition.ifBlank { "(top level)" },
                    value = other.rawValue,
                )
            }
        }
        return list.distinctBy { it.condition + "|" + it.value }
    }

    private fun resolveValue(
        value: String,
        index: Map<String, RawToken>,
        seen: MutableSet<String>,
    ): String {
        val scssAlias = SCSS_ALIAS_REGEX.matchEntire(value)
        if (scssAlias != null) {
            val ref = "\$" + scssAlias.groupValues[1]
            if (seen.add(ref)) {
                index[ref]?.let { return resolveValue(it.rawValue, index, seen) }
            }
        }
        val cssAlias = CSS_VAR_CALL_REGEX.matchEntire(value)
        if (cssAlias != null) {
            val ref = "--" + cssAlias.groupValues[1]
            if (seen.add(ref)) {
                index[ref]?.let { return resolveValue(it.rawValue, index, seen) }
            }
        }
        // JS/TS object-path alias: `{global.modeLight.high.surface.default}`
        val jsAlias = JS_OBJECT_ALIAS_REGEX.matchEntire(value)
        if (jsAlias != null) {
            val ref = jsAlias.groupValues[1]
            if (seen.add(ref)) {
                index[ref]?.let { return resolveValue(it.rawValue, index, seen) }
                // The index is keyed by *canonical* names (mode segment removed),
                // but aliases reference the *original* path. Strip the mode
                // segment and retry before falling back to suffix matching.
                TokenNameParser.stripModeSegment(ref)?.let { canonical ->
                    if (seen.add(canonical)) {
                        index[canonical]?.let { return resolveValue(it.rawValue, index, seen) }
                    }
                }
                // Lead-segment strip: `JsObjectTokenParser` does NOT prefix
                // emitted paths with the `export const NAME` identifier, so an
                // alias `{primitive.neutral.700}` whose target file is
                // `export const primitive = { neutral: { 700: '#fff' } }` ends
                // up indexed under `neutral.700`. Drop leading segments until
                // we find a match.
                val segs = ref.split('.')
                for (skip in 1 until segs.size) {
                    val sub = segs.drop(skip).joinToString(".")
                    if (seen.add(sub)) {
                        index[sub]?.let { return resolveValue(it.rawValue, index, seen) }
                    }
                }
                // Fallback: PrimeUIX / Style-Dictionary presets sometimes alias
                // a *shorter* path that addresses the leaf via the last few
                // segments only. Look up the first token whose name ends with
                // the alias segments.
                jsAliasSuffixMatch(ref, index)?.let { return resolveValue(it.rawValue, index, seen) }
            }
        }
        return value
    }

    private fun jsAliasSuffixMatch(ref: String, index: Map<String, RawToken>): RawToken? {
        val needle = ".$ref"
        return index.entries.firstOrNull { (k, _) -> k.endsWith(needle) || k == ref }?.value
    }

    private data class RawToken(
        val name: String,
        val rawValue: String,
        val kind: TokenKind,
        val filePath: String,
        val offset: Int,
    )

    companion object {
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private val SCSS_EXTS = setOf("scss", "sass")
        private val JS_EXTS = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")
        private val TARGET_EXTENSIONS = listOf("scss", "sass", "css", "ts", "tsx", "js", "jsx", "mjs", "cjs")
        private val TARGET_EXTENSIONS_SET = TARGET_EXTENSIONS.toSet()

        private val SCSS_VAR_REGEX = Regex(
            "(?m)^\\s*\\$([A-Za-z_][A-Za-z0-9_-]*)\\s*:\\s*([^;\\n]+)\\s*;?"
        )
        private val CSS_VAR_REGEX = Regex(
            "--([A-Za-z_][A-Za-z0-9_-]*)\\s*:\\s*([^;}\\n]+)\\s*;?"
        )
        // SCSS map keys: `"<token-name>": <value>,` where token names are
        // lowercase-hyphenated. The trailing comma keeps the pattern specific
        // enough to avoid false positives on arbitrary quoted strings.
        private val SCSS_MAP_KEY_REGEX = Regex(
            "\"([a-z][a-z0-9_-]*)\"\\s*:\\s*([^,\\n}]+),"
        )
        private val SCSS_ALIAS_REGEX = Regex("^\\$([A-Za-z_][A-Za-z0-9_-]*)$")
        private val CSS_VAR_CALL_REGEX = Regex("^var\\(\\s*--([A-Za-z_][A-Za-z0-9_-]*)\\s*\\)$")
        // JS/TS Style-Dictionary-style alias: `{a.b.c}` referring to another token path.
        private val JS_OBJECT_ALIAS_REGEX = Regex("^\\{([A-Za-z_][A-Za-z0-9_.-]*)\\}$")

        fun getInstance(project: Project): TokenScanner =
            project.getService(TokenScanner::class.java)
    }
}
