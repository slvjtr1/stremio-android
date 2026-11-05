package com.stremio.mobile.player.subsync

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/**
 * Lightweight voice-activity feature extractor.
 *
 * Decoded PCM is down-mixed to mono, band-limited to the speech band (~200 Hz – 3.4 kHz) with
 * two one-pole filters, and reduced to one log-energy value per [FRAME_MS] frame. Decisions are
 * not made here: the estimator normalises energies over its analysis window, which keeps the
 * feature independent of mix loudness and makes it cheap enough to run on the audio thread.
 */
class SpeechActivityDetector(
    private val onFrame: (frameIndex: Long, logEnergy: Float) -> Unit,
) {
    private var sampleRate = 0
    private var channels = 0
    private var samplesPerFrame = 0

    private var hpPrevIn = 0.0
    private var hpPrevOut = 0.0
    private var lpPrevOut = 0.0
    private var hpAlpha = 0.0
    private var lpAlpha = 0.0

    private var frameAcc = 0.0
    private var frameSamples = 0
    private var nextFrameIndex = 0L

    fun configure(sampleRateHz: Int, channelCount: Int, startFrameIndex: Long) {
        sampleRate = sampleRateHz
        channels = channelCount.coerceAtLeast(1)
        samplesPerFrame = (sampleRateHz * FRAME_MS / 1000).coerceAtLeast(1)
        val dt = 1.0 / sampleRateHz
        val hpRc = 1.0 / (2 * PI * HIGH_PASS_HZ)
        val lpRc = 1.0 / (2 * PI * LOW_PASS_HZ)
        hpAlpha = hpRc / (hpRc + dt)
        lpAlpha = dt / (lpRc + dt)
        hpPrevIn = 0.0
        hpPrevOut = 0.0
        lpPrevOut = 0.0
        frameAcc = 0.0
        frameSamples = 0
        nextFrameIndex = startFrameIndex
    }

    val isConfigured: Boolean get() = sampleRate > 0

    /** Interleaved signed 16-bit samples. */
    fun feedPcm16(samples: ShortArray, count: Int = samples.size) {
        if (!isConfigured) return
        var i = 0
        while (i + channels <= count) {
            var sum = 0.0
            for (c in 0 until channels) sum += samples[i + c]
            pushMono(sum / channels / 32768.0)
            i += channels
        }
    }

    /** Interleaved float samples in [-1, 1]. */
    fun feedPcmFloat(samples: FloatArray, count: Int = samples.size) {
        if (!isConfigured) return
        var i = 0
        while (i + channels <= count) {
            var sum = 0.0
            for (c in 0 until channels) sum += samples[i + c]
            pushMono(sum / channels)
            i += channels
        }
    }

    private fun pushMono(x: Double) {
        val hp = hpAlpha * (hpPrevOut + x - hpPrevIn)
        hpPrevIn = x
        hpPrevOut = hp
        lpPrevOut += lpAlpha * (hp - lpPrevOut)
        frameAcc += lpPrevOut * lpPrevOut
        frameSamples++
        if (frameSamples >= samplesPerFrame) {
            val meanSquare = frameAcc / frameSamples
            onFrame(nextFrameIndex++, ln(meanSquare + ENERGY_FLOOR).toFloat())
            frameAcc = 0.0
            frameSamples = 0
        }
    }

    companion object {
        const val FRAME_MS = 10
        private const val HIGH_PASS_HZ = 200.0
        private const val LOW_PASS_HZ = 3400.0
        private val ENERGY_FLOOR = exp(-23.0)
    }
}
