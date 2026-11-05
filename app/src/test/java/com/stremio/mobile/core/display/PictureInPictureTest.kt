package com.stremio.mobile.core.display

import android.util.Rational
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PictureInPictureTest {
    @Test
    fun keepsNormalVideoRatios() {
        assertEquals(Rational(1920, 1080), PictureInPictureController.clampRatio(1920, 1080))
    }

    @Test
    fun clampsUltraWideAndTallRatiosToPlatformLimits() {
        assertEquals(Rational(239, 100), PictureInPictureController.clampRatio(3840, 1200))
        assertEquals(Rational(100, 239), PictureInPictureController.clampRatio(400, 1600))
    }
}
