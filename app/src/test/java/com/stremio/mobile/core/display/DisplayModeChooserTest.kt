package com.stremio.mobile.core.display

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayModeChooserTest {
    private val fhd60 = DisplayModeInfo(1, 1080, 2400, 60f)
    private val fhd120 = DisplayModeInfo(2, 1080, 2400, 120f)
    private val fhd90 = DisplayModeInfo(3, 1080, 2400, 90f)
    private val qhd120 = DisplayModeInfo(4, 1440, 3200, 120f)
    private val qhd144 = DisplayModeInfo(5, 1440, 3200, 144f)
    private val all = listOf(fhd60, fhd120, fhd90, qhd120, qhd144)

    @Test
    fun highPicksFastestModeAtCurrentResolutionOnly() {
        assertEquals(2, DisplayModeChooser.choose(all, fhd60, RefreshRatePreference.HIGH))
        assertEquals(5, DisplayModeChooser.choose(all, qhd120, RefreshRatePreference.HIGH))
    }

    @Test
    fun standardPicksSixtyHertz() {
        assertEquals(1, DisplayModeChooser.choose(all, fhd120, RefreshRatePreference.STANDARD))
    }

    @Test
    fun systemOrSingleModeMeansNoPreference() {
        assertEquals(0, DisplayModeChooser.choose(all, fhd60, RefreshRatePreference.SYSTEM))
        assertEquals(0, DisplayModeChooser.choose(listOf(fhd60), fhd60, RefreshRatePreference.HIGH))
    }

    @Test
    fun describesSupportedRates() {
        assertEquals("60 Hz / 90 Hz / 120 Hz", DisplayModeChooser.describe(all, fhd60))
    }

    @Test
    fun parsesStoredPreference() {
        assertEquals(RefreshRatePreference.HIGH, RefreshRatePreference.from("high"))
        assertEquals(RefreshRatePreference.SYSTEM, RefreshRatePreference.from("bogus"))
    }
}
