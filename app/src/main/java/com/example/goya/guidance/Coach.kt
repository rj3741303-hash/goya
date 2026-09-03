package com.example.goya.guidance

import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import com.example.goya.R
import com.example.goya.speech.Speaker

/**
 * Spoken coaching and automatic torch control.
 *
 * A sighted, literate user who sees nothing happening simply moves the phone. An elderly user who
 * cannot read has no idea whether the app is broken, the paper is upside down, or the room is too
 * dark. Silence is the worst possible feedback, so after a few seconds without any text the app
 * says what to try next, cycling through escalating hints instead of repeating one phrase.
 *
 * Torch control uses hysteresis (on below [darkLuma], off above [brightLuma], each requiring a
 * streak of frames) so the light does not flicker on and off as the camera moves.
 *
 * Called from the analysis thread. Not internally synchronised; drive it from OcrAnalyzer only.
 */
class Coach(
    context: Context,
    private val speaker: Speaker,
    private val onTorch: (Boolean) -> Unit,
    private val quietBeforeHintMs: Long = 5_000L,
    private val betweenHintsMs: Long = 18_000L,
    private val darkLuma: Int = 45,
    private val brightLuma: Int = 85,
    private val streakToSwitch: Int = 6
) {

    private val appContext = context.applicationContext

    private var lastTextAt = SystemClock.elapsedRealtime()
    private var lastHintAt = 0L
    private var hintIndex = 0

    private var darkStreak = 0
    private var brightStreak = 0
    private var torchOn = false
    private var torchAnnouncePending = false
    private var torchAnnounced = false

    /**
     * @param hasText whether this frame produced any readable text
     * @param luma    average frame brightness 0..255, or -1 when unavailable
     */
    fun onFrame(hasText: Boolean, luma: Int) {
        updateTorch(luma)
        announceTorchIfQuiet()

        val now = SystemClock.elapsedRealtime()
        if (hasText) {
            lastTextAt = now
            hintIndex = 0
            return
        }

        // Never talk over a sentence being read, and give the user time to aim first.
        if (speaker.isSpeaking) return
        if (now - lastTextAt < quietBeforeHintMs) return
        if (now - lastHintAt < betweenHintsMs) return

        lastHintAt = now
        val hint = HINTS[hintIndex.coerceAtMost(HINTS.lastIndex)]
        hintIndex++
        speaker.speakNow(appContext.getString(hint))
    }

    private fun updateTorch(luma: Int) {
        if (luma < 0) return

        if (luma < darkLuma) {
            darkStreak++
            brightStreak = 0
        } else if (luma > brightLuma) {
            brightStreak++
            darkStreak = 0
        } else {
            return // between thresholds: leave the torch as it is
        }

        if (!torchOn && darkStreak >= streakToSwitch) {
            torchOn = true
            onTorch(true)
            if (!torchAnnounced) torchAnnouncePending = true
        } else if (torchOn && brightStreak >= streakToSwitch) {
            torchOn = false
            onTorch(false)
        }
    }

    /**
     * The torch usually comes on at the exact moment text is finally being read, so announcing it
     * immediately would cut the sentence in half. The announcement waits for a gap instead, and
     * is dropped entirely if the light goes back off before one appears.
     */
    private fun announceTorchIfQuiet() {
        if (!torchAnnouncePending) return
        if (!torchOn) {
            torchAnnouncePending = false
            return
        }
        if (speaker.isSpeaking) return

        torchAnnouncePending = false
        torchAnnounced = true
        speaker.speakNow(appContext.getString(R.string.spoken_torch_on))
    }

    fun reset() {
        lastTextAt = SystemClock.elapsedRealtime()
        lastHintAt = 0L
        hintIndex = 0
        darkStreak = 0
        brightStreak = 0
        torchAnnouncePending = false
        if (torchOn) {
            torchOn = false
            onTorch(false)
        }
    }

    private companion object {
        /** Escalating advice, spoken in order while nothing is being recognised. */
        @StringRes
        val HINTS = listOf(
            R.string.spoken_ready,
            R.string.spoken_move_closer,
            R.string.spoken_hold_steady,
            R.string.spoken_more_light
        )
    }
}
