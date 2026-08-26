package com.yighy.pantograph.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class AppTheme {
    LIGHT, DARK, SYSTEM
}

class PreferenceManager(private val context: Context) {
    companion object {
        val THEME_KEY = stringPreferencesKey("app_theme")
        val HISTORY_LIMIT_KEY = stringPreferencesKey("history_limit")
        val FAB_X_KEY = stringPreferencesKey("fab_x")
        val FAB_Y_KEY = stringPreferencesKey("fab_y")
        val FAB_DRAG_THRESHOLD_KEY = stringPreferencesKey("fab_drag_threshold")
        val BRUSH_SOFTNESS_KEY = stringPreferencesKey("brush_softness")
        val BRUSH_SMOOTHING_KEY = stringPreferencesKey("brush_smoothing")
        val COLOR_HISTORY_KEY = stringPreferencesKey("color_history")
        val COLOR_PICKER_MODE_KEY = booleanPreferencesKey("color_picker_slider_mode")
        val CURSOR_THICKNESS_KEY = floatPreferencesKey("cursor_thickness")
        val FAB_SIZE_KEY = floatPreferencesKey("fab_size")
        val HIDE_STATUS_BAR_KEY = booleanPreferencesKey("hide_status_bar")
        val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color")
        val FILL_TOLERANCE_KEY = floatPreferencesKey("fill_tolerance")
        val ROTATION_JITTER_KEY = floatPreferencesKey("rotation_jitter")
        val VELOCITY_SIZE_KEY = floatPreferencesKey("velocity_size")
        val VELOCITY_FLOW_KEY = floatPreferencesKey("velocity_flow")
        val VELOCITY_SCATTER_KEY = floatPreferencesKey("velocity_scatter")
        val VELOCITY_ENABLED_KEY = booleanPreferencesKey("velocity_enabled")
        val OFFSCREEN_CURSOR_ARROW_KEY = booleanPreferencesKey("offscreen_cursor_arrow")
        val SATELLITE_GATE_SENSITIVITY_KEY = floatPreferencesKey("satellite_gate_sensitivity")
        val PINNED_TOOL_KEY = stringPreferencesKey("pinned_tool")
        val SCATTER_JITTER_KEY = floatPreferencesKey("scatter_jitter")
        val FLOW_JITTER_KEY = floatPreferencesKey("flow_jitter")
        val ROTATION_FOLLOW_KEY = floatPreferencesKey("rotation_follow")
        val UNDO_RESTORES_CURSOR_KEY = booleanPreferencesKey("undo_restores_cursor")
    }

    // Every flow below ends in distinctUntilChanged, and it is load-bearing rather than tidy.
    // DataStore emits its whole snapshot on any write, so without it a change to one setting
    // re-emits all the others at their unchanged values. Several of these are mirrored into
    // the drawing state, which meant saving one preference quietly overwrote brush settings
    // the user had just loaded from a preset - a brush with no scatter came back with the
    // scatter of whichever preset had set the global last.

