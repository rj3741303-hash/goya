Place the Sherpa-ONNX Android AAR in THIS folder:

    app/libs/sherpa-onnx-<version>-android.aar

Get it from the Sherpa-ONNX releases page (pick the newest release and download the
Android .aar asset):

    https://github.com/k2-fsa/sherpa-onnx/releases

The AAR contains BOTH:
  * the Kotlin API used by SherpaTtsEngine.kt  (com.k2fsa.sherpa.onnx.OfflineTts, ...)
  * the native libraries (libsherpa-onnx-jni.so, libonnxruntime.so) for all ABIs

app/build.gradle.kts already picks up every *.aar in this folder via:

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

So no further Gradle change is needed. If you prefer a remote Maven dependency instead of a
local AAR, check the current artifact coordinates in the Sherpa-ONNX Android documentation
(they change between releases) and replace the fileTree line.

Note: build.gradle.kts restricts abiFilters to arm64-v8a and armeabi-v7a to keep the APK
small. Add x86_64 if you need to run on an emulator.

This README can stay; only *.aar and *.jar files are added to the build.
