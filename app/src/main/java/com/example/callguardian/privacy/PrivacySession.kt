package com.example.callguardian.privacy

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session unlock for private items. BIOMETRIC_STRONG or the device PIN unlocks until the app
 * goes to the background, or until [TIMEOUT_MS] elapses, whichever is first.
 */
/** Clock-pure session lock. [PrivacySession] binds this to process lifecycle. */
class PrivacyUnlock(val timeoutMs: Long = TIMEOUT_MS) {
    @Volatile var until = 0L
        private set
    @Volatile var unlocked = false
        private set

    fun isUnlocked(now: Long): Boolean {
        if (!unlocked) return false
        if (now >= until) { lock(); return false }
        return true
    }

    fun unlock(now: Long) {
        until = now + timeoutMs
        unlocked = true
    }

    fun lock() {
        until = 0
        unlocked = false
    }

    companion object {
        const val TIMEOUT_MS = 2 * 60 * 1000L
    }
}

@Singleton
class PrivacySession @Inject constructor() : DefaultLifecycleObserver {
    private val gate = PrivacyUnlock()
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    init {
        runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    }

    fun isUnlocked(now: Long = System.currentTimeMillis()): Boolean {
        val ok = gate.isUnlocked(now)
        if (_unlocked.value != ok) _unlocked.value = ok
        return ok
    }

    fun unlock(now: Long = System.currentTimeMillis()) {
        gate.unlock(now)
        _unlocked.value = true
    }

    fun lock() {
        gate.lock()
        _unlocked.value = false
    }

    override fun onStop(owner: LifecycleOwner) { lock() }

    companion object {
        const val TIMEOUT_MS = PrivacyUnlock.TIMEOUT_MS
    }
}
