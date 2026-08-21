package com.yighy.pantograph.drawing

import com.yighy.pantograph.data.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * A brush setting that lives in two places at once: on every preset, and as a global preference
 * that is mirrored back into [DrawingState].
 *
 * The duplication is deliberate - these carry over between projects - but it comes with a trap.
 * Because the preference is mirrored *into* the state, one left holding another preset's value
 * is not merely unsaved: it is restored over the brush in hand the next time anything re-emits
 * it. Loading a brush with no scatter jitter used to bring back the scatter of whichever preset
 * had written the global last, and it took a colour change to trigger, because writing the
 * colour history was what made the preferences re-emit.
 *
 * That bug was possible because the list of these settings was written out by hand in two
 * places - the observers on one side, the write-back on the other - with nothing tying them
 * together. Now there is one list and both sides walk it, so a setting added later cannot be
 * mirrored without also being written back.
 *
 * @param stateField the [DrawingState] property this owns. Names it for the tests, which check
 * that [applyTo] really does write the field the descriptor claims.
 */
sealed class MirroredBrushSetting<T>(val label: String, val stateField: String) {

    /** The preference this setting is stored in. */
    abstract fun flow(preferences: PreferenceManager): Flow<T>

    /** Writes [value] to the preference. */
    abstract suspend fun persist(preferences: PreferenceManager, value: T)

    /** This setting's value on a saved preset. */
    abstract fun readFrom(brush: BrushConfig): T

    /** [state] with this setting set to [value]. */
    abstract fun applyTo(state: DrawingState, value: T): DrawingState

    /** Mirrors the stored preference into the session for as long as [scope] lives. */
    fun observe(preferences: PreferenceManager, session: DrawingSession, scope: CoroutineScope) {
        flow(preferences)
            .onEach { value -> session.update { applyTo(it, value) } }
            .launchIn(scope)
    }

    /** Pushes [brush]'s value out to the preference, so the global stops disagreeing with it. */
    suspend fun pushToPreference(preferences: PreferenceManager, brush: BrushConfig) =
        persist(preferences, readFrom(brush))

    // ============================ Jitters ============================

    data object RotationJitter : MirroredBrushSetting<Float>("Rotation jitter", "brushRotationJitter") {
        override fun flow(preferences: PreferenceManager) = preferences.rotationJitter
        override suspend fun persist(preferences: PreferenceManager, value: Float) = preferences.setRotationJitter(value)
        override fun readFrom(brush: BrushConfig) = brush.rotationJitter
        override fun applyTo(state: DrawingState, value: Float) = state.copy(brushRotationJitter = value)
    }

    data object ScatterJitter : MirroredBrushSetting<Float>("Scatter jitter", "scatterJitter") {
        override fun flow(preferences: PreferenceManager) = preferences.scatterJitter
        override suspend fun persist(preferences: PreferenceManager, value: Float) = preferences.setScatterJitter(value)
        override fun readFrom(brush: BrushConfig) = brush.scatterJitter
        override fun applyTo(state: DrawingState, value: Float) = state.copy(scatterJitter = value)
    }

    data object FlowJitter : MirroredBrushSetting<Float>("Flow jitter", "flowJitter") {
        override fun flow(preferences: PreferenceManager) = preferences.flowJitter
        override suspend fun persist(preferences: PreferenceManager, value: Float) = preferences.setFlowJitter(value)
        override fun readFrom(brush: BrushConfig) = brush.flowJitter
        override fun applyTo(state: DrawingState, value: Float) = state.copy(flowJitter = value)
    }

    data object RotationFollow : MirroredBrushSetting<Float>("Follow direction", "rotationFollow") {
        override fun flow(preferences: PreferenceManager) = preferences.rotationFollow
        override suspend fun persist(preferences: PreferenceManager, value: Float) = preferences.setRotationFollow(value)
        override fun readFrom(brush: BrushConfig) = brush.rotationFollow
        override fun applyTo(state: DrawingState, value: Float) = state.copy(rotationFollow = value)
    }

    // ============================ Velocity dynamics ============================

    data object VelocityEnabled : MirroredBrushSetting<Boolean>("Velocity dynamics", "velocityEnabled") {
        override fun flow(preferences: PreferenceManager) = preferences.velocityEnabled
        override suspend fun persist(preferences: PreferenceManager, value: Boolean) = preferences.setVelocityEnabled(value)
        override fun readFrom(brush: BrushConfig) = brush.velocityEnabled
        override fun applyTo(state: DrawingState, value: Boolean) = state.copy(velocityEnabled = value)
    }

    data object VelocitySize : MirroredBrushSetting<Float>("Velocity size", "velocitySizeAmount") {
        override fun flow(preferences: PreferenceManager) = preferences.velocitySize
        override suspend fun persist(preferences: PreferenceManager, value: Float) = preferences.setVelocitySize(value)
        override fun readFrom(brush: BrushConfig) = brush.velocitySize
        override fun applyTo(state: DrawingState, value: Float) = state.copy(velocitySizeAmount = value)
    }

    data object VelocityFlow : MirroredBrushSetting<Float>("Velocity flow", "velocityFlowAmount") {
        override fun flow(preferences: PreferenceManager) = preferences.velocityFlow
        override suspend fun persist(preferences: PreferenceManager, value: Float) = preferences.setVelocityFlow(value)
        override fun readFrom(brush: BrushConfig) = brush.velocityFlow
        override fun applyTo(state: DrawingState, value: Float) = state.copy(velocityFlowAmount = value)
    }

    data object VelocityScatter : MirroredBrushSetting<Float>("Velocity scatter", "velocityScatterAmount") {
        override fun flow(preferences: PreferenceManager) = preferences.velocityScatter
        override suspend fun persist(preferences: PreferenceManager, value: Float) = preferences.setVelocityScatter(value)
        override fun readFrom(brush: BrushConfig) = brush.velocityScatter
        override fun applyTo(state: DrawingState, value: Float) = state.copy(velocityScatterAmount = value)
    }

    companion object {
        /** Every setting mirrored between a preference and the brush in hand. */
        val ALL: List<MirroredBrushSetting<*>> = listOf(
            RotationJitter, ScatterJitter, FlowJitter, RotationFollow,
            VelocityEnabled, VelocitySize, VelocityFlow, VelocityScatter
        )

        /** Starts every mirror. Called once, from the ViewModel's settings observers. */
        fun observeAll(preferences: PreferenceManager, session: DrawingSession, scope: CoroutineScope) {
            ALL.forEach { it.observe(preferences, session, scope) }
        }

        /** Writes every one of [brush]'s values back out, so no global is left disagreeing. */
        suspend fun pushAll(preferences: PreferenceManager, brush: BrushConfig) {
            ALL.forEach { it.pushToPreference(preferences, brush) }
        }
    }
}
