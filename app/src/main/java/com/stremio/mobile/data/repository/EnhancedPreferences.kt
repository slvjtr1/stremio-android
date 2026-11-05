package com.stremio.mobile.data.repository

import android.content.Context
import com.stremio.mobile.core.display.RefreshRatePreference
import com.stremio.mobile.presentation.components.SpoilerBlurLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Device-local preferences for features that have no equivalent in the Stremio profile. */
data class EnhancedSettings(
    val autoSubtitleSync: Boolean = true,
    val subtitleDriftCorrection: Boolean = true,
    val spoilerBlurLevel: SpoilerBlurLevel = SpoilerBlurLevel.MEDIUM,
    val refreshRate: RefreshRatePreference = RefreshRatePreference.HIGH,
    val reduceMotion: Boolean = false,
)

class EnhancedPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("enhanced_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val settings: StateFlow<EnhancedSettings> = mutable

    private fun read() = EnhancedSettings(
        autoSubtitleSync = prefs.getBoolean(KEY_AUTO_SYNC, true),
        subtitleDriftCorrection = prefs.getBoolean(KEY_DRIFT, true),
        spoilerBlurLevel = SpoilerBlurLevel.from(prefs.getString(KEY_SPOILER, null)),
        refreshRate = RefreshRatePreference.from(prefs.getString(KEY_REFRESH, RefreshRatePreference.HIGH.storageValue)),
        reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
    )

    fun update(transform: (EnhancedSettings) -> EnhancedSettings) {
        val next = transform(mutable.value)
        if (next == mutable.value) return
        prefs.edit()
            .putBoolean(KEY_AUTO_SYNC, next.autoSubtitleSync)
            .putBoolean(KEY_DRIFT, next.subtitleDriftCorrection)
            .putString(KEY_SPOILER, next.spoilerBlurLevel.storageValue)
            .putString(KEY_REFRESH, next.refreshRate.storageValue)
            .putBoolean(KEY_REDUCE_MOTION, next.reduceMotion)
            .apply()
        mutable.value = next
    }

    private companion object {
        const val KEY_AUTO_SYNC = "auto_subtitle_sync"
        const val KEY_DRIFT = "subtitle_drift_correction"
        const val KEY_SPOILER = "spoiler_blur_level"
        const val KEY_REFRESH = "refresh_rate"
        const val KEY_REDUCE_MOTION = "reduce_motion"
    }
}
