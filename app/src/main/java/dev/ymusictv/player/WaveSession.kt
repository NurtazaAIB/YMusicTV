package dev.ymusictv.player

import dev.ymusictv.api.ModernWaveApi
import dev.ymusictv.api.YandexMusicApi
import dev.ymusictv.model.Track
import dev.ymusictv.model.WaveSettings

/** My Wave backed by the current Yandex rotor/session protocol. */
class WaveSession(private val api: YandexMusicApi) {
    private val modern = ModernWaveApi { api.token }
    private val queue = ArrayDeque<Track>()
    private var batchId: String? = null
    private var sessionId: String? = null
    private var current: Track? = null
    private var settings = WaveSettings()

    /** Starts a genuinely fresh Wave using Yandex serializedSeed values. */
    suspend fun start(newSettings: WaveSettings = settings): Track? {
        settings = newSettings
        queue.clear(); batchId = null; sessionId = null; current = null

        val batch = modern.start(settings)
        sessionId = batch.sessionId
        batchId = batch.batchId
        batch.tracks.forEach(queue::addLast)
        return nextInternal()
    }

    /** Settings are applied by creating a new seed-based rotor session. */
    suspend fun applySettings(newSettings: WaveSettings) {
        settings = newSettings
        queue.clear(); batchId = null; sessionId = null; current = null
    }

    suspend fun next(playedSeconds: Long, skipped: Boolean): Track? {
        current?.let { t ->
            // Keep legacy feedback as a best-effort signal until session feedback batching is wired.
            runCatching { api.waveFeedback(if (skipped) "skip" else "trackFinished", t.id, batchId, playedSeconds) }
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
        return t
    }

    private suspend fun refill(after: String?) {
        val sid = sessionId ?: return
        val batch = modern.next(sid, after)
        batchId = batch.batchId.ifBlank { batchId.orEmpty() }
        val seen = queue.map { it.id }.toMutableSet().apply { current?.let { add(it.id) } }
        batch.tracks.filter { seen.add(it.id) }.forEach(queue::addLast)
    }
}
