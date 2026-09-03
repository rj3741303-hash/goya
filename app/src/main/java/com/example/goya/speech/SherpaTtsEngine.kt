package com.example.goya.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.goya.util.AssetInstaller
import com.example.goya.util.Stages
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Fully offline Persian speech, bundled inside the APK.
 *
 * Uses Sherpa-ONNX with a VITS/Piper Persian voice (an .onnx file in `assets/tts/fa`, ~30 MB)
 * plus the
 * `espeak-ng-data` phonemiser directory. No internet, no Google Play Services, and no device
 * TTS settings are involved: install the APK and it speaks.
 *
 * Audio is produced with Sherpa's plain `generate` call and played through an [AudioTrack].
 *
 * The streaming `generateWithCallback` API is deliberately NOT used. It asks native code to
 * call a Kotlin lambda back over JNI, looking the method up by exact descriptor:
 *
 *     invoke([F)Ljava/lang/Integer;
 *
 * That descriptor only exists when the lambda is compiled as a class of its own, which is what
 * Kotlin 1.x did. From Kotlin 2.0 lambdas are emitted as `invokedynamic` and D8 turns them into
 * `$$ExternalSyntheticLambda` classes carrying only the erased `invoke(Object)Object`. The
 * boxed-Integer method the native lookup needs is simply absent, so GetMethodID fails,
 * NoSuchMethodError is left pending, and Sherpa's next JNI call aborts the whole process.
 * Uncatchable, and fatal on every device -- not just emulators.
 *
 * Responsiveness is preserved instead by splitting the text into short units and writing the
 * samples in small slices, so [stop] still cuts speech off within a few tens of milliseconds.
 *
 * [prepare] is blocking and unpacks ~30 MB on first launch: call it off the main thread.
 */
class SherpaTtsEngine(
    private val context: Context,
    private val assetDir: String = ASSET_DIR,
    private val modelVersion: String = MODEL_VERSION,
    private val speakerId: Int = 0,
    private val speed: Float = 0.95f
) : TtsEngine {

    private val worker = Executors.newSingleThreadExecutor()
    private val ready = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val lastFinishedAt = AtomicLong(0L)

    /** Bumped by [speak] and [stop]; a synthesis job aborts as soon as its own id is stale. */
    private val generation = AtomicInteger(0)

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var firstSynthesisDone = false

    override val isReady: Boolean get() = ready.get()

    override val isSpeaking: Boolean get() = speaking.get()

    override fun millisSinceLastSpeech(): Long {
        val finished = lastFinishedAt.get()
        return if (finished == 0L) Long.MAX_VALUE else System.currentTimeMillis() - finished
    }

    /**
     * Unpacks the bundled model and initialises the engine. Blocking; safe to call once.
     * @return true when the offline voice is usable.
     */
    fun prepare(): Boolean {
        if (ready.get()) return true

        // onnxruntime and espeak-ng are native. When they are unhappy with a path they call
        // exit() instead of failing politely, taking the process with them. If either the load
        // or the first synthesis has already done that twice, report failure so that Speaker
        // falls back to the device's own Persian voice instead of crashing again.
        if (Stages.isQuarantined(Stages.TTS_SPEAK)) {
            Log.w(TAG, "offline synthesis disabled after repeated native crashes")
            return false
        }
        return Stages.guard(Stages.TTS_INIT, fallback = false) { loadOfflineVoice() }
    }

    private fun loadOfflineVoice(): Boolean {
        val dir = AssetInstaller.install(context, assetDir, modelVersion) ?: return false

        val model = dir.listFiles { f -> f.extension == "onnx" }?.minByOrNull { it.name }
        if (model == null) {
            Log.e(TAG, "no .onnx voice found in ${dir.absolutePath}")
            return false
        }

        val tokens = File(dir, "tokens.txt")
        if (!tokens.isFile) {
            Log.e(TAG, "tokens.txt missing next to ${model.name}")
            return false
        }

        // Piper voices phonemise through espeak-ng (dataDir); MMS voices use a lexicon instead.
        val espeak = File(dir, "espeak-ng-data")
        val lexicon = File(dir, "lexicon.txt")

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    lexicon = if (lexicon.isFile) lexicon.absolutePath else "",
                    tokens = tokens.absolutePath,
                    dataDir = if (espeak.isDirectory) espeak.absolutePath else "",
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            // Synthesise sentence by sentence so playback starts sooner.
            maxNumSentences = 1
        )

        return try {
            val engine = OfflineTts(config = config)
            tts = engine
            track = buildTrack(engine.sampleRate())
            ready.set(true)
            Log.i(TAG, "offline Persian voice ready: ${model.name} @ ${engine.sampleRate()} Hz")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Sherpa-ONNX init failed", t)
            releaseNative()
            false
        }
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(sampleRate * 4 / 2) // ~0.5 s of float samples

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Accessibility usage: routed to STREAM_ACCESSIBILITY on Android 8+,
                    // which Cues.ensureAudible() raises alongside STREAM_MUSIC.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override fun speak(text: String) {
        val engine = tts ?: return
        val output = track ?: return
        if (!ready.get()) return

        val myGeneration = generation.incrementAndGet()
        speaking.set(true)

        // Drop whatever is queued from the previous sentence, then restart playback.
        runCatching {
            output.pause()
            output.flush()
        }

        worker.execute {
            if (myGeneration != generation.get()) {
                finish(myGeneration)
                return@execute
            }
            runCatching { output.play() }

            val firstRun = !firstSynthesisDone
            if (firstRun) Stages.begin(Stages.TTS_SPEAK)

            try {
                for (unit in split(text)) {
                    if (myGeneration != generation.get()) break

                    // Synthesis stays entirely inside native code and hands back finished
                    // samples. Nothing calls into Kotlin, so there is no JNI method lookup to
                    // fail. This is the fix for the NoSuchMethodError abort.
                    val samples = engine.generate(
                        text = unit,
                        sid = speakerId,
                        speed = speed
                    ).samples

                    if (samples.isEmpty()) continue

                    // Slice the write so a stop() lands quickly instead of waiting for the
                    // whole unit to drain through a blocking write.
                    var offset = 0
                    while (offset < samples.size) {
                        if (myGeneration != generation.get()) break
                        val count = minOf(WRITE_SLICE_FLOATS, samples.size - offset)
                        val written =
                            output.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) break
                        offset += written
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "synthesis failed", t)
            } finally {
                if (firstRun) {
                    firstSynthesisDone = true
                    Stages.end(Stages.TTS_SPEAK)
                }
                finish(myGeneration)
            }
        }
    }

    /**
     * Breaks text into short units: one per sentence, and long run-on sentences cut at a space.
     *
     * Two reasons. Speech starts sooner, because the first unit only has to be synthesised
     * before playback begins. And [stop] becomes responsive, because the generation counter is
     * re-checked between units.
     */
    private fun split(text: String): List<String> {
        val units = mutableListOf<String>()

        text.split(SENTENCE_BREAK)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { sentence ->
                var rest = sentence
                while (rest.length > MAX_UNIT_CHARS) {
                    val cut = rest.lastIndexOf(' ', MAX_UNIT_CHARS)
                        .takeIf { it > 0 } ?: MAX_UNIT_CHARS
                    units += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) units += rest
            }

        return units
    }

    private fun finish(myGeneration: Int) {
        if (myGeneration == generation.get()) {
            speaking.set(false)
            lastFinishedAt.set(System.currentTimeMillis())
        }
    }

    override fun stop() {
        generation.incrementAndGet() // invalidates any in-flight synthesis
        speaking.set(false)
        runCatching {
            // pause() + flush() also unblocks a worker thread parked inside a blocking write().
            track?.pause()
            track?.flush()
        }
    }

    override fun shutdown() {
        // Order matters. The worker can be parked inside AudioTrack.write(WRITE_BLOCKING), and
        // releasing the track underneath it would crash in native code. stop() unblocks the
        // write, then we wait for the worker to actually leave the callback before releasing.
        stop()
        ready.set(false)
        worker.shutdown()
        runCatching {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "synthesis worker did not stop in time")
                worker.shutdownNow()
                worker.awaitTermination(1, TimeUnit.SECONDS)
            }
        }
        releaseNative()
    }

    private fun releaseNative() {
        runCatching {
            track?.stop()
            track?.release()
        }
        track = null
        runCatching { tts?.release() }
        tts = null
    }

    companion object {
        private const val TAG = "SherpaTtsEngine"

        /** Folder inside `assets` holding the .onnx voice, tokens.txt and espeak-ng-data. */
        const val ASSET_DIR = "tts/fa"

        /** Bump when the bundled voice changes so the installer reunpacks it. */
        const val MODEL_VERSION = "vits-piper-fa_IR-amir-medium-1"

        /** Sentence terminators, including the Persian question mark. */
        private val SENTENCE_BREAK = Regex("[.!?\u061F\u2026\n]+")

        /** Longest unit handed to the synthesiser in one go. */
        private const val MAX_UNIT_CHARS = 120

        /** Floats per AudioTrack write; small enough that stop() is felt immediately. */
        private const val WRITE_SLICE_FLOATS = 2048
    }
}
