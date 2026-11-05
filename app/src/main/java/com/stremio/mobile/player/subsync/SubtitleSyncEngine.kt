package com.stremio.mobile.player.subsync

import kotlin.math.abs

sealed interface SubtitleSyncStatus {
    /** Automatic sync is switched off; manual delay still applies. */
    data object Off : SubtitleSyncStatus

    /** The current track/engine cannot be analysed (e.g. no audio tap, unknown cue timing). */
    data class Unavailable(val reason: String) : SubtitleSyncStatus

    /** Collecting speech evidence. [progress] is 0..1 of the audio needed for a first estimate. */
    data class Listening(val progress: Float) : SubtitleSyncStatus

    data class Synced(
        val shiftMs: Long,
        val scale: Double,
        val zScore: Double,
        val corrections: Int,
    ) : SubtitleSyncStatus {
        val hasDrift: Boolean get() = abs(scale - 1.0) > 1e-5
    }

    /** Evidence was insufficient; nothing was applied. */
    data class Uncertain(val reason: String) : SubtitleSyncStatus
}

/**
 * Automatic subtitle synchronisation policy.
 *
 * * **Constant offset** – once [INITIAL_AUDIO_MS] of audio has been heard, the engine searches a
 *   wide window (±[WIDE_RADIUS_MS]) at scale 1 and at common frame-rate ratios, and applies the
 *   best estimate only if it is statistically reliable.
 * * **Progressive drift** – while playing, local offsets are re-estimated every
 *   [TRACK_INTERVAL_MS] in a narrow window around the current mapping. A linear fit of those
 *   local offsets against media time yields a scale correction; it is applied only when several
 *   consistent points span enough time and the change at the playhead is noticeable.
 *
 * The engine never changes timing on weak evidence and never touches the user's manual offset.
 * It is single-threaded: call it from one worker.
 */
