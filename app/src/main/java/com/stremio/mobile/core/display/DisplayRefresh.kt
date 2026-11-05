package com.stremio.mobile.core.display

import kotlin.math.abs

/** User preference for the UI refresh rate. */
enum class RefreshRatePreference(val storageValue: String) {
    /** Leave the decision to the system (adaptive refresh, battery saver, user settings). */
    SYSTEM("system"),
    /** Ask for the fastest mode at the current resolution (e.g. 90/120/144 Hz). */
    HIGH("high"),
    /** Ask for a ~60 Hz mode to save battery. */
    STANDARD("standard");

    companion object {
        fun from(value: String?): RefreshRatePreference =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Chooses a display mode id for `WindowManager.LayoutParams.preferredDisplayModeId`.
 * Only modes with the current resolution are considered, so a refresh-rate change never
 * triggers a resolution switch. Returns 0 ("no preference") when the system should decide or
 * when nothing better than the current mode exists.
 */
object DisplayModeChooser {
    fun choose(
        modes: List<DisplayModeInfo>,
        current: DisplayModeInfo,
        preference: RefreshRatePreference,
    ): Int {
        if (preference == RefreshRatePreference.SYSTEM) return 0
        val sameResolution = modes.filter { it.width == current.width && it.height == current.height }
        if (sameResolution.size <= 1) return 0
        val pick = when (preference) {
            RefreshRatePreference.HIGH -> sameResolution.maxByOrNull { it.refreshRate }
            RefreshRatePreference.STANDARD -> sameResolution.minByOrNull { abs(it.refreshRate - 60f) }
            RefreshRatePreference.SYSTEM -> null
        } ?: return 0
        return pick.id
    }

    fun describe(modes: List<DisplayModeInfo>, current: DisplayModeInfo): String {
        val rates = modes.filter { it.width == current.width && it.height == current.height }
            .map { Math.round(it.refreshRate) }.distinct().sorted()
        return if (rates.isEmpty()) "${Math.round(current.refreshRate)} Hz" else rates.joinToString(" / ") { "$it Hz" }
    }
}

/** Implemented by the host activity so screens can coordinate display-mode requests. */
interface DisplayRefreshHost {
    val displayRefresh: DisplayRefreshController
}

/**
 * Single owner of `preferredDisplayModeId` for the window.
 *
 * Outside playback the user's [RefreshRatePreference] applies (fast mode for smooth scrolling,
 * 60 Hz for battery, or no preference). During playback the player's frame-rate matching wins,
 * and `0` lets the system and ExoPlayer's `Surface#setFrameRate` votes choose. Leaving the
 * player always restores the UI preference.
 */
class DisplayRefreshController(private val activity: android.app.Activity) {
    private var uiPreference = RefreshRatePreference.SYSTEM
    private var playbackActive = false
    private var playbackModeId = 0

    fun setUiPreference(preference: RefreshRatePreference) {
        uiPreference = preference
        apply()
    }

    fun setPlayback(active: Boolean, modeId: Int = 0) {
        playbackActive = active
        playbackModeId = if (active) modeId else 0
        apply()
    }

    fun apply() {
        val window = activity.window ?: return
        val target = if (playbackActive) playbackModeId else uiModeId()
        if (window.attributes.preferredDisplayModeId != target) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = target }
        }
    }

    fun supportedRatesLabel(): String {
        val (modes, current) = modes() ?: return "Unknown"
        return DisplayModeChooser.describe(modes, current)
    }

    private fun uiModeId(): Int {
        val (modes, current) = modes() ?: return 0
        return DisplayModeChooser.choose(modes, current, uiPreference)
    }

    private fun modes(): Pair<List<DisplayModeInfo>, DisplayModeInfo>? {
        val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { activity.display }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager?.defaultDisplay
        } ?: return null
        val active = display.mode ?: return null
        val modes = display.supportedModes?.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
            ?: return null
        return modes to DisplayModeInfo(active.modeId, active.physicalWidth, active.physicalHeight, active.refreshRate)
    }
}
