# Tesseract4Android JNI entry points
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# Sherpa-ONNX: the native layer resolves these classes and their fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
