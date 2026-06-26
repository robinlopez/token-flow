package fr.fsh.tokendesigner.settings

import java.awt.event.InputEvent
import java.awt.event.MouseEvent

/**
 * The platform's primary modifier mask — ⌘ on macOS, Ctrl elsewhere. Declared
 * at file level (not in the companion) so it is safe to reference from the enum
 * constant initializers, which run before the companion object is set up.
 */
private fun primaryMask(): Int =
    if (System.getProperty("os.name").lowercase().contains("mac"))
        InputEvent.META_DOWN_MASK
    else
        InputEvent.CTRL_DOWN_MASK

/**
 * Modifier combo that, together with a left-click on a token reference, opens
 * the "copy resolved value" dropdown (issue #27). Configurable from the plugin
 * settings; the default is [CMD_SHIFT], chosen because plain Cmd/Ctrl+Click is
 * go-to-declaration and Alt+Click is the IDE's multi-caret gesture, whereas the
 * +Shift variant is free on every standard keymap.
 *
 * [CMD_SHIFT] maps to the platform's primary modifier (⌘ on macOS, Ctrl
 * elsewhere) so the gesture feels native everywhere.
 */
enum class CopyClickShortcut(val label: String, private val mask: Int) {
    CMD_SHIFT("⌘/Ctrl + Shift + Click", primaryMask() or InputEvent.SHIFT_DOWN_MASK),
    CTRL_SHIFT("Ctrl + Shift + Click", InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
    CTRL_ALT("Ctrl + Alt + Click", InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK),
    ALT_SHIFT_CLICK("Alt + Shift + Click", InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
    ALT_CLICK("Alt + Click", InputEvent.ALT_DOWN_MASK);

    /**
     * True when [e] is a left-button press carrying exactly this combo's
     * modifiers (no extra modifiers, so e.g. Cmd+Shift doesn't also fire for
     * Cmd+Shift+Alt).
     */
    fun matches(e: MouseEvent): Boolean {
        if (e.button != MouseEvent.BUTTON1) return false
        return (e.modifiersEx and MODIFIER_BITS) == mask
    }

    /** Drives the combo-box rendering in the settings panel. */
    override fun toString(): String = label

    companion object {
        /** Bits we consider when matching — keyboard modifiers only, not buttons. */
        private val MODIFIER_BITS = InputEvent.SHIFT_DOWN_MASK or
            InputEvent.CTRL_DOWN_MASK or
            InputEvent.ALT_DOWN_MASK or
            InputEvent.META_DOWN_MASK or
            InputEvent.ALT_GRAPH_DOWN_MASK

        fun fromName(name: String?): CopyClickShortcut =
            entries.firstOrNull { it.name == name } ?: CMD_SHIFT
    }
}