    /**
     * Comma-separated names of the tools pinned to the quick-access satellite, or null for an
     * empty slot. Stored raw so this layer stays unaware of the drawing package's enum - the
     * ViewModel resolves it, and unknown names simply drop out.
     */
    val pinnedTools: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PINNED_TOOL_KEY]
    }.distinctUntilChanged()

    val appTheme: Flow<AppTheme> = context.dataStore.data.map { preferences ->
        val themeName = preferences[THEME_KEY] ?: AppTheme.SYSTEM.name
        AppTheme.valueOf(themeName)
    }.distinctUntilChanged()

    val historyLimit: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[HISTORY_LIMIT_KEY]?.toIntOrNull() ?: 5
    }.distinctUntilChanged()

    val fabPosition: Flow<Pair<Float, Float>> = context.dataStore.data.map { preferences ->
        val x = preferences[FAB_X_KEY]?.toFloatOrNull() ?: 40f
        val y = preferences[FAB_Y_KEY]?.toFloatOrNull() ?: 500f
        x to y
    }.distinctUntilChanged()

    val fabDragThreshold: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[FAB_DRAG_THRESHOLD_KEY]?.toFloatOrNull() ?: 100f
    }.distinctUntilChanged()

    val brushSoftness: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[BRUSH_SOFTNESS_KEY]?.toFloatOrNull() ?: 0f
    }.distinctUntilChanged()

    val brushSmoothing: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[BRUSH_SMOOTHING_KEY]?.toFloatOrNull() ?: 0.5f
    }.distinctUntilChanged()

    val colorHistory: Flow<List<String>> = context.dataStore.data.map { preferences ->
        preferences[COLOR_HISTORY_KEY]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }.distinctUntilChanged()

    val colorPickerIsSliderMode: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[COLOR_PICKER_MODE_KEY] ?: false
    }.distinctUntilChanged()

    val cursorThickness: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[CURSOR_THICKNESS_KEY] ?: 1.0f
    }.distinctUntilChanged()

    val fabSize: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[FAB_SIZE_KEY] ?: 56f
    }.distinctUntilChanged()

    val hideStatusBar: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HIDE_STATUS_BAR_KEY] ?: true
    }.distinctUntilChanged()

    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DYNAMIC_COLOR_KEY] ?: true
    }.distinctUntilChanged()

    val fillTolerance: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[FILL_TOLERANCE_KEY] ?: 10f
    }.distinctUntilChanged()

    val scatterJitter: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[SCATTER_JITTER_KEY] ?: 0f
    }.distinctUntilChanged()

    val flowJitter: Flow<Float> = context.dataStore.data.map { it[FLOW_JITTER_KEY] ?: 0f }.distinctUntilChanged()
    val rotationFollow: Flow<Float> = context.dataStore.data.map { it[ROTATION_FOLLOW_KEY] ?: 0f }.distinctUntilChanged()

    val rotationJitter: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[ROTATION_JITTER_KEY] ?: 0f
    }.distinctUntilChanged()

    val velocitySize: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[VELOCITY_SIZE_KEY] ?: 0f
    }.distinctUntilChanged()

    val velocityFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[VELOCITY_FLOW_KEY] ?: 0f
    }.distinctUntilChanged()

    val velocityScatter: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[VELOCITY_SCATTER_KEY] ?: 0f
    }.distinctUntilChanged()

    val velocityEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[VELOCITY_ENABLED_KEY] ?: false
    }.distinctUntilChanged()

    val offscreenCursorArrow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[OFFSCREEN_CURSOR_ARROW_KEY] ?: true
    }.distinctUntilChanged()

    val satelliteGateSensitivity: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[SATELLITE_GATE_SENSITIVITY_KEY] ?: 1f
    }.distinctUntilChanged()

    /**
     * Whether undoing a stroke also walks the cursor back to where that stroke started.
     *
     * On by default: the cursor is driven relatively, so its position is earned rather than
     * pointed at, and undo-then-redraw is common enough that re-aiming by hand every time is
     * the bigger annoyance. Off restores the plain behaviour, where only the pixels change.
     */
    val undoRestoresCursor: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[UNDO_RESTORES_CURSOR_KEY] ?: true
    }.distinctUntilChanged()

    suspend fun setAppTheme(theme: AppTheme) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme.name
        }
    }

    suspend fun setHistoryLimit(limit: Int) {
        context.dataStore.edit { preferences ->
            preferences[HISTORY_LIMIT_KEY] = limit.toString()
        }
    }

    suspend fun setFabPosition(x: Float, y: Float) {
        context.dataStore.edit { preferences ->
            preferences[FAB_X_KEY] = x.toString()
            preferences[FAB_Y_KEY] = y.toString()
        }
    }

    suspend fun setFabDragThreshold(threshold: Float) {
        context.dataStore.edit { preferences ->
            preferences[FAB_DRAG_THRESHOLD_KEY] = threshold.toString()
        }
    }

    suspend fun setBrushSoftness(softness: Float) {
        context.dataStore.edit { preferences ->
            preferences[BRUSH_SOFTNESS_KEY] = softness.toString()
        }
    }

    suspend fun setBrushSmoothing(smoothing: Float) {
        context.dataStore.edit { preferences ->
            preferences[BRUSH_SMOOTHING_KEY] = smoothing.toString()
        }
    }

    suspend fun setColorHistory(colors: List<String>) {
        context.dataStore.edit { preferences ->
            preferences[COLOR_HISTORY_KEY] = colors.joinToString(",")
        }
    }

    suspend fun setColorPickerSliderMode(isSlider: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[COLOR_PICKER_MODE_KEY] = isSlider
        }
    }

    suspend fun setCursorThickness(thickness: Float) {
        context.dataStore.edit { preferences ->
            preferences[CURSOR_THICKNESS_KEY] = thickness
        }
    }

    suspend fun setFabSize(size: Float) {
        context.dataStore.edit { preferences ->
            preferences[FAB_SIZE_KEY] = size
        }
    }

    suspend fun setHideStatusBar(hide: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HIDE_STATUS_BAR_KEY] = hide
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR_KEY] = enabled
        }
    }

    suspend fun setFillTolerance(tolerance: Float) {
        context.dataStore.edit { preferences ->
            preferences[FILL_TOLERANCE_KEY] = tolerance
        }
    }

    suspend fun setRotationJitter(jitter: Float) {
        context.dataStore.edit { preferences ->
            preferences[ROTATION_JITTER_KEY] = jitter
        }
    }

    suspend fun setVelocitySize(amount: Float) {
        context.dataStore.edit { preferences ->
            preferences[VELOCITY_SIZE_KEY] = amount
        }
    }

    suspend fun setVelocityFlow(amount: Float) {
        context.dataStore.edit { preferences ->
            preferences[VELOCITY_FLOW_KEY] = amount
        }
    }

    suspend fun setFlowJitter(amount: Float) {
        context.dataStore.edit { it[FLOW_JITTER_KEY] = amount }
    }

    suspend fun setRotationFollow(amount: Float) {
        context.dataStore.edit { it[ROTATION_FOLLOW_KEY] = amount }
    }

    suspend fun setScatterJitter(amount: Float) {
        context.dataStore.edit { preferences ->
            preferences[SCATTER_JITTER_KEY] = amount
        }
    }

    suspend fun setVelocityScatter(amount: Float) {
        context.dataStore.edit { preferences ->
            preferences[VELOCITY_SCATTER_KEY] = amount
        }
    }

    suspend fun setVelocityEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VELOCITY_ENABLED_KEY] = enabled
        }
    }

    suspend fun setOffscreenCursorArrow(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[OFFSCREEN_CURSOR_ARROW_KEY] = enabled
        }
    }

    suspend fun setUndoRestoresCursor(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[UNDO_RESTORES_CURSOR_KEY] = enabled
        }
    }

    suspend fun setSatelliteGateSensitivity(sensitivity: Float) {
        context.dataStore.edit { preferences ->
            preferences[SATELLITE_GATE_SENSITIVITY_KEY] = sensitivity
        }
    }

    suspend fun setPinnedTools(toolNames: String?) {
        context.dataStore.edit { preferences ->
            if (toolNames == null) preferences.remove(PINNED_TOOL_KEY)
            else preferences[PINNED_TOOL_KEY] = toolNames
        }
    }
}
