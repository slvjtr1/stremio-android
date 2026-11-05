package com.stremio.mobile.player.subsync

/**
 * Sparse store of per-frame audio log-energies indexed by media time
 * ([SpeechActivityDetector.FRAME_MS] per frame). Playback can seek, so only the parts of the
 * timeline that were actually heard are filled; unknown frames read as NaN.
 *
 * Memory: 4 bytes per 10 ms frame, allocated in 10 s chunks — a fully played 2 h film is ~2.9 MB.
 */
class ActivityTimeline {
    private val chunks = HashMap<Long, FloatArray>()
    private val lock = Any()

    fun put(frameIndex: Long, value: Float) {
        if (frameIndex < 0) return
        synchronized(lock) {
            val chunk = chunks.getOrPut(frameIndex / CHUNK) { FloatArray(CHUNK) { Float.NaN } }
            chunk[(frameIndex % CHUNK).toInt()] = value
        }
    }

    fun get(frameIndex: Long): Float {
        if (frameIndex < 0) return Float.NaN
        synchronized(lock) {
            val chunk = chunks[frameIndex / CHUNK] ?: return Float.NaN
            return chunk[(frameIndex % CHUNK).toInt()]
        }
    }

    /** Copies frames [from, from + out.size) into [out], NaN where unknown. */
    fun read(from: Long, out: FloatArray) {
        synchronized(lock) {
            for (i in out.indices) {
                val idx = from + i
                if (idx < 0) {
                    out[i] = Float.NaN
                    continue
                }
                val chunk = chunks[idx / CHUNK]
                out[i] = chunk?.get((idx % CHUNK).toInt()) ?: Float.NaN
            }
        }
    }

    fun knownFrames(from: Long, toExclusive: Long): Int {
        var n = 0
        synchronized(lock) {
            var idx = from.coerceAtLeast(0)
            while (idx < toExclusive) {
                val chunk = chunks[idx / CHUNK]
                if (chunk == null) {
                    idx = (idx / CHUNK + 1) * CHUNK
                    continue
                }
                if (!chunk[(idx % CHUNK).toInt()].isNaN()) n++
                idx++
            }
        }
        return n
    }

    /** First frame in [from, toExclusive) with data, or -1. */
    fun firstKnownFrame(from: Long, toExclusive: Long): Long {
        synchronized(lock) {
            var idx = from.coerceAtLeast(0)
            while (idx < toExclusive) {
                val chunk = chunks[idx / CHUNK]
                if (chunk == null) {
                    idx = (idx / CHUNK + 1) * CHUNK
                    continue
                }
                if (!chunk[(idx % CHUNK).toInt()].isNaN()) return idx
                idx++
            }
        }
        return -1
    }

    fun clear() = synchronized(lock) { chunks.clear() }

    companion object {
        const val CHUNK = 1000
    }
}
