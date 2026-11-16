package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.BotConfig
import com.example.model.FormulaMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.botDataStore: DataStore<Preferences> by preferencesDataStore(name = "jump_assist_prefs")

class BotPreferencesRepository(private val context: Context) {

    private object Keys {
        val FORMULA_MODE = stringPreferencesKey("formula_mode")
        val K_FACTOR = floatPreferencesKey("k_factor")
        val OFFSET_MS = floatPreferencesKey("offset_ms")
        val QUAD_FACTOR = floatPreferencesKey("quad_factor")
        val PAWN_COLOR_RGB = intPreferencesKey("pawn_color_rgb")
        val PAWN_COLOR_TOLERANCE = intPreferencesKey("pawn_color_tolerance")
        val BG_DIFF_THRESHOLD = intPreferencesKey("bg_diff_threshold")
        val ROI_TOP_FRACTION = floatPreferencesKey("roi_top_fraction")
        val ROI_BOTTOM_FRACTION = floatPreferencesKey("roi_bottom_fraction")
        val TAP_X_FRACTION = floatPreferencesKey("tap_x_fraction")
        val TAP_Y_FRACTION = floatPreferencesKey("tap_y_fraction")
        val JUMP_COOLDOWN_MS = longPreferencesKey("jump_cooldown_ms")
        val MIN_PRESS_MS = longPreferencesKey("min_press_ms")
        val MAX_PRESS_MS = longPreferencesKey("max_press_ms")
        val CALIB_VERSION = intPreferencesKey("calib_version")
    }

    private companion object {
        const val CURRENT_CALIB_VERSION = 2
    }

    val configFlow: Flow<BotConfig> = context.botDataStore.data.map { prefs ->
        val migrated = (prefs[Keys.CALIB_VERSION] ?: 1) >= CURRENT_CALIB_VERSION
        val modeName = if (migrated) (prefs[Keys.FORMULA_MODE] ?: FormulaMode.LINEAR.name) else FormulaMode.LINEAR.name
        val mode = runCatching { FormulaMode.valueOf(modeName) }.getOrDefault(FormulaMode.LINEAR)
        val defaults = BotConfig()
        if (!migrated) {
            defaults
        } else {
            BotConfig(
                formulaMode = mode,
                kFactor = prefs[Keys.K_FACTOR] ?: defaults.kFactor,
                offsetMs = prefs[Keys.OFFSET_MS] ?: defaults.offsetMs,
                quadFactor = prefs[Keys.QUAD_FACTOR] ?: defaults.quadFactor,
                pawnColorRgb = prefs[Keys.PAWN_COLOR_RGB] ?: defaults.pawnColorRgb,
                pawnColorTolerance = prefs[Keys.PAWN_COLOR_TOLERANCE] ?: defaults.pawnColorTolerance,
                bgDiffThreshold = prefs[Keys.BG_DIFF_THRESHOLD] ?: defaults.bgDiffThreshold,
                roiTopFraction = prefs[Keys.ROI_TOP_FRACTION] ?: defaults.roiTopFraction,
                roiBottomFraction = prefs[Keys.ROI_BOTTOM_FRACTION] ?: defaults.roiBottomFraction,
                tapXFraction = prefs[Keys.TAP_X_FRACTION] ?: defaults.tapXFraction,
                tapYFraction = prefs[Keys.TAP_Y_FRACTION] ?: defaults.tapYFraction,
                jumpCooldownMs = prefs[Keys.JUMP_COOLDOWN_MS] ?: defaults.jumpCooldownMs,
                minPressMs = prefs[Keys.MIN_PRESS_MS] ?: defaults.minPressMs,
                maxPressMs = prefs[Keys.MAX_PRESS_MS] ?: defaults.maxPressMs
            )
        }
    }

    suspend fun updateConfig(transform: (BotConfig) -> BotConfig) {
        context.botDataStore.edit { prefs ->
            val migrated = (prefs[Keys.CALIB_VERSION] ?: 1) >= CURRENT_CALIB_VERSION
            val defaults = BotConfig()
            val current = if (!migrated) {
                defaults
            } else {
                BotConfig(
                    formulaMode = runCatching {
                        FormulaMode.valueOf(prefs[Keys.FORMULA_MODE] ?: FormulaMode.LINEAR.name)
                    }.getOrDefault(FormulaMode.LINEAR),
                    kFactor = prefs[Keys.K_FACTOR] ?: defaults.kFactor,
                    offsetMs = prefs[Keys.OFFSET_MS] ?: defaults.offsetMs,
                    quadFactor = prefs[Keys.QUAD_FACTOR] ?: defaults.quadFactor,
                    pawnColorRgb = prefs[Keys.PAWN_COLOR_RGB] ?: defaults.pawnColorRgb,
                    pawnColorTolerance = prefs[Keys.PAWN_COLOR_TOLERANCE] ?: defaults.pawnColorTolerance,
                    bgDiffThreshold = prefs[Keys.BG_DIFF_THRESHOLD] ?: defaults.bgDiffThreshold,
                    roiTopFraction = prefs[Keys.ROI_TOP_FRACTION] ?: defaults.roiTopFraction,
                    roiBottomFraction = prefs[Keys.ROI_BOTTOM_FRACTION] ?: defaults.roiBottomFraction,
                    tapXFraction = prefs[Keys.TAP_X_FRACTION] ?: defaults.tapXFraction,
                    tapYFraction = prefs[Keys.TAP_Y_FRACTION] ?: defaults.tapYFraction,
                    jumpCooldownMs = prefs[Keys.JUMP_COOLDOWN_MS] ?: defaults.jumpCooldownMs,
                    minPressMs = prefs[Keys.MIN_PRESS_MS] ?: defaults.minPressMs,
                    maxPressMs = prefs[Keys.MAX_PRESS_MS] ?: defaults.maxPressMs
                )
            }
            val updated = transform(current)
            prefs[Keys.CALIB_VERSION] = CURRENT_CALIB_VERSION
            prefs[Keys.FORMULA_MODE] = updated.formulaMode.name
            prefs[Keys.K_FACTOR] = updated.kFactor
            prefs[Keys.OFFSET_MS] = updated.offsetMs
            prefs[Keys.QUAD_FACTOR] = updated.quadFactor
            prefs[Keys.PAWN_COLOR_RGB] = updated.pawnColorRgb
            prefs[Keys.PAWN_COLOR_TOLERANCE] = updated.pawnColorTolerance
            prefs[Keys.BG_DIFF_THRESHOLD] = updated.bgDiffThreshold
            prefs[Keys.ROI_TOP_FRACTION] = updated.roiTopFraction
            prefs[Keys.ROI_BOTTOM_FRACTION] = updated.roiBottomFraction
            prefs[Keys.TAP_X_FRACTION] = updated.tapXFraction
            prefs[Keys.TAP_Y_FRACTION] = updated.tapYFraction
            prefs[Keys.JUMP_COOLDOWN_MS] = updated.jumpCooldownMs
            prefs[Keys.MIN_PRESS_MS] = updated.minPressMs
            prefs[Keys.MAX_PRESS_MS] = updated.maxPressMs
        }
    }

    suspend fun resetDefaults() {
        context.botDataStore.edit { it.clear() }
    }
}
