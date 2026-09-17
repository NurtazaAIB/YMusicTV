package dev.ymusictv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.ymusictv.model.Track

class TvPlayer(context: Context) {
    private val appContext = context.applicationContext
    private var endedCallback: (() -> Unit)? = null
    private var errorCallback: ((PlaybackException) -> Unit)? = null
    private var player: ExoPlayer? = null

    val exo: ExoPlayer
        get() = player ?: createPlayer().also { player = it }

    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(appContext).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) endedCallback?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                errorCallback?.invoke(error)
            }
        })
    }

    fun play(track: Track, url: String) {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .build()
        exo.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(metadata).build())
        exo.prepare()
        exo.playWhenReady = true
    }

    fun onEnded(block: () -> Unit) { endedCallback = block }
    fun onError(block: (PlaybackException) -> Unit) { errorCallback = block }

    fun release() {
        player?.release()
        player = null
    }
}
