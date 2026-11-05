package com.stremio.mobile.player.subsync

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives decoded PCM from ExoPlayer's audio pipeline (before speed processing) and feeds the
 * speech detector. Each flush (seek, format change) re-anchors the sample counter to the media
 * time published by the player thread, so frames land at the right place on the timeline.
 *
 * Runs on ExoPlayer's playback thread; the work per sample is a handful of arithmetic ops.
 */
@OptIn(UnstableApi::class)
class SpeechAudioTap(
    private val timeline: ActivityTimeline,
) : TeeAudioProcessor.AudioBufferSink {
    private val detector = SpeechActivityDetector { index, energy -> timeline.put(index, energy) }

    /** Media position to anchor the next flush to; set by the player on load/seek. */
    private val pendingAnchorMs = AtomicLong(0L)
    /** Last playhead position published by the player, used when a flush has no seek target. */
    private val lastPositionMs = AtomicLong(0L)

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var receivingPcm: Boolean = false
        private set

    private var encoding = C.ENCODING_INVALID
    private var shortScratch = ShortArray(0)
    private var floatScratch = FloatArray(0)

    fun anchorAt(positionMs: Long) = pendingAnchorMs.set(positionMs.coerceAtLeast(0))

    fun publishPosition(positionMs: Long) = lastPositionMs.set(positionMs.coerceAtLeast(0))

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
        val anchor = pendingAnchorMs.getAndSet(-1L).takeIf { it >= 0 } ?: lastPositionMs.get()
        val supported = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT
        receivingPcm = supported && sampleRateHz > 0 && channelCount > 0
        if (receivingPcm) {
            detector.configure(sampleRateHz, channelCount, anchor / SpeechActivityDetector.FRAME_MS)
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled || !receivingPcm || !detector.isConfigured) return
        val bb = buffer.duplicate().order(ByteOrder.nativeOrder())
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val n = bb.remaining() / 2
                if (shortScratch.size < n) shortScratch = ShortArray(n)
                bb.asShortBuffer().get(shortScratch, 0, n)
                detector.feedPcm16(shortScratch, n)
            }
            C.ENCODING_PCM_FLOAT -> {
                val n = bb.remaining() / 4
                if (floatScratch.size < n) floatScratch = FloatArray(n)
                bb.asFloatBuffer().get(floatScratch, 0, n)
                detector.feedPcmFloat(floatScratch, n)
            }
        }
    }
}
