package fr.fsh.tokendesigner.settings

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Pre-built mapping from a npm package name (or family pattern) to the
 * CSS-custom-property prefix the framework injects at runtime. Used to
 * auto-populate [Scope.externalPrefixes] so users don't have to remember
 * which framework owns `--p-`, `--ion-`, `--mat-`, etc.
 *
 * Match is on dependency presence (any non-empty version pin in `dependencies`
 * or `devDependencies` of a `package.json` somewhere in the project tree).
 * The detector reads up to a few package.json files — enough to cover both
 * a flat project and a Nx/Turbo monorepo without blowing up on huge trees.
 */
object FrameworkPrefixDetector {

    /**
     * Known framework → prefix associations. Order matters only for the
     * display (first match wins as the "Detected:" label).
     */
    private val KNOWN: List<Framework> = listOf(
        Framework("PrimeNG", "--p-", listOf("primeng", "primereact", "primevue", "@primeuix/themes")),
        Framework("Ionic", "--ion-", listOf("@ionic/core", "@ionic/angular", "@ionic/react", "@ionic/vue")),
        Framework("Angular Material", "--mat-", listOf("@angular/material")),
        Framework("Material Web Components", "--mdc-", listOf("@material/web", "@material/all", "material-components-web")),
        Framework("Vuetify", "--v-", listOf("vuetify")),
        Framework("Bootstrap (CSS vars)", "--bs-", listOf("bootstrap")),
        Framework("shadcn / Radix", "--radix-", listOf("@radix-ui/themes")),
        Framework("Quasar", "--q-", listOf("quasar")),
        Framework("Element Plus", "--el-", listOf("element-plus")),
        Framework("Mantine", "--mantine-", listOf("@mantine/core")),
        Framework("Carbon Design System", "--cds-", listOf("@carbon/styles", "@carbon/react")),
    )

    data class Framework(val displayName: String, val prefix: String, val packages: List<String>)
    data class Detection(val framework: Framework, val sourceFile: VirtualFile)

    /**
     * Walks the project tree looking for `package.json` files (up to
     * [maxFiles]), parses each, and returns every framework whose package(s)
     * appear in `dependencies` / `devDependencies` / `peerDependencies`.
     * `node_modules` and other heavy folders are skipped so a monorepo with
     * dozens of `package.json` doesn't choke the scan.
     */
    fun detect(project: Project, maxFiles: Int = 12): List<Detection> {
        val base = project.basePath ?: return emptyList()
        val root = LocalFileSystem.getInstance().findFileByPath(base) ?: return emptyList()
        val packageJsons = mutableListOf<VirtualFile>()

        VfsUtilCore.iterateChildrenRecursively(
            root,
            { vf -> vf == root || (vf.isDirectory && vf.name !in SKIP_DIRS) || !vf.isDirectory },
            { vf ->
                if (!vf.isDirectory && vf.name == "package.json") {
                    packageJsons += vf
                    packageJsons.size < maxFiles
                } else true
            },
        )

        if (packageJsons.isEmpty()) return emptyList()

        val seen = mutableSetOf<String>()
        val out = mutableListOf<Detection>()
        for (pkg in packageJsons) {
            val deps = readDeps(pkg)
            if (deps.isEmpty()) continue
            for (fw in KNOWN) {
                if (fw.prefix in seen) continue
                if (fw.packages.any { it in deps }) {
                    out += Detection(fw, pkg)
                    seen += fw.prefix
                }
            }
        }
        return out
    }

    /**
     * Reads `dependencies`, `devDependencies` and `peerDependencies` keys
     * from [packageJson]. Returns an empty set on any parse error — we
     * don't want a broken package.json to crash the IDE startup notification.
     */
    private fun readDeps(packageJson: VirtualFile): Set<String> {
        return try {
            val text = VfsUtilCore.loadText(packageJson)
            val obj = JsonParser.parseString(text).asJsonObject
            val out = mutableSetOf<String>()
            for (key in listOf("dependencies", "devDependencies", "peerDependencies")) {
                val section = obj.getAsJsonObject(key) ?: continue
                section.keySet().forEach { out += it }
            }
            out
        } catch (_: Throwable) {
            emptySet()
        }
    }

    /**
     * Directories large enough to dominate the recursive scan and unlikely
     * to host an authored `package.json`. `node_modules` is the obvious one,
     * but `.git`, build outputs and IDE folders waste just as much time on
     * a cold filesystem.
     */
    private val SKIP_DIRS = setOf(
        "node_modules", ".git", ".idea", ".vscode", "dist", "build",
        "target", "out", "coverage", ".next", ".nuxt", ".turbo", ".cache",
    )
}
