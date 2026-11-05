package com.stremio.mobile.player.subsync

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Maps subtitle time to media time: `media = subtitle * scale + offsetMs`.
 *
 * `scale != 1` models progressive drift (typically a frame-rate mismatch such as 23.976 vs 25 fps);
 * `offsetMs` is the constant shift. [manualMs] is the user's own correction on top of the
 * automatic one and is never touched by the engine.
 */
data class SubtitleTimeMap(
    val offsetMs: Double = 0.0,
    val scale: Double = 1.0,
    val manualMs: Long = 0L,
) {
    fun subtitleTimeAt(mediaMs: Long): Long = ((mediaMs - offsetMs - manualMs) / scale).roundToLong()

    fun mediaTimeOf(subtitleMs: Long): Long = (subtitleMs * scale + offsetMs + manualMs).roundToLong()

    /** Total shift currently applied at [mediaMs] (positive = subtitles appear later). */
    fun effectiveShiftAt(mediaMs: Long): Long = mediaMs - subtitleTimeAt(mediaMs)

    val isIdentity: Boolean get() = offsetMs == 0.0 && scale == 1.0 && manualMs == 0L
}

/** Subtitle cue intervals in subtitle time, sorted by start. */
class CueIntervals(starts: LongArray, ends: LongArray) {
    val starts: LongArray
    val ends: LongArray

    init {
        require(starts.size == ends.size)
        val order = starts.indices.sortedBy { starts[it] }
        this.starts = LongArray(order.size) { starts[order[it]] }
        this.ends = LongArray(order.size) { maxOf(ends[order[it]], starts[order[it]]) }
    }

    val size: Int get() = starts.size
    val lastEndMs: Long get() = ends.maxOrNull() ?: 0L

    /** Rasterises the cues to [binMs] bins over subtitle time [0, lastEnd + pad). */
    fun raster(binMs: Int, padMs: Long = 0L): BooleanArray {
        val bins = ((lastEndMs + padMs) / binMs + 1).toInt().coerceAtLeast(1)
        val out = BooleanArray(bins)
        for (i in starts.indices) {
            val a = (starts[i] / binMs).toInt().coerceIn(0, bins - 1)
            val b = ((ends[i] - 1).coerceAtLeast(starts[i]) / binMs).toInt().coerceIn(0, bins - 1)
            for (k in a..b) out[k] = true
        }
        return out
    }

    companion object {
        fun of(vararg pairs: Pair<Long, Long>) =
            CueIntervals(LongArray(pairs.size) { pairs[it].first }, LongArray(pairs.size) { pairs[it].second })
    }
}

data class OffsetEstimate(
    /** Best `offsetMs` for the given scale. */
    val offsetMs: Long,
    val scale: Double,
    /** Correlation peak height in standard deviations of the off-peak score distribution. */
    val zScore: Double,
    /** Peak divided by the best competing peak at least [SEPARATION_MS] away. */
    val peakRatio: Double,
    val coverage: Double,
) {
    val isReliable: Boolean
        get() = zScore >= MIN_Z && peakRatio >= MIN_RATIO && coverage >= MIN_COVERAGE

    companion object {
        const val MIN_Z = 5.0
        const val MIN_RATIO = 1.12
        const val MIN_COVERAGE = 0.6
        const val SEPARATION_MS = 1_500L
    }
}

/**
 * Finds the subtitle shift that best lines cue intervals up with detected speech, by
 * cross-correlating a normalised speech feature with the rasterised cue activity
 * (the approach used by tools such as ffsubsync, implemented here without FFT because the
 * search windows are small). A coarse pass is followed by a 10 ms refinement around the peak.
 */
object OffsetEstimator {
    private const val FRAME_MS = SpeechActivityDetector.FRAME_MS
    private const val COARSE_BIN_MS = 100
    private const val FINE_RADIUS_MS = 200L

