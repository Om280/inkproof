package com.inkproof.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.inkproof.app.BuildConfig
import com.inkproof.app.model.PenPalette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "inkproof_settings")

data class Settings(
    // Canvas
    val continueFromLastPage: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val fullScreenCanvas: Boolean = false,
    val fingerWriting: Boolean = false,
    // Writing
    val defaultPenColor: Int = PenPalette.INK_NAVY,
    val defaultPenWidth: Float = 3.0f,
    val pressureEnabled: Boolean = true,
    val holdToShapeMs: Long = 400L,
    val eraserRadius: Float = 18f,
    // Math
    val recognitionConfidenceThreshold: Float = 0.4f,
    val autoShowHints: Boolean = false,
    // AI
    val mockMode: Boolean = BuildConfig.DEFAULT_MOCK_MODE,
    val lastPageByNotebook: String = ""
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val CONTINUE_LAST = booleanPreferencesKey("continue_from_last_page")
        val KEEP_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val FULL_SCREEN = booleanPreferencesKey("full_screen_canvas")
        val FINGER_WRITING = booleanPreferencesKey("finger_writing")
        val PEN_COLOR = intPreferencesKey("default_pen_color")
        val PEN_WIDTH = floatPreferencesKey("default_pen_width")
        val PRESSURE = booleanPreferencesKey("pressure_enabled")
        val HOLD_MS = longPreferencesKey("hold_to_shape_ms")
        val ERASER_RADIUS = floatPreferencesKey("eraser_radius")
        val RECOGNITION_CONFIDENCE = floatPreferencesKey("recognition_confidence")
        val AUTO_HINTS = booleanPreferencesKey("auto_show_hints")
        val MOCK_MODE = booleanPreferencesKey("mock_mode")
        val LAST_PAGES = stringPreferencesKey("last_page_by_notebook")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            continueFromLastPage = p[Keys.CONTINUE_LAST] ?: true,
            keepScreenAwake = p[Keys.KEEP_AWAKE] ?: true,
            fullScreenCanvas = p[Keys.FULL_SCREEN] ?: false,
            fingerWriting = p[Keys.FINGER_WRITING] ?: false,
            defaultPenColor = p[Keys.PEN_COLOR] ?: PenPalette.INK_NAVY,
            defaultPenWidth = p[Keys.PEN_WIDTH] ?: 3.0f,
            pressureEnabled = p[Keys.PRESSURE] ?: true,
            holdToShapeMs = p[Keys.HOLD_MS] ?: 400L,
            eraserRadius = p[Keys.ERASER_RADIUS] ?: 18f,
            recognitionConfidenceThreshold = p[Keys.RECOGNITION_CONFIDENCE] ?: 0.4f,
            autoShowHints = p[Keys.AUTO_HINTS] ?: false,
            mockMode = p[Keys.MOCK_MODE] ?: BuildConfig.DEFAULT_MOCK_MODE,
            lastPageByNotebook = p[Keys.LAST_PAGES] ?: ""
        )
    }

    suspend fun setContinueFromLastPage(value: Boolean) =
        context.dataStore.edit { it[Keys.CONTINUE_LAST] = value }

    suspend fun setKeepScreenAwake(value: Boolean) =
        context.dataStore.edit { it[Keys.KEEP_AWAKE] = value }

    suspend fun setFullScreenCanvas(value: Boolean) =
        context.dataStore.edit { it[Keys.FULL_SCREEN] = value }

    suspend fun setFingerWriting(value: Boolean) =
        context.dataStore.edit { it[Keys.FINGER_WRITING] = value }

    suspend fun setDefaultPenColor(value: Int) =
        context.dataStore.edit { it[Keys.PEN_COLOR] = value }

    suspend fun setDefaultPenWidth(value: Float) =
        context.dataStore.edit { it[Keys.PEN_WIDTH] = value }

    suspend fun setPressureEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.PRESSURE] = value }

    suspend fun setHoldToShapeMs(value: Long) =
        context.dataStore.edit { it[Keys.HOLD_MS] = value }

    suspend fun setEraserRadius(value: Float) =
        context.dataStore.edit { it[Keys.ERASER_RADIUS] = value }

    suspend fun setRecognitionConfidence(value: Float) =
        context.dataStore.edit { it[Keys.RECOGNITION_CONFIDENCE] = value }

    suspend fun setAutoShowHints(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_HINTS] = value }

    suspend fun setMockMode(value: Boolean) =
        context.dataStore.edit { it[Keys.MOCK_MODE] = value }
}
