package fr.fsh.tokendesigner.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Icon catalogue for the Token Flow plugin. The user can pick which variant
 * shows up on the tool-window stripe (and other surfaces) from the settings.
 *
 * PNG sources live under `src/main/resources/icons/`. JetBrains' [IconLoader]
 * handles classpath lookup + retina (@2x) variants.
 */
enum class IconVariant(val displayName: String, val resourcePath: String) {
    /**
     * Auto-switching variant. Resolves to `icon-tokenflow-default.png` under a
     * light UI theme and `icon-tokenflow-default_dark.png` under a dark theme
     * — IntelliJ's [IconLoader] picks the `_dark` companion automatically when
     * one exists alongside the base resource.
     */
    DEFAULT("Default (auto light/dark)", "/icons/icon-tokenflow-default.png"),
    ORANGE("Orange", "/icons/icon-tokenflow-orange.png"),
    ARC("Arc", "/icons/icon-tokenflow-arc.png");

    fun load(): Icon = IconLoader.getIcon(resourcePath, IconVariant::class.java)

    /** Scaled version of the icon, for previews in the settings combo. */
    fun loadScaled(targetPx: Int): Icon {
        // Pick the dark companion when the IDE is on a dark theme so the
        // settings preview always matches what the user will see in the strip.
        val path = if (!com.intellij.ui.JBColor.isBright()) {
            resourcePath.replace(".png", "_dark.png")
        } else resourcePath
        val raw = javaClass.getResourceAsStream(path)
            ?: javaClass.getResourceAsStream(resourcePath)
            ?: return load()
        val image = javax.imageio.ImageIO.read(raw) ?: return load()
        val scale = com.intellij.util.ui.JBUI.scale(targetPx)
        val scaled = image.getScaledInstance(scale, scale, java.awt.Image.SCALE_SMOOTH)
        return javax.swing.ImageIcon(scaled)
    }

    companion object {
        fun fromName(name: String?): IconVariant {
            // Migrate legacy values (`WHITE`, `BLACK`) — both fold into the new
            // theme-aware DEFAULT so no settings reset is required.
            if (name == "WHITE" || name == "BLACK") return DEFAULT
            return entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }
}