    fun estimate(
        timeline: ActivityTimeline,
        cues: CueIntervals,
        windowStartMs: Long,
        windowEndMs: Long,
        scale: Double,
        searchCenterMs: Long,
        searchRadiusMs: Long,
    ): OffsetEstimate? {
        if (cues.size == 0 || windowEndMs - windowStartMs < 10_000) return null
        val startFrame = windowStartMs / FRAME_MS
        val frameCount = ((windowEndMs - windowStartMs) / FRAME_MS).toInt()
        val energy = FloatArray(frameCount)
        timeline.read(startFrame, energy)
        val speech = normalise(energy) ?: return null
        val known = speech.count { !it.isNaN() }
        val coverage = known.toDouble() / frameCount
        if (known < 3_000) return null

        // Coarse pass on 100 ms bins.
        val ratio = COARSE_BIN_MS / FRAME_MS
        val coarseCount = frameCount / ratio
        val coarse = FloatArray(coarseCount) { b ->
            var s = 0f
            var n = 0
            for (k in 0 until ratio) {
                val v = speech[b * ratio + k]
                if (!v.isNaN()) {
                    s += v
                    n++
                }
            }
            if (n == 0) Float.NaN else s / n
        }
        val coarseRaster = cues.raster(COARSE_BIN_MS, padMs = 0)
        val lagStep = COARSE_BIN_MS.toLong()
        val lagCount = (2 * searchRadiusMs / lagStep + 1).toInt()
        val scores = DoubleArray(lagCount)
        for (li in 0 until lagCount) {
            val offset = searchCenterMs - searchRadiusMs + li * lagStep
            scores[li] = score(coarse, windowStartMs, COARSE_BIN_MS, coarseRaster, COARSE_BIN_MS, offset, scale)
        }
        var best = 0
        for (i in scores.indices) if (scores[i] > scores[best]) best = i
        val bestCoarse = searchCenterMs - searchRadiusMs + best * lagStep

        val sepBins = (OffsetEstimate.SEPARATION_MS / lagStep).toInt()
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var competitor = Double.NEGATIVE_INFINITY
        for (i in scores.indices) {
            if (abs(i - best) <= sepBins) continue
            sum += scores[i]
            sumSq += scores[i] * scores[i]
            n++
            if (scores[i] > competitor) competitor = scores[i]
        }
        val peak = scores[best]
        val z: Double
        val peakRatio: Double
        if (n >= 8) {
            val mean = sum / n
            val sd = sqrt((sumSq / n - mean * mean).coerceAtLeast(1e-12))
            z = (peak - mean) / sd
            peakRatio = if (competitor <= 0.0) (if (peak > 0) 10.0 else 0.0) else peak / competitor
        } else {
            // Narrow searches (drift tracking) cannot build an off-peak distribution; use the
            // shape of the scores themselves.
            val mean = scores.average()
            val sd = sqrt(scores.map { (it - mean) * (it - mean) }.average().coerceAtLeast(1e-12))
            z = (peak - mean) / sd
            peakRatio = if (peak > 0) 2.0 else 0.0
        }

        // Fine pass on 10 ms frames.
        val fineRaster = cues.raster(FRAME_MS, padMs = 0)
        var bestFine = bestCoarse
        var bestFineScore = Double.NEGATIVE_INFINITY
        var off = bestCoarse - FINE_RADIUS_MS
        while (off <= bestCoarse + FINE_RADIUS_MS) {
            val s = score(speech, windowStartMs, FRAME_MS, fineRaster, FRAME_MS, off, scale)
            if (s > bestFineScore) {
                bestFineScore = s
                bestFine = off
            }
            off += FRAME_MS
        }
        return OffsetEstimate(bestFine, scale, z, peakRatio, coverage)
    }

    /**
     * Robust z-normalisation (median / MAD) clipped to ±3, so music and effects peaks cannot
     * dominate the correlation. Returns null if the window holds no usable signal.
     */
    internal fun normalise(energy: FloatArray): FloatArray? {
        val known = energy.filter { !it.isNaN() }.sorted()
        if (known.size < 100) return null
        val median = known[known.size / 2]
        val deviations = known.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2].coerceAtLeast(1e-3f)
        return FloatArray(energy.size) { i ->
            val v = energy[i]
            if (v.isNaN()) Float.NaN else ((v - median) / (1.4826f * mad)).coerceIn(-3f, 3f)
        }
    }

    /** Σ speech(t) · (cueActive(t) − mean) over the window, with cues mapped via (scale, offset). */
    private fun score(
        speech: FloatArray,
        windowStartMs: Long,
        speechBinMs: Int,
        raster: BooleanArray,
        rasterBinMs: Int,
        offsetMs: Long,
        scale: Double,
    ): Double {
        var dot = 0.0
        var speechSum = 0.0
        var activeCount = 0
        var n = 0
        for (i in speech.indices) {
            val v = speech[i]
            if (v.isNaN()) continue
            val mediaMs = windowStartMs + i.toLong() * speechBinMs + speechBinMs / 2
            val subMs = (mediaMs - offsetMs) / scale
            val active = if (subMs < 0) false else {
                val bin = (subMs / rasterBinMs).toInt()
                bin < raster.size && raster[bin]
            }
            if (active) {
                dot += v
                activeCount++
            }
            speechSum += v
            n++
        }
        if (n == 0) return 0.0
        // Centre the cue indicator so windows with many cues don't win by volume alone.
        val p = activeCount.toDouble() / n
        return (dot - p * speechSum) / n
    }
}
