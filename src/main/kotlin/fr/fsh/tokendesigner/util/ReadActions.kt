package fr.fsh.tokendesigner.util

import com.intellij.openapi.application.ReadAction

/**
 * Synchronous read-action helper. Replaces the deprecated
 * `com.intellij.openapi.application.runReadAction` Kotlin top-level
 * function (which JetBrains plans to remove in favour of `ReadAction.compute`
 * or coroutine-based `readAction`).
 *
 * Throws whatever [block] throws — the underlying `ReadAction.compute` lets any
 * `Throwable` propagate.
 */
inline fun <T> readAction(crossinline block: () -> T): T =
    ReadAction.compute<T, Throwable> { block() }
