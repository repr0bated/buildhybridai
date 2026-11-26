package com.example.callguardian.screening

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * AICore (Gemini Nano) is only allowed while the process is at least
 * [Lifecycle.State.RESUMED] — started-but-not-resumed (receivers, a paused
 * activity, process start) still counts as background and raises ErrorCode 9.
 *
 * [override] is for unit tests only; never set it from production code.
 */
object AppForeground {
    @Volatile
    var override: Boolean? = null

    val isResumed: Boolean
        get() = override ?: runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }.getOrDefault(false)

    /** Alias of [isResumed]. Prefer [isResumed] at new call sites. */
    val isForeground: Boolean get() = isResumed
}
