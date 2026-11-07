package com.auralis.app.playback

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.auralis.app.domain.model.AppSettings

/**
 * Volume-based crossfade on the service-owned ExoPlayer. The outgoing track fades down during its
 * final seconds and the incoming track fades up after the Media3 transition. ExoPlayer preloads the
 * next item, so the transition stays gapless on supported formats. Beat matching is deliberately
 * not offered because ExoPlayer has no reliable tempo/BPM engine.
 */
class CrossfadeController(private val player: ExoPlayer) {
    private var seconds = 0
    private var baseVolume = 1f
    private var fadeInUntilMs = 0L
    private var lastVolume = -1f

    fun update(settings: AppSettings) {
        seconds = settings.crossfadeSeconds.coerceIn(0, 12)
        baseVolume = settings.volume.coerceIn(0f, 1f)
        if (seconds == 0) setVolume(baseVolume)
    }

    /** Called on the service ticker while playback is active. */
    fun tick() {
        if (!player.isPlaying) {
            setVolume(baseVolume)
            return
        }
        val duration = player.duration
        val position = player.currentPosition
        val fadeMs = seconds * 1_000L
        if (fadeMs > 0 && duration > 0 && player.hasNextMediaItem() && position >= 0) {
            val remaining = duration - position
            if (remaining in 0..fadeMs) {
                val factor = (remaining.toFloat() / fadeMs).coerceIn(0f, 1f)
                setVolume(baseVolume * factor)
                return
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now < fadeInUntilMs) {
            val remaining = (fadeInUntilMs - now).toFloat() / FADE_IN_MS
            setVolume(baseVolume * (1f - remaining).coerceIn(0f, 1f))
        } else {
            setVolume(baseVolume)
        }
    }

    fun onMediaItemTransition(reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && seconds > 0) {
            fadeInUntilMs = SystemClock.elapsedRealtime() + FADE_IN_MS
        }
    }

    fun onPlaybackResumed() {
        if (seconds > 0) fadeInUntilMs = SystemClock.elapsedRealtime() + FADE_IN_MS
    }

    fun reset() {
        fadeInUntilMs = 0L
        setVolume(baseVolume)
    }

    private fun setVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(clamped - lastVolume) > 0.002f) {
            lastVolume = clamped
            runCatching { player.volume = clamped }
        }
    }

    private companion object {
        const val FADE_IN_MS = 700L
    }
}
