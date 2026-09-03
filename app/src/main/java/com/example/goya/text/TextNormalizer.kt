package com.example.goya.text

/**
 * Persian-aware text utilities.
 *
 * Live OCR output jitters between frames (Arabic vs Persian yeh/kaf, tatweel, diacritics,
 * stray punctuation, collapsed newlines). Normalising before comparison is what makes the
 * speech debouncer in [com.example.goya.speech.SpeechGate] actually work.
 */
object TextNormalizer {

    private val diacritics = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
    private val nonWord = Regex("[^\\p{L}\\p{Nd}\\s]")
    private val whitespace = Regex("\\s+")
    private val newlines = Regex("[\\r\\n]+")

    /** Canonical form used only for similarity checks. Never spoken. */
    fun normalize(raw: String): String = raw
        .replace('\u064A', '\u06CC') // Arabic yeh    -> Persian yeh
        .replace('\u0643', '\u06A9') // Arabic kaf    -> Persian keheh
        .replace('\u0629', '\u0647') // teh marbuta   -> heh
        .replace('\u0623', '\u0627') // alef w/ hamza -> alef
        .replace('\u0625', '\u0627')
        .replace('\u0622', '\u0627')
        .replace(diacritics, "")
        .replace(nonWord, " ")
        .replace(whitespace, " ")
        .trim()

    /** Cleans recognised text for speech: collapses line breaks and repeated spaces. */
    fun forSpeech(raw: String): String = raw
        .replace(newlines, " ")
        .replace(whitespace, " ")
        .trim()

    /**
     * Number of Persian/Arabic letters in the text.
     *
     * This, not [persianRatio], is what says whether there is real writing here. A frame of OCR
     * noise such as "۹۱ ۲۱" has a persianRatio of 1.0 the moment it contains two stray letters,
     * because that ratio only ever looks at characters that are already letters.
     */
    fun persianLetterCount(text: String): Int = text.count { ch ->
        ch.isLetter() && (ch.code in 0x0600..0x06FF || ch.code in 0xFB50..0xFEFF)
    }

    /**
     * Fraction of the visible text made up of letters, digits and punctuation included.
     *
     * Low values mean the frame is mostly numbers or symbols: a price tag, a page number, or
     * Tesseract hallucinating on a blurry image. Not worth reading aloud.
     */
    fun letterFraction(text: String): Float {
        val visible = text.count { !it.isWhitespace() }
        if (visible == 0) return 0f
        return text.count { it.isLetter() }.toFloat() / visible
    }

    /** Fraction of letters that are Persian/Arabic script. Used to reject noise frames. */
    fun persianRatio(text: String): Float {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return 0f
        val persian = letters.count { ch ->
            ch.code in 0x0600..0x06FF || ch.code in 0xFB50..0xFEFF
        }
        return persian.toFloat() / letters.length
    }

    /**
     * Why a text frame would be rejected by the speech gate, or null when it would pass.
     *
     * This is diagnostics, not a second gate: when the app stays silent despite OCR producing
     * text, the log must say WHICH condition failed, otherwise "OCR read something" and "the
     * user heard nothing" look identical from outside. The thresholds are the same ones
     * [com.example.goya.speech.SpeechGate] applies, passed in so there is exactly one source
     * of truth for each number.
     */
    fun rejectionReason(
        text: String,
        minChars: Int,
        minPersianLetters: Int,
        minLetterFraction: Float,
        minPersianRatio: Float
    ): String? {
        if (text.length < minChars) return "too-short len=${text.length}"
        val letters = persianLetterCount(text)
        if (letters < minPersianLetters) return "few-persian-letters count=$letters"
        val fraction = letterFraction(text)
        if (fraction < minLetterFraction) return "mostly-digits fraction=%.2f".format(fraction)
        val ratio = persianRatio(text)
        if (ratio < minPersianRatio) return "non-persian-letters ratio=%.2f".format(ratio)
        return null
    }

    /** Normalised Levenshtein similarity: 0.0 = unrelated, 1.0 = identical. */
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = levenshtein(a, b)
        return 1f - distance.toFloat() / maxOf(a.length, b.length)
    }

    /** Two-row Levenshtein: O(min(a,b)) memory, fast enough for every frame. */
    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val swap = prev
            prev = curr
            curr = swap
        }
        return prev[b.length]
    }
}
