package dev.ymusictv.player

import dev.ymusictv.api.YandexMusicApi
import dev.ymusictv.model.Track
import dev.ymusictv.model.WaveSettings

/** Keeps My Wave as a real radio session: batch id, queue cursor and feedback. */
class WaveSession(private val api: YandexMusicApi) {
    private val queue = ArrayDeque<Track>()
    private var batchId: String? = null
    private var current: Track? = null
    private var settings = WaveSettings()

    suspend fun start(newSettings: WaveSettings = settings): Track? {
        settings = newSettings
        api.setWaveSettings(settings)
        queue.clear(); batchId = null; current = null
        api.waveFeedback("radioStarted")
        refill(null)
        return nextInternal()
    }

    suspend fun next(playedSeconds: Long, skipped: Boolean): Track? {
        current?.let { t ->
            api.waveFeedback(if (skipped) "skip" else "trackFinished", t.id, batchId, playedSeconds)
        }
        if (queue.size < 2) refill(current?.id)
        return nextInternal()
    }

    suspend fun likeCurrent(): Boolean = current?.let { api.likeTrack(it.id) } ?: false
    suspend fun dislikeAndSkip(playedSeconds: Long): Track? {
        current?.let { api.dislikeTrack(it.id) }
        return next(playedSeconds, true)
    }

    fun currentTrack(): Track? = current

    private suspend fun nextInternal(): Track? {
        val t = queue.removeFirstOrNull() ?: return null
        current = t
        api.waveFeedback("trackStarted", t.id, batchId)
        return t
    }

    private suspend fun refill(after: String?) {
        val batch = api.myWave(after)
        batchId = batch.batchId.ifBlank { batchId.orEmpty() }
        val seen = queue.map { it.id }.toMutableSet().apply { current?.let { add(it.id) } }
        batch.tracks.filter { seen.add(it.id) }.forEach(queue::addLast)
    }
}
