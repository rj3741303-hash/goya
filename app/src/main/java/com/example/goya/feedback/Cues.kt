package com.example.goya.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Non-visual feedback for a user who cannot read the screen.
 *
 * Two problems this solves for an elderly user:
 *  - Hearing loss: a short vibration confirms "I found text and I am about to read it", so the
 *    user knows the phone is working even if the first words are missed.
 *  - Silent phone: an app that stays quiet because the volume is at 10% is indistinguishable
 *    from a broken app. [ensureAudible] raises the relevant streams to a usable level.
 */
class Cues(context: Context) {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    private var focusRequest: AudioFocusRequest? = null

    /** Two short taps: "text found, reading now". */
    fun textFound() = vibrate(longArrayOf(0, 35, 70, 35))

    /** One short tap: acknowledges a screen touch (repeat last sentence). */
    fun tapAck() = vibrate(longArrayOf(0, 25))

    /**
     * Makes sure the phone is actually loud enough to be heard.
     *
     * Both streams are raised, because the two speech backends land on different ones:
     *  - SherpaTtsEngine's AudioTrack declares USAGE_ASSISTANCE_ACCESSIBILITY, which Android 8+
     *    routes to STREAM_ACCESSIBILITY -- a stream that is completely independent of media
     *    volume, so raising STREAM_MUSIC alone would have no effect on it at all.
     *  - SystemTtsEngine (the fallback) normally plays on STREAM_MUSIC.
     *
     * Only raises a stream that is below [minFraction] of maximum, and never lowers one, so a
     * user who deliberately turned the volume down is not overridden on every launch.
     */
    fun ensureAudible(minFraction: Float = 0.6f, targetFraction: Float = 0.8f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            raiseStream(AudioManager.STREAM_ACCESSIBILITY, minFraction, targetFraction)
        }
        raiseStream(AudioManager.STREAM_MUSIC, minFraction, targetFraction)
    }

    private fun raiseStream(stream: Int, minFraction: Float, targetFraction: Float) {
        runCatching {
            val max = audio.getStreamMaxVolume(stream)
            val current = audio.getStreamVolume(stream)
            if (max > 0 && current < max * minFraction) {
                val target = (max * targetFraction).toInt().coerceIn(1, max)
                audio.setStreamVolume(stream, target, 0)
            }
        }.onFailure {
            // Do Not Disturb can block volume changes; not fatal.
            Log.w(TAG, "could not adjust stream $stream: ${it.message}")
        }
    }

    /** Ducks music/podcasts playing in the background instead of talking over them. */
    fun requestFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attributes)
                        .setWillPauseWhenDucked(false)
                        .build()
                focusRequest = request
                audio.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    fun abandonFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audio.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audio.abandonAudioFocus(null)
            }
        }
    }

    private fun vibrate(pattern: LongArray) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(pattern, -1)
            }
        }
    }

    private companion object {
        const val TAG = "Cues"
    }
}