class SubtitleSyncEngine(
    val timeline: ActivityTimeline = ActivityTimeline(),
) {
    var cues: CueIntervals? = null
        private set
    var map: SubtitleTimeMap = SubtitleTimeMap()
        private set
    var status: SubtitleSyncStatus = SubtitleSyncStatus.Off
        private set

    var autoSyncEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) status = SubtitleSyncStatus.Off else if (status == SubtitleSyncStatus.Off) restart()
        }
    var driftCorrectionEnabled: Boolean = true

    internal var lastDebug: String = ""
    private var initialDone = false
    private var nextCheckpoint = 0
    private var lastTrackAtMs = Long.MIN_VALUE
    private var corrections = 0
    private var lastZ = 0.0
    private val trackPoints = ArrayList<TrackPoint>()

    private data class TrackPoint(val mediaMs: Long, val offsetMs: Double, val z: Double)

    fun setCues(intervals: CueIntervals?, unavailableReason: String? = null) {
        cues = intervals
        map = SubtitleTimeMap(manualMs = map.manualMs)
        restart()
        if (intervals == null || intervals.size < MIN_CUES) {
            status = if (!autoSyncEnabled) SubtitleSyncStatus.Off
            else SubtitleSyncStatus.Unavailable(unavailableReason ?: "Subtitle timing is not available for this track")
        }
    }

    fun setManualOffset(ms: Long) {
        if (ms == map.manualMs) return
        map = map.copy(manualMs = ms)
        // Tracking points are totals; they are stale once the manual part changes.
        trackPoints.clear()
    }

    /** Discards the automatic correction (keeps manual) and starts listening again. */
    fun reset() {
        map = SubtitleTimeMap(manualMs = map.manualMs)
        restart()
    }

    private fun restart() {
        initialDone = false
        nextCheckpoint = 0
        lastTrackAtMs = Long.MIN_VALUE
        corrections = 0
        trackPoints.clear()
        if (autoSyncEnabled && (cues?.size ?: 0) >= MIN_CUES) status = SubtitleSyncStatus.Listening(0f)
        else if (!autoSyncEnabled) status = SubtitleSyncStatus.Off
    }

    /**
     * Runs one analysis step for the current playhead. Returns true when [map] changed.
     * Cheap when there is nothing to do; the wide search costs a few tens of ms.
     */
    fun analyze(positionMs: Long): Boolean {
        val cues = cues ?: return false
        if (!autoSyncEnabled || cues.size < MIN_CUES) return false
        if (status is SubtitleSyncStatus.Unavailable) return false
        return if (!initialDone) initialStep(cues, positionMs) else trackStep(cues, positionMs)
    }

    /** Window of recent audio actually heard: [first heard frame within the last 5 min, pos). */
    private fun heardWindow(positionMs: Long): Pair<Long, Long> {
        val from = (positionMs - INITIAL_WINDOW_MS).coerceAtLeast(0)
        val first = timeline.firstKnownFrame(from / FRAME, positionMs / FRAME)
        val start = if (first < 0) positionMs else first * FRAME
        val heard = timeline.knownFrames(start / FRAME, positionMs / FRAME).toLong() * FRAME
        return start to heard
    }

    /**
     * Wide search over offsets and (when drift correction is on) frame-rate ratios. A scale other
     * than [preferredScale] must beat it clearly: over a few minutes 1.0 and 0.999 are nearly
     * indistinguishable, so noise must not flip the decision.
     */
    private fun wideSearch(cues: CueIntervals, windowStart: Long, positionMs: Long, preferredScale: Double): OffsetEstimate? {
        val scales = if (driftCorrectionEnabled) (listOf(preferredScale) + SCALES).distinct() else listOf(1.0)
        val estimates = scales.mapNotNull { scale ->
            OffsetEstimator.estimate(
                timeline, cues, windowStart, positionMs, scale,
                searchCenterMs = map.manualMs, searchRadiusMs = WIDE_RADIUS_MS,
            )
        }
        lastDebug = estimates.joinToString { "s=%.4f o=%d z=%.2f r=%.2f c=%.2f".format(it.scale, it.offsetMs, it.zScore, it.peakRatio, it.coverage) }
        val preferred = estimates.firstOrNull { it.scale == preferredScale }
        val bestOther = estimates.filter { it.scale != preferredScale }.maxByOrNull { it.zScore }
        return when {
            preferred == null -> bestOther
            bestOther != null && bestOther.zScore > preferred.zScore * SCALE_PREFERENCE_MARGIN -> bestOther
            else -> preferred
        }
    }

    private fun applyEstimate(estimate: OffsetEstimate, positionMs: Long): Boolean {
        lastZ = estimate.zScore
        // The estimator measures the total shift; the user's manual part is kept separate.
        val newMap = SubtitleTimeMap((estimate.offsetMs - map.manualMs).toDouble(), estimate.scale, map.manualMs)
        val changed = abs(newMap.effectiveShiftAt(positionMs) - map.effectiveShiftAt(positionMs)) >= MIN_APPLY_MS ||
            newMap.scale != map.scale
        if (changed) {
            map = newMap
            corrections++
            trackPoints.clear()
        }
        status = synced()
        return changed
    }

    private fun initialStep(cues: CueIntervals, positionMs: Long): Boolean {
        val (windowStart, heard) = heardWindow(positionMs)
        if (heard < INITIAL_AUDIO_MS) {
            status = SubtitleSyncStatus.Listening((heard.toFloat() / INITIAL_AUDIO_MS).coerceIn(0f, 0.99f))
            return false
        }
        val estimate = wideSearch(cues, windowStart, positionMs, preferredScale = 1.0)
        if (estimate == null || !estimate.isReliable) {
            status = if (heard >= GIVE_UP_AFTER_MS) {
                initialDone = true
                lastTrackAtMs = positionMs
                SubtitleSyncStatus.Uncertain("Speech and subtitles could not be matched reliably; timing left unchanged")
            } else {
                SubtitleSyncStatus.Listening(0.99f)
            }
            return false
        }
        initialDone = true
        lastTrackAtMs = positionMs
        nextCheckpoint = 0
        return applyEstimate(estimate, positionMs)
    }

    private fun trackStep(cues: CueIntervals, positionMs: Long): Boolean {
        if (status is SubtitleSyncStatus.Uncertain) {
            // A failed initial pass gets one more chance once much more audio is available.
            if (positionMs - lastTrackAtMs < RETRY_UNCERTAIN_MS && lastTrackAtMs != Long.MIN_VALUE) return false
            lastTrackAtMs = positionMs
            initialDone = false
            return initialStep(cues, positionMs)
        }
        // Re-check offset and frame-rate ratio once more audio is available.
        if (nextCheckpoint < CHECKPOINTS_MS.size) {
            val (windowStart, heard) = heardWindow(positionMs)
            if (heard >= CHECKPOINTS_MS[nextCheckpoint]) {
                nextCheckpoint++
                val estimate = wideSearch(cues, windowStart, positionMs, preferredScale = map.scale)
                if (estimate != null && estimate.isReliable) return applyEstimate(estimate, positionMs)
                return false
            }
        }
        if (!driftCorrectionEnabled) return false
        if (abs(positionMs - lastTrackAtMs) < TRACK_INTERVAL_MS) return false
        lastTrackAtMs = positionMs

        val trackFrom = (positionMs - TRACK_WINDOW_MS).coerceAtLeast(0)
        val firstHeard = timeline.firstKnownFrame(trackFrom / FRAME, positionMs / FRAME)
        if (firstHeard < 0) return false
        val windowStart = maxOf(trackFrom, firstHeard * FRAME)
        val estimate = OffsetEstimator.estimate(
            timeline, cues, windowStart, positionMs, map.scale,
            searchCenterMs = map.offsetMs.toLong() + map.manualMs, searchRadiusMs = TRACK_RADIUS_MS,
        ) ?: return false
        if (estimate.zScore < TRACK_MIN_Z || estimate.coverage < OffsetEstimate.MIN_COVERAGE) return false

        val center = (windowStart + positionMs) / 2
        trackPoints.removeAll { abs(it.mediaMs - center) < TRACK_INTERVAL_MS / 2 }
        trackPoints.add(TrackPoint(center, (estimate.offsetMs - map.manualMs).toDouble(), estimate.zScore))
        if (trackPoints.size > MAX_POINTS) trackPoints.removeAt(0)
        return refit(positionMs)
    }

    /**
     * Theil–Sen fit of local offsets vs. media time. Offsets are measured under the current scale,
     * so a non-zero slope β means the scale is off: media·(1−β) = sub·scale + b₀.
     */
    private fun refit(positionMs: Long): Boolean {
        if (trackPoints.size < MIN_POINTS) return false
        val span = trackPoints.maxOf { it.mediaMs } - trackPoints.minOf { it.mediaMs }
        if (span < MIN_SPAN_MS) return false

        val slopes = ArrayList<Double>()
        for (i in trackPoints.indices) for (j in i + 1 until trackPoints.size) {
            val dx = (trackPoints[j].mediaMs - trackPoints[i].mediaMs).toDouble()
            if (abs(dx) >= 60_000) slopes.add((trackPoints[j].offsetMs - trackPoints[i].offsetMs) / dx)
        }
        if (slopes.isEmpty()) return false
        slopes.sort()
        var beta = slopes[slopes.size / 2]
        val intercepts = trackPoints.map { it.offsetMs - beta * it.mediaMs }.sorted()
        var b0 = intercepts[intercepts.size / 2]
        val residuals = trackPoints.map { abs(it.offsetMs - (b0 + beta * it.mediaMs)) }.sorted()
        if (residuals[residuals.size / 2] > MAX_RESIDUAL_MS) return false

        if (abs(beta) < MIN_SLOPE) {
            // No drift: just a constant correction if the points agree on one.
            beta = 0.0
            b0 = trackPoints.map { it.offsetMs }.sorted()[trackPoints.size / 2]
        }
        val newScale = map.scale / (1 - beta)
        val newOffset = b0 / (1 - beta)
        val candidate = SubtitleTimeMap(newOffset, newScale, map.manualMs)
        val delta = abs(candidate.effectiveShiftAt(positionMs) - map.effectiveShiftAt(positionMs))
        if (delta < MIN_APPLY_MS) return false
        map = candidate
        corrections++
        lastZ = trackPoints.last().z
        // Points were measured against the old mapping; re-express them under the new one.
        trackPoints.replaceAll { it.copy(offsetMs = newOffset) }
        status = synced()
        return true
    }

    private fun synced() = SubtitleSyncStatus.Synced(
        shiftMs = map.offsetMs.toLong(),
        scale = map.scale,
        zScore = lastZ,
        corrections = corrections,
    )

    companion object {
        private const val FRAME = SpeechActivityDetector.FRAME_MS.toLong()
        const val MIN_CUES = 8
        const val INITIAL_AUDIO_MS = 60_000L
        const val INITIAL_WINDOW_MS = 300_000L
        const val WIDE_RADIUS_MS = 60_000L
        /** Give up (and say so) if this much audio produced no reliable match. */
        const val GIVE_UP_AFTER_MS = 240_000L
        /** Heard-audio amounts at which the wide search is repeated with more evidence. */
        private val CHECKPOINTS_MS = longArrayOf(150_000L, 290_000L)
        private const val RETRY_UNCERTAIN_MS = 300_000L
        const val TRACK_INTERVAL_MS = 60_000L
        const val TRACK_WINDOW_MS = 180_000L
        private const val TRACK_RADIUS_MS = 3_000L
        private const val TRACK_MIN_Z = 2.5
        private const val MAX_POINTS = 12
        private const val MIN_POINTS = 3
        private const val MIN_SPAN_MS = 240_000L
        private const val MAX_RESIDUAL_MS = 150.0
        /** 0.02 % ≈ 0.7 s per hour; smaller slopes are treated as noise. */
        private const val MIN_SLOPE = 2e-4
        const val MIN_APPLY_MS = 120L
        private const val SCALE_PREFERENCE_MARGIN = 1.25

        /** Scale 1 plus common frame-rate conversions (sub·scale = media). */
        val SCALES = doubleArrayOf(
            1.0,
            25.0 / 23.976, 23.976 / 25.0,
            24.0 / 23.976, 23.976 / 24.0,
            25.0 / 24.0, 24.0 / 25.0,
        ).toList()
    }
}
