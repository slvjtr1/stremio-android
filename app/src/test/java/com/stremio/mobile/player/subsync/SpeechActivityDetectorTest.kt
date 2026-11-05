package com.stremio.mobile.player.subsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechActivityDetectorTest {

    private fun tone(freq: Double, ms: Int, rate: Int, channels: Int, amp: Double): ShortArray {
        val n = rate * ms / 1000
        return ShortArray(n * channels) { i ->
            val t = (i / channels).toDouble() / rate
            (amp * 32767 * sin(2 * PI * freq * t)).toInt().toShort()
        }
    }

    @Test
    fun emitsOneFramePer10msAtGivenIndex() {
        val frames = ArrayList<Long>()
        val det = SpeechActivityDetector { idx, _ -> frames.add(idx) }
        det.configure(48_000, 2, startFrameIndex = 500)
        det.feedPcm16(tone(1000.0, 1000, 48_000, 2, 0.3))
        assertEquals(100, frames.size)
        assertEquals(500L, frames.first())
        assertEquals(599L, frames.last())
    }

    @Test
    fun speechBandToneIsLouderThanSilenceAndHum() {
        val energies = HashMap<String, Float>()
        fun measure(label: String, pcm: ShortArray) {
            val vals = ArrayList<Float>()
            val det = SpeechActivityDetector { _, e -> vals.add(e) }
            det.configure(44_100, 1, 0)
            det.feedPcm16(pcm)
            energies[label] = vals.drop(10).average().toFloat()
        }
        measure("speech", tone(800.0, 500, 44_100, 1, 0.2))
        measure("hum", tone(40.0, 500, 44_100, 1, 0.2))
        measure("silence", ShortArray(22_050))
        assertTrue(energies.toString(), energies["speech"]!! > energies["hum"]!! + 2f)
        assertTrue(energies.toString(), energies["hum"]!! > energies["silence"]!!)
    }

    @Test
    fun floatInputMatchesPcm16() {
        val a = ArrayList<Float>()
        val b = ArrayList<Float>()
        val pcm = tone(1200.0, 200, 48_000, 2, 0.4)
        SpeechActivityDetector { _, e -> a.add(e) }.apply { configure(48_000, 2, 0); feedPcm16(pcm) }
        SpeechActivityDetector { _, e -> b.add(e) }.apply {
            configure(48_000, 2, 0)
            feedPcmFloat(FloatArray(pcm.size) { pcm[it] / 32768f })
        }
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-3f)
    }

    @Test
    fun timelineStoresSparseFrames() {
        val tl = ActivityTimeline()
        tl.put(5, 1f)
        tl.put(123_456, 2f)
        assertEquals(1f, tl.get(5))
        assertTrue(tl.get(6).isNaN())
        assertEquals(2, tl.knownFrames(0, 200_000))
        val out = FloatArray(3)
        tl.read(4, out)
        assertTrue(out[0].isNaN())
        assertEquals(1f, out[1])
    }
}
