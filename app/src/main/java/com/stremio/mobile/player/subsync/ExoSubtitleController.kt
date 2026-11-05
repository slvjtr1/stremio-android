package com.stremio.mobile.player.subsync

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns subtitle presentation for the ExoPlayer backend so that timing can be shifted:
 *
 * * **Subtitle files** (addon / imported): fetched and parsed with Media3's own parsers, then
 *   rendered against the playhead through a [SubtitleTimeMap] (manual delay + automatic offset +
 *   drift scale). These tracks support automatic synchronisation.
 * * **Embedded tracks**: cues come from the player as usual; a positive delay is applied by
 *   holding cues back. Content cannot be shown before the decoder delivers it, so negative
 *   delays are limited to zero for embedded tracks.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class ExoSubtitleController(
    context: Context,
    private val dataSourceFactory: DataSource.Factory,
    private val scope: CoroutineScope,
    private val positionMs: () -> Long,
    private val isPlaying: () -> Boolean,
) {
    val engine = SubtitleSyncEngine()
    val audioTap = SpeechAudioTap(engine.timeline)

    val view: SubtitleView = SubtitleView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
    }

    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val mutableStatus = MutableStateFlow<SubtitleSyncStatus>(SubtitleSyncStatus.Off)
    val status: StateFlow<SubtitleSyncStatus> = mutableStatus

    private val mutableMap = MutableStateFlow(SubtitleTimeMap())
    val map: StateFlow<SubtitleTimeMap> = mutableMap

    private var mode: Mode = Mode.None
    private var timeline: CueTimeline = CueTimeline.EMPTY
    private var lastShown: List<Cue>? = null
    private var manualDelayMs = 0L
    private var autoSyncPreferred = true
    private var driftPreferred = true

    private val embeddedQueue = ArrayDeque<Pair<Long, List<Cue>>>()
    private var embeddedCurrent: List<Cue> = emptyList()

    private var loadJob: Job? = null
    private var renderJob: Job? = null
    private var analysisJob: Job? = null

    private sealed interface Mode {
        data object None : Mode
        data class External(val key: String) : Mode
        data object Embedded : Mode
    }

    /**
     * Places the controlled view where PlayerView's own subtitle view lives (inside the video
     * content frame, so positioned ASS/SSA cues keep their geometry) and hides the default one.
     */
    fun attachTo(overlay: FrameLayout?, defaultView: SubtitleView?) {
        (view.parent as? ViewGroup)?.removeView(view)
        val parent = defaultView?.parent as? ViewGroup
        if (parent != null) {
            defaultView.visibility = View.GONE
            parent.addView(view, parent.indexOfChild(defaultView) + 1, defaultView.layoutParams)
        } else {
            overlay?.addView(view, 0)
        }
        renderNow(force = true)
    }

    fun setPreferences(autoSync: Boolean, driftCorrection: Boolean) {
        autoSyncPreferred = autoSync
        driftPreferred = driftCorrection
        scope.launch(worker) {
            engine.driftCorrectionEnabled = driftCorrection
            engine.autoSyncEnabled = autoSync && mode is Mode.External
            publish()
        }
    }

    fun setManualDelay(delayMs: Long) {
        if (delayMs == manualDelayMs) return
        manualDelayMs = delayMs
        mutableMap.value = mutableMap.value.copy(manualMs = delayMs)
        scope.launch(worker) {
            engine.setManualOffset(delayMs)
            publish()
        }
        renderNow(force = true)
    }

    fun resetAutoSync() {
        scope.launch(worker) {
            engine.reset()
            publish()
        }
    }

    /** New media: forget all audio evidence. Call before preparing a new item. */
    fun onNewMedia(startPositionMs: Long) {
        audioTap.anchorAt(startPositionMs)
        scope.launch(worker) { engine.timeline.clear() }
        clearEmbedded()
    }

    fun onSeek(targetMs: Long) {
        audioTap.anchorAt(targetMs)
        clearEmbedded()
        renderNow(force = true)
    }

    fun onSubtitlesDisabled() = select(Mode.None, null)

    /** Selected track is an addon/imported file. */
    fun selectExternal(id: String, url: String) = select(Mode.External("$id|$url"), url)

    fun selectEmbedded() = select(Mode.Embedded, null)

    /** Player text output; only used for embedded tracks. */
    fun onPlayerCues(group: CueGroup) {
        if (mode != Mode.Embedded) return
        val at = if (group.presentationTimeUs != C.TIME_UNSET) group.presentationTimeUs / 1000 else positionMs()
        embeddedQueue.addLast(at to group.cues)
        while (embeddedQueue.size > 64) embeddedQueue.removeFirst()
        renderNow()
    }

    fun release() {
        loadJob?.cancel()
        renderJob?.cancel()
        analysisJob?.cancel()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun select(newMode: Mode, url: String?) {
        if (newMode == mode) return
        mode = newMode
        loadJob?.cancel()
        timeline = CueTimeline.EMPTY
        clearEmbedded()
        view.setCues(emptyList())
        lastShown = null
        when (newMode) {
            Mode.None -> {
                scope.launch(worker) {
                    engine.setCues(null)
                    engine.autoSyncEnabled = false
                    publish()
                }
                stopLoops()
            }
            Mode.Embedded -> {
                scope.launch(worker) {
                    engine.autoSyncEnabled = false
                    engine.setCues(null)
                    publish(SubtitleSyncStatus.Unavailable("Automatic sync works with addon and imported subtitle files"))
                }
                startRenderLoop()
                analysisJob?.cancel()
            }
            is Mode.External -> {
                loadJob = scope.launch {
                    val parsed = withContext(Dispatchers.IO) { loadCues(Uri.parse(url)) }
                    if (mode != newMode) return@launch
                    timeline = parsed ?: CueTimeline.EMPTY
                    withContext(worker) {
                        engine.driftCorrectionEnabled = driftPreferred
                        engine.autoSyncEnabled = autoSyncPreferred
                        engine.setCues(
                            parsed?.intervals(),
                            unavailableReason = if (parsed == null) "Subtitle file could not be read" else null,
                        )
                        publish()
                    }
                    startRenderLoop()
                    startAnalysisLoop()
                }
            }
        }
    }

    private fun stopLoops() {
        renderJob?.cancel()
        analysisJob?.cancel()
    }

    private fun clearEmbedded() {
        embeddedQueue.clear()
        embeddedCurrent = emptyList()
    }

    private fun startRenderLoop() {
        renderJob?.cancel()
        renderJob = scope.launch {
            while (isActive) {
                val wait = renderNow()
                delay(if (isPlaying()) wait else 250L)
            }
        }
    }

    private fun startAnalysisLoop() {
        analysisJob?.cancel()
        analysisJob = scope.launch {
            while (isActive) {
                delay(ANALYSIS_PERIOD_MS)
                if (!isPlaying()) continue
                val pos = positionMs()
                audioTap.publishPosition(pos)
                if (!audioTap.receivingPcm) {
                    if (engine.autoSyncEnabled) {
                        publish(SubtitleSyncStatus.Unavailable("Audio is not decoded by the app (passthrough), so speech cannot be analysed"))
                    }
                    continue
                }
                withContext(worker) {
                    if (engine.analyze(pos)) Timber.d("Subtitle sync updated: %s", engine.map)
                    publish()
                }
            }
        }
    }

    private fun publish(override: SubtitleSyncStatus? = null) {
        mutableStatus.value = override ?: engine.status
        mutableMap.value = engine.map.copy(manualMs = manualDelayMs)
    }

    /** Updates the view for the current playhead; returns ms until the next cue boundary. */
    private fun renderNow(force: Boolean = false): Long {
        val pos = positionMs()
        val (cues, nextChangeMs) = when (mode) {
            is Mode.External -> externalCuesAt(pos)
            Mode.Embedded -> embeddedCuesAt(pos)
            Mode.None -> emptyList<Cue>() to Long.MAX_VALUE
        }
        if (force || cues !== lastShown) {
            if (force || cues != lastShown) view.setCues(cues)
            lastShown = cues
        }
        return nextChangeMs.coerceIn(MIN_TICK_MS, MAX_TICK_MS)
    }

    private fun externalCuesAt(mediaMs: Long): Pair<List<Cue>, Long> = cuesForMedia(timeline, mutableMap.value, mediaMs)

    private fun embeddedCuesAt(mediaMs: Long): Pair<List<Cue>, Long> {
        val delayMs = manualDelayMs.coerceAtLeast(0)
        while (embeddedQueue.isNotEmpty() && embeddedQueue.first().first + delayMs <= mediaMs) {
            embeddedCurrent = embeddedQueue.removeFirst().second
        }
        val next = embeddedQueue.firstOrNull()?.let { it.first + delayMs - mediaMs } ?: MAX_TICK_MS
        return embeddedCurrent to next
    }

    private fun loadCues(uri: Uri): CueTimeline? = runCatching {
        val dataSource = dataSourceFactory.createDataSource()
        val bytes = try {
            dataSource.open(DataSpec(uri))
            DataSourceUtil.readToEnd(dataSource)
        } finally {
            dataSource.close()
        }
        if (bytes.size > MAX_SUBTITLE_BYTES) null else CueTimeline.parse(uri.toString(), bytes)
    }.onFailure { Timber.w(it, "Subtitle load failed") }.getOrNull()

    companion object {
        /**
         * Cues to show at media time [mediaMs] under [map], and the media-time delay until the
         * next change (so rendering can sleep until then).
         */
        internal fun cuesForMedia(timeline: CueTimeline, map: SubtitleTimeMap, mediaMs: Long): Pair<List<Cue>, Long> {
            if (timeline.isEmpty) return emptyList<Cue>() to MAX_TICK_MS
            val subUs = map.subtitleTimeAt(mediaMs) * 1000
            val (cues, untilUs) = timeline.cuesAt(subUs)
            val untilMs = if (untilUs == Long.MAX_VALUE) MAX_TICK_MS else (untilUs / 1000.0 * map.scale).toLong()
            return cues to untilMs
        }

        private const val ANALYSIS_PERIOD_MS = 5_000L
        private const val MIN_TICK_MS = 16L
        private const val MAX_TICK_MS = 500L
        private const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024
    }
}
