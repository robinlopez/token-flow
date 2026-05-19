package fr.fsh.tokendesigner.util

import com.intellij.openapi.application.ApplicationManager

/**
 * Synchronous read-action helper.
 *
 * `ReadAction.compute(ThrowableComputable)` is deprecated on 2024.2+ — the
 * plugin verifier flags it as "may be removed in future releases". The
 * canonical non-deprecated replacement for a *blocking* read action is
 * `ApplicationManager.getApplication().runReadAction { … }`, which is what
 * we delegate to here. Coroutine-based callers should reach for
 * `com.intellij.openapi.application.readAction { … }` instead.
 *
 * Throws whatever [block] throws — exceptions raised inside the read action
 * are captured and re-thrown after the lambda returns so the caller sees
 * the original stack trace.
 */
inline fun <T> readAction(crossinline block: () -> T): T {
    var result: T? = null
    var thrown: Throwable? = null
    ApplicationManager.getApplication().runReadAction {
        try {
            result = block()
        } catch (t: Throwable) {
            thrown = t
        }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
