package com.stremio.mobile.player.subsync

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CueTimelineTest {
    private val srt = """
        1
        00:00:01,000 --> 00:00:03,000
        Hello there.

        2
        00:00:05,500 --> 00:00:07,000
        <i>General Kenobi.</i>

        3
        00:01:00,000 --> 00:01:02,000
        Later line
    """.trimIndent().toByteArray()

    private fun text(cues: Pair<List<androidx.media3.common.text.Cue>, Long>) =
        cues.first.joinToString("|") { it.text.toString() }

    @Test
    fun parsesSrtAndLooksUpCues() {
        val tl = CueTimeline.parse("https://subs.example/file.srt", srt)
        assertNotNull(tl)
        tl!!
        assertEquals("", text(tl.cuesAt(500_000)))
        assertEquals("Hello there.", text(tl.cuesAt(1_000_000)))
        assertEquals("Hello there.", text(tl.cuesAt(2_999_000)))
        assertEquals("", text(tl.cuesAt(3_000_000)))
        assertEquals("General Kenobi.", text(tl.cuesAt(6_000_000)))
        // Next boundary from 2 s is the end of the first cue at 3 s.
        assertEquals(1_000_000L, tl.cuesAt(2_000_000).second)
        val iv = tl.intervals()
        assertEquals(3, iv.size)
        assertEquals(5_500L, iv.starts[1])
        assertEquals(7_000L, iv.ends[1])
    }

    @Test
    fun mapShiftsAndScalesRendering() {
        val tl = CueTimeline.parse("x.srt", srt)!!
        val late = SubtitleTimeMap(offsetMs = 2_000.0)
        // With +2 s the first line shows from 3 s to 5 s of media time.
        assertEquals("", text(ExoSubtitleController.cuesForMedia(tl, late, 2_500)))
        assertEquals("Hello there.", text(ExoSubtitleController.cuesForMedia(tl, late, 3_500)))
        val manual = SubtitleTimeMap(manualMs = -500)
        assertEquals("Hello there.", text(ExoSubtitleController.cuesForMedia(tl, manual, 600)))
        // 25 vs 23.976 fps: the 60 s line appears at ~62.56 s of media time.
        val drift = SubtitleTimeMap(scale = 25.0 / 23.976)
        assertEquals("", text(ExoSubtitleController.cuesForMedia(tl, drift, 61_000)))
        assertEquals("Later line", text(ExoSubtitleController.cuesForMedia(tl, drift, 63_000)))
    }

    @Test
    fun parsesWebVttWithoutExtension() {
        val vtt = "WEBVTT\n\n00:00:02.000 --> 00:00:04.000\nVTT line\n".toByteArray()
        assertEquals(MimeTypes.TEXT_VTT, SubtitleFormats.mimeFor("https://addon.example/sub/123", vtt))
        val tl = CueTimeline.parse("https://addon.example/sub/123", vtt)!!
        assertEquals("VTT line", text(tl.cuesAt(3_000_000)))
    }

    @Test
    fun parsesAssDialogue() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, Alignment
            Style: Default,Arial,20,&H00FFFFFF,2

            [Events]
            Format: Layer, Start, End, Style, Text
            Dialogue: 0,0:00:10.00,0:00:12.50,Default,Styled line
        """.trimIndent().toByteArray()
        assertEquals(MimeTypes.TEXT_SSA, SubtitleFormats.mimeFor("https://addon.example/sub", ass))
        val tl = CueTimeline.parse("https://addon.example/sub", ass)!!
        assertEquals("Styled line", text(tl.cuesAt(11_000_000)))
        assertTrue(tl.intervals().size == 1)
    }

    @Test
    fun overlappingCuesAreAllVisible() {
        val overlap = """
            1
            00:00:01,000 --> 00:00:10,000
            Long sign

            2
            00:00:02,000 --> 00:00:03,000
            Short line
        """.trimIndent().toByteArray()
        val tl = CueTimeline.parse("o.srt", overlap)!!
        val shown = text(tl.cuesAt(2_500_000))
        assertTrue(shown, shown.contains("Long sign") && shown.contains("Short line"))
        assertEquals("Long sign", text(tl.cuesAt(5_000_000)))
    }
}
