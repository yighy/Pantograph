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
        val FILL_GROW_KEY = floatPreferencesKey("fill_grow")
        val LOUPE_ZOOM_KEY = floatPreferencesKey("loupe_zoom")
        val DEFAULT_BRUSHES_SEEDED_KEY = booleanPreferencesKey("default_brushes_seeded")
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
        val BRUSH_SIZE_KEY = floatPreferencesKey("brush_size")
        val BRUSH_OPACITY_KEY = floatPreferencesKey("brush_opacity")
        val BRUSH_FLOW_KEY = floatPreferencesKey("brush_flow")
        val BRUSH_SPACING_KEY = floatPreferencesKey("brush_spacing")
        val BRUSH_ROTATION_KEY = floatPreferencesKey("brush_rotation")
        val SIZE_JITTER_KEY = floatPreferencesKey("size_jitter")
        val SIZE_MULTIPLIER_KEY = floatPreferencesKey("size_multiplier")
        val BRUSH_TIP_URI_KEY = stringPreferencesKey("brush_tip_uri")
        val BRUSH_TEXTURE_URI_KEY = stringPreferencesKey("brush_texture_uri")
        val TIP_SHAPE_KEY = stringPreferencesKey("tip_shape")
        val TIP_RATIO_KEY = floatPreferencesKey("tip_ratio")
        val ANTI_ALIAS_KEY = booleanPreferencesKey("anti_alias")
        val HUE_JITTER_KEY = floatPreferencesKey("hue_jitter")
        val SATURATION_JITTER_KEY = floatPreferencesKey("saturation_jitter")
        val VALUE_JITTER_KEY = floatPreferencesKey("value_jitter")
        val SMUDGE_KEY = floatPreferencesKey("smudge")
        val SMUDGE_LENGTH_KEY = floatPreferencesKey("smudge_length")
        val KEEP_UNDONE_STROKES_KEY = booleanPreferencesKey("keep_undone_strokes")
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

    // The rest of the brush. These follow you between projects for the same reason the jitters
    // always did: they describe the brush in hand, not the drawing. Defaults match BrushConfig,
    // which is what a preset restores when you load one.

    val brushSize: Flow<Float> = context.dataStore.data.map { it[BRUSH_SIZE_KEY] ?: 20f }.distinctUntilChanged()
    val brushOpacity: Flow<Float> = context.dataStore.data.map { it[BRUSH_OPACITY_KEY] ?: 1f }.distinctUntilChanged()
    val brushFlow: Flow<Float> = context.dataStore.data.map { it[BRUSH_FLOW_KEY] ?: 1f }.distinctUntilChanged()
    val brushSpacing: Flow<Float> = context.dataStore.data.map { it[BRUSH_SPACING_KEY] ?: 0.1f }.distinctUntilChanged()
    val brushRotation: Flow<Float> = context.dataStore.data.map { it[BRUSH_ROTATION_KEY] ?: 0f }.distinctUntilChanged()
    val sizeJitter: Flow<Float> = context.dataStore.data.map { it[SIZE_JITTER_KEY] ?: 0f }.distinctUntilChanged()
    val sizeMultiplier: Flow<Float> = context.dataStore.data.map { it[SIZE_MULTIPLIER_KEY] ?: 1f }.distinctUntilChanged()

    /** Null means the built-in round tip; same for [brushTextureUri] and no texture. */
    val brushTipUri: Flow<String?> = context.dataStore.data.map { it[BRUSH_TIP_URI_KEY] }.distinctUntilChanged()
    val brushTextureUri: Flow<String?> = context.dataStore.data.map { it[BRUSH_TEXTURE_URI_KEY] }.distinctUntilChanged()

    /** Name of a TipShape; the drawing layer resolves it, and an unknown one reads as Round. */
    val tipShape: Flow<String> = context.dataStore.data.map { it[TIP_SHAPE_KEY] ?: "Round" }.distinctUntilChanged()
    val tipRatio: Flow<Float> = context.dataStore.data.map { it[TIP_RATIO_KEY] ?: 1f }.distinctUntilChanged()

    val antiAlias: Flow<Boolean> = context.dataStore.data.map { it[ANTI_ALIAS_KEY] ?: true }.distinctUntilChanged()

    val hueJitter: Flow<Float> = context.dataStore.data.map { it[HUE_JITTER_KEY] ?: 0f }.distinctUntilChanged()
    val saturationJitter: Flow<Float> = context.dataStore.data.map { it[SATURATION_JITTER_KEY] ?: 0f }.distinctUntilChanged()
    val valueJitter: Flow<Float> = context.dataStore.data.map { it[VALUE_JITTER_KEY] ?: 0f }.distinctUntilChanged()

    val smudge: Flow<Float> = context.dataStore.data.map { it[SMUDGE_KEY] ?: 0f }.distinctUntilChanged()
    val smudgeLength: Flow<Float> = context.dataStore.data.map { it[SMUDGE_LENGTH_KEY] ?: 0.5f }.distinctUntilChanged()

    suspend fun setSmudge(v: Float) { context.dataStore.edit { it[SMUDGE_KEY] = v } }
    suspend fun setSmudgeLength(v: Float) { context.dataStore.edit { it[SMUDGE_LENGTH_KEY] = v } }

    suspend fun setHueJitter(v: Float) { context.dataStore.edit { it[HUE_JITTER_KEY] = v } }
    suspend fun setSaturationJitter(v: Float) { context.dataStore.edit { it[SATURATION_JITTER_KEY] = v } }
    suspend fun setValueJitter(v: Float) { context.dataStore.edit { it[VALUE_JITTER_KEY] = v } }

    suspend fun setAntiAlias(on: Boolean) { context.dataStore.edit { it[ANTI_ALIAS_KEY] = on } }

    suspend fun setTipShape(name: String) { context.dataStore.edit { it[TIP_SHAPE_KEY] = name } }
    suspend fun setTipRatio(value: Float) { context.dataStore.edit { it[TIP_RATIO_KEY] = value } }

    suspend fun setBrushSize(value: Float) { context.dataStore.edit { it[BRUSH_SIZE_KEY] = value } }
    suspend fun setBrushOpacity(value: Float) { context.dataStore.edit { it[BRUSH_OPACITY_KEY] = value } }
    suspend fun setBrushFlow(value: Float) { context.dataStore.edit { it[BRUSH_FLOW_KEY] = value } }
    suspend fun setBrushSpacing(value: Float) { context.dataStore.edit { it[BRUSH_SPACING_KEY] = value } }
    suspend fun setBrushRotation(value: Float) { context.dataStore.edit { it[BRUSH_ROTATION_KEY] = value } }
    suspend fun setSizeJitter(value: Float) { context.dataStore.edit { it[SIZE_JITTER_KEY] = value } }
    suspend fun setSizeMultiplier(value: Float) { context.dataStore.edit { it[SIZE_MULTIPLIER_KEY] = value } }

    suspend fun setBrushTipUri(uri: String?) {
        context.dataStore.edit { if (uri == null) it.remove(BRUSH_TIP_URI_KEY) else it[BRUSH_TIP_URI_KEY] = uri }
    }

    suspend fun setBrushTextureUri(uri: String?) {
        context.dataStore.edit { if (uri == null) it.remove(BRUSH_TEXTURE_URI_KEY) else it[BRUSH_TEXTURE_URI_KEY] = uri }
    }

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

    /**
     * How far a fill reaches under the edge that stopped it, in pixels.
     *
     * Two by default rather than zero: an antialiased line fades over two or three pixels, and
     * every fill against one leaves a hem without this. Nobody would think to go looking for a
     * setting to fix what reads as the fill tool simply being wrong.
     */
    val fillGrow: Flow<Float> = context.dataStore.data.map { it[FILL_GROW_KEY] ?: 2f }.distinctUntilChanged()

    /** How much bigger the loupe draws the canvas. Four is close enough to read individual pixels. */
    val loupeZoom: Flow<Float> = context.dataStore.data.map { it[LOUPE_ZOOM_KEY] ?: 4f }.distinctUntilChanged()

    suspend fun setFillGrow(v: Float) { context.dataStore.edit { it[FILL_GROW_KEY] = v } }
    suspend fun setLoupeZoom(v: Float) { context.dataStore.edit { it[LOUPE_ZOOM_KEY] = v } }

    /**
     * Whether the starter presets have been installed.
     *
     * A flag rather than "is the brush library empty": someone who deletes all nine of them has
     * said what they want, and finding them back at the next launch would be the app arguing.
     */
    val defaultBrushesSeeded: Flow<Boolean> =
        context.dataStore.data.map { it[DEFAULT_BRUSHES_SEEDED_KEY] ?: false }.distinctUntilChanged()

    suspend fun setDefaultBrushesSeeded(done: Boolean) {
        context.dataStore.edit { it[DEFAULT_BRUSHES_SEEDED_KEY] = done }
    }

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

    /**
     * Whether an undone stroke is left behind on a reference layer instead of simply going.
     *
     * Off by default: it changes what undo means. The stroke is gone from the drawing, but
     * still on screen as a tracing guide, and someone who did not ask for that would read it
     * as undo having failed.
     */
    val keepUndoneStrokes: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEEP_UNDONE_STROKES_KEY] ?: false
    }.distinctUntilChanged()

    suspend fun setKeepUndoneStrokes(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEEP_UNDONE_STROKES_KEY] = enabled
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
