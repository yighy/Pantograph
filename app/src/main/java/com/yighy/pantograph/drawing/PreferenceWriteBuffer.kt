package com.yighy.pantograph.drawing

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How long a brush setting has to hold still before it is written to the preference store.
 *
 * Long enough that a slider drag costs one write instead of one per frame, short enough that
 * nothing is at risk in the gap: a setting is already live in [DrawingSession] the moment it
 * changes, and the wait only governs when it reaches disk.
 */
private const val PREFERENCE_SETTLE_MS = 250L

/**
 * Settings on their way to the preference store, each written once it has stopped moving.
 *
 * A slider calls its setter once per frame, and each of those was a DataStore edit - which
 * rewrites and fsyncs the whole preference file. Sixty of those a second is work the drag has to
 * share the device with. Writes are filed under the row each one lands in, so the newest value of
 * a setting replaces the pending one instead of queueing behind it, and two settings moved in the
 * same breath can't displace each other.
 *
 * [scope] must outlive the screen - see [flushNow].
 */
class PreferenceWriteBuffer(private val scope: CoroutineScope) {

    private val pending = mutableMapOf<Preferences.Key<*>, suspend () -> Unit>()
    private var flushJob: Job? = null

    /**
     * Holds [persist] until the setting stops moving.
     *
     * [key] is the store's own key for the row the write lands in, rather than a name invented
     * here, which two settings could collide on without anything noticing. The wait restarts on
     * each change rather than ticking, so a slider that has settled is written promptly while one
     * still under the thumb is not written at all.
     */
    fun write(key: Preferences.Key<*>, persist: suspend () -> Unit) {
        synchronized(pending) { pending[key] = persist }
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(PREFERENCE_SETTLE_MS)
            flush()
        }
    }

    /**
     * Writes everything still waiting, without waiting: for leaving the screen, where a setting
     * nudged on the way out has not waited out its delay yet - and these are globals, so the next
     * project would open with the old value.
     */
    fun flushNow() {
        flushJob?.cancel()
        scope.launch { flush() }
    }

    /**
     * NonCancellable because the next change cancels this job to restart the wait: a flush
     * caught halfway through would have emptied the map of writes it never made, and the setting
     * they carried would be gone.
     */
    private suspend fun flush() = withContext(NonCancellable) {
        val due = synchronized(pending) {
            pending.values.toList().also { pending.clear() }
        }
        due.forEach { it() }
    }
}
