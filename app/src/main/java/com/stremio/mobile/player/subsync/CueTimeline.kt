package com.stremio.mobile.player.subsync

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Parsed subtitle file with fast lookup of the cues visible at a subtitle-time instant.
 * Parsing uses Media3's own parsers (SubRip, WebVTT, SSA/ASS, TTML), so styling and
 * positioning match what ExoPlayer would render itself.
 */
@OptIn(UnstableApi::class)
class CueTimeline(cues: List<CuesWithTiming>) {
    private val list = cues.sortedBy { it.startTimeUs }
    private val maxDurationUs = list.maxOfOrNull { if (it.durationUs == C.TIME_UNSET) 0L else it.durationUs } ?: 0L

    val isEmpty: Boolean get() = list.isEmpty()

    /** Cue intervals (ms) of text cues, for the sync engine. */
    fun intervals(): CueIntervals {
        val textual = list.filter { c -> c.durationUs != C.TIME_UNSET && c.cues.any { !it.text.isNullOrBlank() } }
        return CueIntervals(
            LongArray(textual.size) { textual[it].startTimeUs / 1000 },
            LongArray(textual.size) { textual[it].endTimeUs / 1000 },
        )
    }

    /**
     * Cues visible at subtitle time [subUs], plus the subtitle-time distance (µs) to the next
     * start or end boundary (Long.MAX_VALUE if none).
     */
    fun cuesAt(subUs: Long): Pair<List<Cue>, Long> {
        if (list.isEmpty()) return emptyList<Cue>() to Long.MAX_VALUE
        var lo = 0
        var hi = list.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startTimeUs <= subUs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        var nextBoundary = if (idx + 1 < list.size) list[idx + 1].startTimeUs else Long.MAX_VALUE
        val active = ArrayList<Cue>(2)
        var i = idx
        while (i >= 0 && list[i].startTimeUs >= subUs - maxDurationUs) {
            val c = list[i]
            val end = if (c.durationUs == C.TIME_UNSET) Long.MAX_VALUE else c.endTimeUs
            if (subUs < end) {
                active.addAll(0, c.cues)
                if (end < nextBoundary) nextBoundary = end
            }
            i--
        }
        val until = if (nextBoundary == Long.MAX_VALUE) Long.MAX_VALUE else nextBoundary - subUs
        return (if (active.isEmpty()) emptyList() else active) to until
    }

    companion object {
        val EMPTY = CueTimeline(emptyList())

        /** Parses [bytes] (format from [url] extension or content); null if unsupported. */
        fun parse(url: String, bytes: ByteArray): CueTimeline? {
            val format = Format.Builder().setSampleMimeType(SubtitleFormats.mimeFor(url, bytes)).build()
            val factory = DefaultSubtitleParserFactory()
            if (!factory.supportsFormat(format)) return null
            val out = ArrayList<CuesWithTiming>()
            factory.create(format).parse(bytes, SubtitleParser.OutputOptions.allCues()) { out.add(it) }
            return CueTimeline(out)
        }
    }
}
