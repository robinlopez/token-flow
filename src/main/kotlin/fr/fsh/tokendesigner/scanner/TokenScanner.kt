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
import fr.fsh.tokendesigner.scanner.parsers.JsTokenFileParserRegistry
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
        val ext = file.extension?.lowercase()
        val path = file.path

        when (ext) {
            "vue" -> extractFromVue(text, path, sink)
            else -> {
                val isScss = ext in SCSS_EXTS
                extractCssLike(text, isScss, path, blockOffset = 0, sink)
            }
        }
        // TS/JS files: dispatch to the right parser (Style-Dictionary preset
        // vs. runtime object theme). The registry picks one strategy per file
        // so the index stays free of cross-flavour duplicates. For runtime
        // files we also extract callable helpers (`spacing`, `radius`, …) so
        // they show up in the library and feed helper-aware suggestions.
        if (ext in JS_EXTS) {
            extractJsLike(text, path, sink)
        }
    }

    /**
     * Vue SFC: only the contents of `<style …>` blocks are eligible for
     * token detection. Each block is scanned with the CSS or SCSS regex set
     * depending on its `lang` attribute, and emitted offsets are translated
     * back to file-absolute coordinates so the locator / gutter swatches
     * still navigate to the correct line inside the .vue file.
     */
    private fun extractFromVue(text: CharSequence, path: String, sink: MutableList<RawToken>) {
        for (block in VueStyleBlockExtractor.extract(text)) {
            // `src="…"` blocks point at an external file — that file is
            // scanned by the normal extension walker, so skipping here avoids
            // a duplicate emission with broken offsets.
            if (block.src != null || block.text.isEmpty()) continue
            val isScss = block.lang in SCSS_BLOCK_LANGS
            extractCssLike(block.text, isScss, path, block.startOffset, sink)
        }
    }

    private fun extractCssLike(
        text: CharSequence,
        isScss: Boolean,
        path: String,
        blockOffset: Int,
        sink: MutableList<RawToken>,
    ) {
        if (isScss) {
            for (m in SCSS_VAR_REGEX.findAll(text)) {
                val raw = m.groupValues[2].trim().trimEnd(';').trim()
                val cleanedRaw = raw.replace(Regex("(?i)!(default|global|important)\\s*$"), "").trim()
                sink += RawToken(
                    name = "\$" + m.groupValues[1],
                    rawValue = cleanedRaw,
                    kind = TokenKind.SCSS_VARIABLE,
                    filePath = path,
                    offset = m.range.first + blockOffset,
                )
            }
            for (m in SCSS_MAP_KEY_REGEX.findAll(text)) {
                sink += RawToken(
                    name = "--" + m.groupValues[1],
                    rawValue = m.groupValues[2].trim(),
                    kind = TokenKind.CSS_CUSTOM_PROPERTY,
                    filePath = path,
                    offset = m.range.first + blockOffset,
                )
            }
        }
        for (m in CSS_VAR_REGEX.findAll(text)) {
            val raw = m.groupValues[2].trim().trimEnd(';').trim()
            val cleanedRaw = raw.replace(Regex("(?i)!important\\s*$"), "").trim()
            sink += RawToken(
                name = "--" + m.groupValues[1],
                rawValue = cleanedRaw,
                kind = TokenKind.CSS_CUSTOM_PROPERTY,
                filePath = path,
                offset = m.range.first + blockOffset,
            )
        }
    }

    private fun extractJsLike(text: CharSequence, path: String, sink: MutableList<RawToken>) {
        val parsed = JsTokenFileParserRegistry.parseFull(text)
        val kind = JsTokenFileParserRegistry.parserFor(parsed.mode).kind
        for (leaf in parsed.leaves) {
            sink += RawToken(
                name = leaf.path,
                rawValue = leaf.value,
                kind = kind,
                filePath = path,
                offset = leaf.offset,
            )
        }
        for (helper in parsed.helpers) {
            sink += RawToken(
                name = helper.name,
                rawValue = "${helper.unitSource} × ${helper.paramName}",
                kind = TokenKind.JS_RUNTIME_FUNCTION,
                filePath = path,
                offset = helper.offset,
                functionUnit = helper.unit,
            )
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
            // The primary token's "default" column header is replaced with its
            // own declaration context whenever that's meaningful — so that an
            // SCSS map nested under `$themes-config -> "themeOne" -> "light"`
            // surfaces "themeOne light" on the primary just like its siblings,
            // letting `VariantTableHtml` group columns by theme. JS preset
            // tokens keep their dedicated mode-segment label (`light`/`dark`
            // pulled from the path), since their context is the surrounding
            // object literal rather than a CSS / SCSS chain.
            val primaryLabel = when (primary.kind) {
                TokenKind.JS_OBJECT_PATH -> TokenNameParser.modeSegmentOf(primary.name)
                // Runtime tokens are flat property accesses — no light/dark
                // sibling paths and no surrounding `@media`/theme chain to
                // surface, so the default "default" column header is fine.
                TokenKind.JS_RUNTIME_PROPERTY -> null
                TokenKind.JS_RUNTIME_FUNCTION -> null
                TokenKind.CSS_CUSTOM_PROPERTY,
                TokenKind.SCSS_VARIABLE -> DeclarationContext
                    .describeAt(textOf(primary.filePath), primary.offset)
                    .takeIf { it.isNotBlank() }
            }
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
                functionUnit = primary.functionUnit,
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
        // Runtime property-access alias: `colors.PRIMARY_500` — a bare JS
        // identifier expression. Common in React-Native themes where one
        // typed object (`nomTheme`) reuses values from a primitive object
        // (`colors`). The alias is the value verbatim, no braces.
        val runtimeAlias = JS_RUNTIME_ALIAS_REGEX.matchEntire(value)
        if (runtimeAlias != null) {
            val ref = runtimeAlias.value
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
        val functionUnit: Double? = null,
    )

    companion object {
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private val SCSS_EXTS = setOf("scss", "sass")
        private val JS_EXTS = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")
        /** `<style lang="…">` values that should run the SCSS regex set in addition to CSS. */
        private val SCSS_BLOCK_LANGS = setOf("scss", "sass")
        private val TARGET_EXTENSIONS = listOf("scss", "sass", "css", "vue", "ts", "tsx", "js", "jsx", "mjs", "cjs")
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
        // Bare runtime property-access alias: `colors.PRIMARY_500`. At least one
        // dot is required so we don't treat a plain identifier as an alias.
        private val JS_RUNTIME_ALIAS_REGEX = Regex(
            "^[A-Za-z_\$][\\w\$]*(?:\\.[A-Za-z_\$0-9][\\w\$]*)+$"
        )

        fun getInstance(project: Project): TokenScanner =
            project.getService(TokenScanner::class.java)
    }
}
