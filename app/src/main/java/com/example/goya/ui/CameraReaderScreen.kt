package com.example.goya.ui

import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.example.goya.feedback.Cues
import com.example.goya.guidance.Coach
import com.example.goya.guidance.Onboarding
import com.example.goya.ocr.OcrAnalyzer
import com.example.goya.ocr.OcrEngine
import com.example.goya.speech.Speaker
import com.example.goya.speech.SpeechGate
import com.example.goya.util.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.plus
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * The entire user interface: one full-bleed camera preview and nothing else.
 *
 * No buttons, no overlays, no text, no menus. Periodic centre autofocus keeps print sharp, the
 * torch turns itself on in poor light, and touching anywhere repeats the last sentence.
 */
@Composable
fun CameraReaderScreen(ocrEngine: OcrEngine, speaker: Speaker, cues: Cues) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val cameraRef = remember { AtomicReference<Camera?>(null) }

    // Single analysis thread. Everything that touches SpeechGate runs here, including the tap
    // handler, so the gate never sees concurrent access.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // Budgeted logger for speech-gate rejections. Rejections can happen on every frame while the
    // camera sweeps across a page, so this is capped: enough lines to name the reason, never
    // enough to crowd out the OCR lines in the same log file.
    val gateLogBudget = remember { AtomicInteger(40) }
    val logGateRejection: (String) -> Unit = { reason ->
        if (gateLogBudget.getAndDecrement() > 0) {
            CrashLog.append(context, "gate: rejected $reason")
        }
    }

    val onboarding = remember { Onboarding(context, speaker, scope) }
    val gate = remember {
        SpeechGate(
            speaker = speaker,
            onSpoke = {
                cues.textFound()
                onboarding.onSentenceSpoken()
            },
            onRejected = logGateRejection
        )
    }
    val coach = remember {
        // onTorch MUST be passed by name. Coach declares trailing parameters with defaults
        // (quietBeforeHintMs, betweenHintsMs, darkLuma, brightLuma, streakToSwitch), so
        // Kotlin's trailing-lambda syntax binds the lambda to the LAST parameter
        // (streakToSwitch: Int) rather than to onTorch.
        Coach(
            context = context,
            speaker = speaker,
            onTorch = { on: Boolean ->
                // runCatching returns Result<T>; onTorch is (Boolean) -> Unit, so discard it.
                runCatching { cameraRef.get()?.cameraControl?.enableTorch(on) }
                Unit
            }
        )
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            keepScreenOn = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // The whole screen is one invisible "say that again" target. Nothing to find, nothing
            // to aim at, and impossible to press by mistake in a harmful way.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        cues.tapAck()
                        // Hop onto the analysis thread: SpeechGate is single-threaded by contract.
                        runCatching { analysisExecutor.execute { gate.repeatLast() } }
                    }
                )
            }
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }

    LaunchedEffect(Unit) {
        val provider = try {
            ProcessCameraProvider.getInstance(context).await()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "camera provider unavailable", t)
            return@LaunchedEffect
        }

        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }

        // A higher analysis resolution dramatically improves OCR on small print.
        // A single ResolutionStrategy did not work everywhere: requesting 1080p with
        // FALLBACK_RULE_CLOSEST_HIGHER throws "No available output size is found" and the whole
        // camera fails to bind on devices whose nearest supported size to 1080p is BELOW it
        // (some phones with 720p rear cameras, many emulators, and any virtual camera). The
        // camera never comes up, no frames ever reach the OCR engine, and the log shows nothing
        // but empty frames. So: try 1080p first (best for tight print), and if the camera will
        // not bind at that resolution, rebind at 720p. 720p is still readable for headline-size
        // print, and a camera that opens beats one that does not.
        var analysis = buildAnalysis(Size(1920, 1080), analysisExecutor, ocrEngine, scope + Dispatchers.Default, ANALYSIS_INTERVAL_MS) { text, luma ->
            gate.submit(text)
            coach.onFrame(hasText = text.isNotBlank(), luma = luma)
        }
        val camera = try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA, // rear camera, immediately
                preview,
                analysis
            )
        } catch (fallbackFailure: Throwable) {
            Log.w(TAG, "1080p bind failed (${fallbackFailure.message}), retrying at 720p")
            analysis = buildAnalysis(Size(1280, 720), analysisExecutor, ocrEngine, scope + Dispatchers.Default, ANALYSIS_INTERVAL_MS) { text, luma ->
                gate.submit(text)
                coach.onFrame(hasText = text.isNotBlank(), luma = luma)
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        } catch (c: CancellationException) {
            throw c // let structured cancellation unwind cleanly on dispose
        }
        cameraRef.set(camera)

        // Hands-free autofocus on the centre of the frame, retriggered periodically.
        // Elderly users often hold the phone too close for fixed focus to cope.
        val point = previewView.meteringPointFactory.createPoint(0.5f, 0.5f)
        val focusAction = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()

        while (true) {
            try {
                camera.cameraControl.startFocusAndMetering(focusAction)
            } catch (c: CancellationException) {
                throw c // let structured cancellation unwind cleanly on dispose
            } catch (t: Throwable) {
                Log.v(TAG, "autofocus trigger ignored: ${t.message}")
            }
            delay(AUTOFOCUS_INTERVAL_MS)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            coach.reset() // also switches the torch off
            gate.reset()
            cameraRef.set(null)
            analysisExecutor.shutdown()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
}

/**
 * Builds the ImageAnalysis use case at the requested resolution with the shared analyzer.
 * Kept in a function so the camera can be re-bound at a lower resolution when the first
 * resolution is unsupported (see the try/catch in [CameraReaderScreen]).
 */
private fun buildAnalysis(
    size: Size,
    executor: Executor,
    engine: OcrEngine,
    scope: CoroutineScope,
    intervalMs: Long,
    onResult: (String, Int) -> Unit
): ImageAnalysis = ImageAnalysis.Builder()
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
            .build()
    )
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()
    .also { useCase ->
        useCase.setAnalyzer(
            executor,
            OcrAnalyzer(engine = engine, scope = scope, intervalMs = intervalMs, onResult = onResult)
        )
    }

private const val TAG = "CameraReaderScreen"
private const val ANALYSIS_INTERVAL_MS = 450L
private const val AUTOFOCUS_INTERVAL_MS = 2500L
