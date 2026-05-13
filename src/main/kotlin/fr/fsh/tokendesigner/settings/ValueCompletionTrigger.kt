package fr.fsh.tokendesigner.settings

/**
 * User-facing options for controlling when value-based token completion
 * triggers.  Each entry maps to a [minChars] threshold stored in
 * [TokenSelectorSettings.valueCompletionMinChars].
 */
enum class ValueCompletionTrigger(
    val minChars: Int,
    private val label: String,
) {
    IMMEDIATELY(0, "Immediately after \":\""),
    AFTER_1(1, "After 1 character  (e.g. padding: 4…)"),
    AFTER_2(2, "After 2 characters (e.g. color: #F…)"),
    AFTER_3(3, "After 3 characters (e.g. color: #FF…)"),
    ;

    override fun toString(): String = label

    companion object {
        /** Maps a persisted [minChars] value back to the closest enum entry. */
        fun fromMinChars(n: Int): ValueCompletionTrigger =
            entries.lastOrNull { it.minChars <= n } ?: IMMEDIATELY
    }
}
