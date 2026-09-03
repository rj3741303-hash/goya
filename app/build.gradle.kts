plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.goya"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.goya"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Sherpa-ONNX ships native libs; keep only the ABIs real phones use.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 strips the unused OCR backend. With persianOcr = true the whole ML Kit Latin
            // pipeline is dead code, and shrinking removes several MB from an APK that is
            // already large because of the bundled voice.
            // Keep rules for the JNI classes (Tesseract, Sherpa-ONNX, ONNX Runtime, ML Kit)
            // live in proguard-rules.pro; those libraries resolve classes by name at runtime
            // and WILL crash if they are renamed.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Models are read directly at runtime and must stay uncompressed in the APK.
    androidResources {
        noCompress += listOf("traineddata", "onnx")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        jniLibs.useLegacyPackaging = false
    }

    // The bundled voice model pushes the APK past the default 100 MB warning threshold.
    // Use an app bundle (./gradlew bundleRelease) for Play distribution.
    splits {
        abi {
            isEnable = false
        }
    }
}

// Version of the Tesseract Android library, from gradle.properties (currently 4.9.0).
//
// IMPORTANT: this library lives on JitPack, not Maven Central. settings.gradle.kts adds
// the JitPack repository; without it the build fails with "Could not find
// cz.adaptech.tesseract4android:tesseract4android:<version>", which looks like a bad
// version number but is really a missing repository.
//
// CI overrides this with -PtesseractVersion=<version> after resolving the newest published
// version, so the build log records exactly what was used.
// List real versions with: bash tools/list-tesseract-versions.sh
val tesseractVersion: String =
    (findProperty("tesseractVersion") as String?)?.takeIf { it.isNotBlank() }
        ?: "4.9.0"

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // CameraX
    val camerax = "1.4.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // ---- OCR backends ----------------------------------------------------------------
    // ML Kit Text Recognition v2 -> Latin script only (no Arabic/Persian model exists).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Tesseract 4 -> real Persian ('fas') recognition, fully offline.
    implementation("cz.adaptech.tesseract4android:tesseract4android:$tesseractVersion")

    // ---- Offline Persian speech ------------------------------------------------------
    // Drop sherpa-onnx-<version>-android.aar into app/libs/ (see app/libs/README.txt).
    // It bundles both the Kotlin API (com.k2fsa.sherpa.onnx.*) and the native .so files.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
}
