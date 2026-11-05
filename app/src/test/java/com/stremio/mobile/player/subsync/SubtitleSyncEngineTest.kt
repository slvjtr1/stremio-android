package com.stremio.mobile.player.subsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class SubtitleSyncEngineTest {

    /** Random dialogue: 1–4 s lines separated by 0.3–6 s gaps, in subtitle time. */
    private fun dialogue(durationMs: Long, seed: Int): CueIntervals {
        val rnd = Random(seed)
        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        var t = 2_000L
        while (t < durationMs) {
            val len = 1_000L + rnd.nextLong(3_000)
            starts.add(t)
            ends.add(t + len)
            t += len + 300 + rnd.nextLong(5_700)
        }
        return CueIntervals(starts.toLongArray(), ends.toLongArray())
    }

    /**
     * Writes simulated audio energies for media time [0, untilMs): speech where a cue is shown
     * under [truth], plus noise, unsubtitled speech, music bursts and missing speech.
     */
    private fun simulateAudio(
        timeline: ActivityTimeline,
        cues: CueIntervals,
        truth: SubtitleTimeMap,
        fromMs: Long,
        untilMs: Long,
        seed: Int,
    ) {
        val rnd = Random(seed)
        val frame = SpeechActivityDetector.FRAME_MS
        var musicUntil = -1L
        var extraSpeechUntil = -1L
        var muteCueStart = -1L
        var f = fromMs / frame
        while (f < untilMs / frame) {
            val m = f * frame
            val s = truth.subtitleTimeAt(m)
            var idx = cues.starts.binarySearch(s)
            if (idx < 0) idx = -idx - 2
            val inCue = idx >= 0 && s < cues.ends[idx]
            if (inCue && idx >= 0 && cues.starts[idx] != muteCueStart && rnd.nextDouble() < 0.0005) {
                muteCueStart = cues.starts[idx] // a line with no audible speech
            }
            if (m > musicUntil && rnd.nextDouble() < 0.0004) musicUntil = m + 2_000 + rnd.nextLong(8_000)
            if (m > extraSpeechUntil && rnd.nextDouble() < 0.0003) extraSpeechUntil = m + 500 + rnd.nextLong(2_500)
            val speaking = (inCue && cues.starts.getOrNull(idx) != muteCueStart) || m < extraSpeechUntil
            var e = -9.0 + rnd.nextDouble() * 1.5
            if (speaking) e = -4.5 + 1.2 * sin(m / 90.0) + rnd.nextDouble() * 1.5
            if (m < musicUntil) e = maxOf(e, -5.0 + rnd.nextDouble() * 2.0)
            timeline.put(f, e.toFloat())
            f++
        }
    }

    private fun play(engine: SubtitleSyncEngine, truth: SubtitleTimeMap, cues: CueIntervals, untilMs: Long, seed: Int, fromMs: Long = 0) {
        var pos = fromMs
        while (pos < untilMs) {
            val next = pos + 10_000
            simulateAudio(engine.timeline, cues, truth, pos, next, seed + (pos / 10_000).toInt())
            engine.analyze(next)
            if (System.getenv("SYNC_DEBUG") != null) println("pos=${next / 1000}s status=${engine.status} map=${engine.map} ${engine.lastDebug}")
            pos = next
        }
    }

    @Test
    fun detectsConstantLateOffset() {
        val cues = dialogue(30 * 60_000L, seed = 1)
        val truth = SubtitleTimeMap(offsetMs = 2_350.0)
        val engine = SubtitleSyncEngine().apply { setCues(cues) }
        play(engine, truth, cues, untilMs = 3 * 60_000L, seed = 11)
        val status = engine.status
        assertTrue("status $status", status is SubtitleSyncStatus.Synced)
        assertEquals(1.0, engine.map.scale, 1e-9)
        assertEquals(2_350.0, engine.map.offsetMs, 40.0)
    }

    @Test
    fun detectsConstantEarlyOffset() {
        val cues = dialogue(30 * 60_000L, seed = 2)
        val truth = SubtitleTimeMap(offsetMs = -17_800.0)
        val engine = SubtitleSyncEngine().apply { setCues(cues) }
        play(engine, truth, cues, untilMs = 3 * 60_000L, seed = 22)
        assertTrue(engine.status is SubtitleSyncStatus.Synced)
        assertEquals(-17_800.0, engine.map.offsetMs, 40.0)
    }

    @Test
    fun listensBeforeEnoughAudio() {
        val cues = dialogue(10 * 60_000L, seed = 3)
        val engine = SubtitleSyncEngine().apply { setCues(cues) }
        play(engine, SubtitleTimeMap(offsetMs = 1_000.0), cues, untilMs = 30_000L, seed = 33)
        val status = engine.status
        assertTrue(status is SubtitleSyncStatus.Listening)
        assertTrue(engine.map.isIdentity)
    }

    @Test
    fun detectsFrameRateMismatch() {
        val cues = dialogue(60 * 60_000L, seed = 4)
        val truth = SubtitleTimeMap(offsetMs = 800.0, scale = 25.0 / 23.976)
        val engine = SubtitleSyncEngine().apply { setCues(cues) }
        play(engine, truth, cues, untilMs = 4 * 60_000L, seed = 44)
        assertTrue(engine.status is SubtitleSyncStatus.Synced)
        // Error at the 40 minute mark must stay well below perceptible.
        val at = 40 * 60_000L
        val err = abs(engine.map.mediaTimeOf(truth.subtitleTimeAt(at)) - at)
        assertTrue("error ${err}ms", err < 150)
    }

    @Test
    fun correctsProgressiveDrift() {
        // Small drift (0.1 %, 3.6 s/hour) that the frame-rate candidates do not cover.
        val cues = dialogue(80 * 60_000L, seed = 5)
        val truth = SubtitleTimeMap(offsetMs = 1_200.0, scale = 1.001)
        val engine = SubtitleSyncEngine().apply { setCues(cues) }
        play(engine, truth, cues, untilMs = 40 * 60_000L, seed = 55)
        assertTrue(engine.status is SubtitleSyncStatus.Synced)
        val at = 70 * 60_000L
        val err = abs(engine.map.mediaTimeOf(truth.subtitleTimeAt(at)) - at)
        assertTrue("error ${err}ms at 70 min (scale ${engine.map.scale})", err < 300)
    }

    @Test
    fun driftCorrectionCanBeDisabled() {
        val cues = dialogue(80 * 60_000L, seed = 5)
        val truth = SubtitleTimeMap(offsetMs = 1_200.0, scale = 1.001)
        val engine = SubtitleSyncEngine().apply {
            driftCorrectionEnabled = false
            setCues(cues)
        }
        play(engine, truth, cues, untilMs = 40 * 60_000L, seed = 55)
        assertEquals(1.0, engine.map.scale, 1e-9)
    }

    @Test
    fun refusesUnrelatedSubtitles() {
        val cues = dialogue(30 * 60_000L, seed = 6)
        val other = dialogue(30 * 60_000L, seed = 999)
        val engine = SubtitleSyncEngine().apply { setCues(cues) }
        // Audio follows a different script entirely.
        play(engine, SubtitleTimeMap(), other, untilMs = 6 * 60_000L, seed = 66)
        assertFalse(engine.status is SubtitleSyncStatus.Synced)
        assertTrue(engine.map.isIdentity)
    }

    @Test
    fun manualOffsetIsPreservedAndAdditive() {
        val cues = dialogue(30 * 60_000L, seed = 7)
        val engine = SubtitleSyncEngine().apply {
            setManualOffset(500)
            setCues(cues)
        }
        // Audio truth includes the user's +500 so auto should find the remaining 1500.
        play(engine, SubtitleTimeMap(offsetMs = 1_500.0, manualMs = 500), cues, untilMs = 3 * 60_000L, seed = 77)
        assertEquals(500L, engine.map.manualMs)
        assertEquals(1_500.0, engine.map.offsetMs, 40.0)
        engine.reset()
        assertEquals(500L, engine.map.manualMs)
        assertEquals(0.0, engine.map.offsetMs, 0.0)
    }

    @Test
    fun seekingKeepsMappingAndUsesNewAudio() {
        val cues = dialogue(60 * 60_000L, seed = 8)
        val truth = SubtitleTimeMap(offsetMs = -3_000.0)
        val engine = SubtitleSyncEngine().apply { setCues(cues) }
        // Start watching at 30 minutes (resume), not from the beginning.
        play(engine, truth, cues, untilMs = 33 * 60_000L, seed = 88, fromMs = 30 * 60_000L)
        assertTrue(engine.status is SubtitleSyncStatus.Synced)
        assertEquals(-3_000.0, engine.map.offsetMs, 40.0)
    }

    @Test
    fun tooFewCuesIsUnavailable() {
        val engine = SubtitleSyncEngine()
        engine.setCues(CueIntervals.of(0L to 1000L, 2000L to 3000L))
        assertTrue(engine.status is SubtitleSyncStatus.Unavailable)
        assertFalse(engine.analyze(100_000))
    }

    @Test
    fun timeMapRoundTrips() {
        val map = SubtitleTimeMap(offsetMs = 1234.0, scale = 25.0 / 23.976, manualMs = -200)
        for (s in longArrayOf(0, 1_000, 3_600_000)) {
            assertEquals(s.toDouble(), map.subtitleTimeAt(map.mediaTimeOf(s)).toDouble(), 1.0)
        }
    }
}
