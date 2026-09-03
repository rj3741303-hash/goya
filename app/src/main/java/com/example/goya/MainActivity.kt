package com.example.goya

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.goya.feedback.Cues
import com.example.goya.ocr.MlKitOcrEngine
import com.example.goya.ocr.OcrEngine
import com.example.goya.ocr.TesseractOcrEngine
import com.example.goya.speech.Speaker
import com.example.goya.ui.CameraReaderScreen
import com.example.goya.util.CrashLog
import com.example.goya.util.Stages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single-activity, zero-click reader.
 *
 * Launch -> rear camera fills the screen -> frames are OCR'd -> Persian text is spoken through
 * the offline voice bundled in the APK. No navigation, no settings, no controls of any kind,
 * and no dependency on the internet or on the device's TTS configuration.
 *
 * Both heavy backends unpack tens of megabytes of models on first launch, so both are started
 * on background dispatchers before the UI is set. Neither blocks the first frame: the camera
 * runs immediately and OCR and speech switch on as they become ready.
 */
class MainActivity : ComponentActivity() {

    /**
     * true  -> real Persian OCR via Tesseract `fas` (requires assets/tessdata/fas.traineddata)
     * false -> ML Kit Latin-script recognizer (no assets needed, cannot read Persian)
     */
    private val persianOcr = true

    private companion object {
        const val TAG = "MainActivity"

        /** Long enough for a ~63 MB model to unpack on a slow phone's first launch. */
        const val ENGINE_REPORT_DELAY_MS = 12_000L
    }

    private lateinit var speaker: Speaker
    private lateinit var cues: Cues
    private lateinit var ocrEngine: OcrEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Diagnostics first, before anything that could die. startSession also writes the
        // post-mortem of the previous process death, which is the only way to see a native
        // crash without a computer attached. Stages.install then reports which subsystem was
        // mid-initialisation when the process vanished, and disables it if it keeps doing that.
        // Everything is written to Android/data/com.example.goya/files/goya-log.txt
        CrashLog.startSession(this)
        Stages.install(this)

        // Full-bleed, edge-to-edge, no system bars, screen never sleeps.
        // Hiding the bars matters here: there is nothing on screen the user could tap by accident
        // and no way to wander into another app.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        speaker = Speaker(this)
        cues = Cues(this)
        ocrEngine = if (persianOcr) TesseractOcrEngine(this) else MlKitOcrEngine()

        // A silent app is indistinguishable from a broken app for someone who cannot read the
        // screen, so make sure the phone is loud enough before anything else happens.
        cues.ensureAudible()

        // Start both model loads before the first composition. Speaker queues anything requested
        // while it is still initialising and speaks it the moment the voice is ready, so the
        // permission prompt below is never lost.
        speaker.prepare(lifecycleScope)
        lifecycleScope.launch(Dispatchers.IO) {
            // Copies fas.traineddata (4-15 MB) out of the APK and initialises Tesseract.
            // Never do this on the main thread: it is a guaranteed ANR on a slow phone.
            // runCatching because an exception escaping a lifecycleScope coroutine is fatal:
            // no OCR is a degraded app, but a crash is no app at all.
            runCatching { ocrEngine.prepare() }
                .onFailure { Log.e(TAG, "OCR init failed - camera still runs, reading disabled", it) }
        }

        // Both engines load in the background, so their real state is only knowable a few
        // seconds in. Writing that snapshot to the log removes all guesswork about whether a
        // silent app is silent because OCR found nothing or because no voice ever loaded.
        lifecycleScope.launch {
            delay(ENGINE_REPORT_DELAY_MS)
            CrashLog.append(
                this@MainActivity,
                buildString {
                    appendLine("----- engine status after ${ENGINE_REPORT_DELAY_MS / 1000}s -----")
                    appendLine("OCR ready:            ${ocrEngine.isReady}")
                    appendLine("offline voice in use: ${speaker.isOffline}")
                    appendLine("any voice available:  ${speaker.isReady}")
                }
            )
            // Refresh the public copy now that this session's findings are in the file.
            CrashLog.exportToDownloads(this@MainActivity)
        }

        setContent {
            AutoPermissionGate(
                onGranted = {
                    CameraReaderScreen(ocrEngine = ocrEngine, speaker = speaker, cues = cues)
                },
                onAsking = { speaker.speakNow(getString(R.string.spoken_need_camera)) },
                onDenied = { speaker.speakNow(getString(R.string.spoken_permission_denied)) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Duck background music rather than talking over it.
        cues.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        // Never keep talking in the background.
        speaker.stop()
        cues.abandonFocus()
    }

    override fun onDestroy() {
        speaker.shutdown()
        ocrEngine.close()
        super.onDestroy()
    }
}

/**
 * Requests CAMERA immediately with no UI of its own.
 *
 * The user never taps anything inside the app. The only possible tap is the OS permission
 * dialog, which we announce out loud because the user cannot read it -- and if it is refused we
 * say so, instead of leaving a black screen with no explanation.
 */
@Composable
private fun AutoPermissionGate(
    onGranted: @Composable () -> Unit,
    onAsking: () -> Unit,
    onDenied: () -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        if (!result) onDenied()
    }

    LaunchedEffect(Unit) {
        if (!granted) {
            onAsking()
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (granted) {
        onGranted()
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}
