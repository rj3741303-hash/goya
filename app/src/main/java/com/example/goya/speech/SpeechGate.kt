package com.example.goya.speech

import android.os.SystemClock
import com.example.goya.text.TextNormalizer

/**
 * Decides when detected text becomes speech. This is the whole "smart debouncing" layer.
 *
 * Rules:
 *  1. Text must appear on [stableFrames] consecutive frames before it is spoken (kills OCR flicker).
 *  2. Text at least [sameThreshold] similar to the last utterance is NOT repeated while the camera
 *     sits still, until [repeatCooldownMs] has passed since it was spoken (deliberate re-read).
 *  3. A materially different frame (below [changeThreshold] similarity) interrupts current speech.
 *  4. [emptyFramesToCancel] consecutive text-free frames cancel speech and clear memory, so pointing
 *     back at the same sign reads it again immediately.
 *
 * ## Threading
 *
 * Every entry point ([submit], [repeatLast], [reset]) must run on the **same** single analysis
 * thread. The tap handler lives on the main thread, so CameraReaderScreen posts [repeatLast] onto
 * the analysis executor rather than calling it directly. The `lastSpoken*` fields are additionally
 * marked `@Volatile` as a safety net, since they are the only state a stray cross-thread call
 * would touch.
 */
class SpeechGate(
    private val speaker: Speaker,
    private val stableFrames: Int = 2,
    private val sameThreshold: Float = 0.85f,
    private val changeThreshold: Float = 0.55f,
    private val repeatCooldownMs: Long = 10_000L,
    private val emptyFramesToCancel: Int = 3,
    private val minChars: Int = 3,
    private val minPersianRatio: Float = 0.5f,
    /** Minimum count of real Persian letters. Roughly one short word. */
    private val minPersianLetters: Int = 4,
    /** Minimum share of the text that must be letters rather than digits or symbols. */
    private val minLetterFraction: Float = 0.55f,
    private val requirePersian: Boolean = true,
    /** Fired on the analysis thread whenever a new sentence starts being read. */
    private val onSpoke: (String) -> Unit = {}
) {

    @Volatile private var lastSpokenNorm: String? = null
    @Volatile private var lastSpokenRaw: String = ""
    @Volatile private var lastSpokenAt = 0L

    private var candidateNorm: String? = null
    private var candidateRaw: String = ""
    private var candidateHits = 0
    private var emptyStreak = 0

    /** Feed one OCR result. Blank or junk input is treated as "no text". */
    fun submit(rawText: String) {
        val speech = TextNormalizer.forSpeech(rawText)

        // Text is judged on how much real Persian writing it contains, not on the makeup of its
        // letters alone. Checking persianRatio by itself was a design mistake: that ratio is
        // computed among characters that are already letters, so a frame of digit noise like
        // "۹۱ ۲۱" scored a perfect 1.0 as soon as two stray letters landed in it, and got read
        // aloud while genuine sentences were being discarded. Three conditions now apply:
        // enough Persian letters, letters forming most of the text, and those letters being
        // Persian rather than Latin.
        val usable = speech.length >= minChars && (
            !requirePersian || (
                TextNormalizer.persianLetterCount(speech) >= minPersianLetters &&
                    TextNormalizer.letterFraction(speech) >= minLetterFraction &&
                    TextNormalizer.persianRatio(speech) >= minPersianRatio
                )
            )

        if (!usable) {
            onNoText()
            return
        }

        emptyStreak = 0
        val norm = TextNormalizer.normalize(speech)
        if (norm.isEmpty()) {
            onNoText()
            return
        }

        // ---- 1. Stability accumulation -------------------------------------------------
        val matchesCandidate = candidateNorm?.let { candidate ->
            TextNormalizer.similarity(candidate, norm) >= sameThreshold
        } ?: false

        if (matchesCandidate) {
            candidateHits++
            // Keep the most complete reading of the same text.
            if (norm.length > (candidateNorm?.length ?: 0)) {
                candidateNorm = norm
                candidateRaw = speech
            }
        } else {
            candidateNorm = norm
            candidateRaw = speech
            candidateHits = 1
        }

        if (candidateHits < stableFrames) return

        // ---- 2 & 3. Repetition suppression / interruption -------------------------------
        val previous = lastSpokenNorm
        if (previous != null) {
            val similarity = TextNormalizer.similarity(previous, norm)
            if (similarity >= sameThreshold) {
                // Same text, camera stationary: stay quiet until the cooldown expires.
                val idle = SystemClock.elapsedRealtime() - lastSpokenAt
                if (speaker.isSpeaking || idle < repeatCooldownMs) return
            } else if (similarity < changeThreshold && speaker.isSpeaking) {
                // Scene changed materially: cut the stale sentence off mid-word.
                speaker.stop()
            }
        }

        speak(candidateRaw, candidateNorm)
        candidateHits = 0
    }

    /**
     * Re-reads the last sentence on demand.
     *
     * The one concession to input in an otherwise zero-click app: touching anywhere on the screen
     * repeats what was just read. There is no button and nothing to aim at, so it works for a user
     * who cannot see or read a control, and it answers the most common request from an elderly
     * listener who simply did not catch the sentence the first time.
     *
     * Must be posted onto the analysis thread, not called from the tap handler directly.
     */
    fun repeatLast() {
        val text = lastSpokenRaw
        if (text.isEmpty()) return
        speaker.stop()
        speak(text, lastSpokenNorm)
    }

    private fun speak(raw: String, norm: String?) {
        speaker.speakNow(raw)
        lastSpokenRaw = raw
        lastSpokenNorm = norm
        lastSpokenAt = SystemClock.elapsedRealtime()
        onSpoke(raw)
    }

    // ---- 4. Pointed away ---------------------------------------------------------------
    private fun onNoText() {
        candidateNorm = null
        candidateRaw = ""
        candidateHits = 0
        emptyStreak++
        if (emptyStreak == emptyFramesToCancel) {
            speaker.stop()        // silence as soon as there is nothing to read
            lastSpokenNorm = null // allow an instant re-read when pointed back
            // lastSpokenRaw is deliberately kept so a screen touch can still repeat it.
        }
    }

    /** Clears all memory and stops speech. Called when the camera screen is disposed. */
    fun reset() {
        lastSpokenNorm = null
        lastSpokenRaw = ""
        candidateNorm = null
        candidateRaw = ""
        candidateHits = 0
        emptyStreak = 0
        speaker.stop()
    }
}
