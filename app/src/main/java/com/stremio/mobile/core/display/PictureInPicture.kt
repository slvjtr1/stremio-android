package com.stremio.mobile.core.display

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/** Implemented by the host activity; lets the player request picture-in-picture. */
interface PictureInPictureHost {
    val pictureInPicture: PictureInPictureController
}

/**
 * Picture-in-picture for video playback. The player marks itself eligible while a video is
 * playing; leaving the app then shrinks playback into a PiP window (auto-enter on Android 12+,
 * `onUserLeaveHint` before that). Controls are hidden while in PiP.
 */
class PictureInPictureController(private val activity: Activity) {
    private val inPip: MutableState<Boolean> = mutableStateOf(false)
    val isInPictureInPicture: State<Boolean> get() = inPip

    private var eligible = false
    private var ratio = Rational(16, 9)

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun setEligible(eligible: Boolean, videoWidth: Int = 0, videoHeight: Int = 0) {
        this.eligible = eligible
        if (videoWidth > 0 && videoHeight > 0) ratio = clampRatio(videoWidth, videoHeight)
        if (isSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { activity.setPictureInPictureParams(params()) }
        }
    }

    /** Called from `onUserLeaveHint`; needed before Android 12 where auto-enter doesn't exist. */
    fun onUserLeaveHint() {
        if (!eligible || !isSupported || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { activity.enterPictureInPictureMode(params()) }
    }

    fun onModeChanged(isInPictureInPicture: Boolean) {
        inPip.value = isInPictureInPicture
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun params(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(eligible).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    companion object {
        /** Android only accepts aspect ratios between 1:2.39 and 2.39:1. */
        fun clampRatio(width: Int, height: Int): Rational {
            val r = width.toDouble() / height
            return when {
                r > 2.39 -> Rational(239, 100)
                r < 1 / 2.39 -> Rational(100, 239)
                else -> Rational(width, height)
            }
        }
    }
}
