package com.example.callguardian.screening

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the Unprocessed / pending AI queue when the app is RESUMED, and
 * cancels the drain immediately on pause so Nano is never called from the
 * background. Serialized with backoff inside [ScreeningEngine.drainUnprocessed].
 */
@Singleton
class UnprocessedDrain @Inject constructor(
    private val engine: ScreeningEngine,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun attach() {
        runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
            .onFailure { Timber.w("Could not observe process lifecycle for the unprocessed queue") }
        if (AppForeground.isResumed) startDrain()
    }

    override fun onResume(owner: LifecycleOwner) = startDrain()

    override fun onPause(owner: LifecycleOwner) {
        job?.cancel()
        job = null
    }

    private fun startDrain() {
        job?.cancel()
        job = scope.launch {
            runCatching { engine.drainUnprocessed() }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) Timber.w(it, "Unprocessed drain stopped") }
        }
    }
}
