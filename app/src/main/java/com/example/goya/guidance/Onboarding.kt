package com.example.goya.guidance

import android.content.Context
import com.example.goya.R
import com.example.goya.speech.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speaks the "touch anywhere to hear it again" hint exactly once, ever.
 *
 * Tap-to-repeat has no visual affordance by design, which means a user who cannot read would
 * never discover it. Telling them is the only way it exists at all.
 *
 * Timing matters: the hint is spoken after the **first successful reading**, not at launch. At
 * launch it would be meaningless ("hear what again?") and would delay the thing the user
 * actually opened the app for. It also waits for the sentence to finish rather than cutting it
 * off, and the flag is only persisted once the hint has actually been spoken, so a user who
 * closes the app mid-sentence still gets it next time.
 */
class Onboarding(
    context: Context,
    private val speaker: Speaker,
    private val scope: CoroutineScope
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val running = AtomicBoolean(false)

    /** Call whenever a sentence starts being read. Cheap and safe to call from any thread. */
    fun onSentenceSpoken() {
        if (prefs.getBoolean(KEY_TAP_HINT, false)) return
        if (!running.compareAndSet(false, true)) return

        scope.launch {
            try {
                // Wait for the sentence to finish instead of talking over it.
                withTimeoutOrNull(SENTENCE_TIMEOUT_MS) {
                    while (speaker.isSpeaking) delay(POLL_MS)
                }
                delay(GAP_MS)

                // If a new sentence started meanwhile, try again on a later reading.
                if (speaker.isSpeaking) {
                    running.set(false)
                    return@launch
                }

                speaker.speakNow(appContext.getString(R.string.spoken_tap_to_repeat))
                prefs.edit().putBoolean(KEY_TAP_HINT, true).apply()
            } catch (t: Throwable) {
                running.set(false)
                throw t
            }
        }
    }

    private companion object {
        const val PREFS = "goya_onboarding"
        const val KEY_TAP_HINT = "tap_hint_spoken"
        const val SENTENCE_TIMEOUT_MS = 30_000L
        const val POLL_MS = 150L
        const val GAP_MS = 700L
    }
}
