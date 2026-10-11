package com.bibot.app.core.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer

class AudioBridge(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    fun playStream(url: String, speed: Float = 0.85f) {
        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.playbackParameters = PlaybackParameters(speed)
        player.prepare()
        player.play()
    }

    fun stop() {
        player.stop()
    }

    fun release() {
        player.release()
    }
}

