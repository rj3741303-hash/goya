pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()

        // Tesseract4Android is published through JitPack, NOT Maven Central.
        //
        // Without this repository the build fails with:
        //     Could not find cz.adaptech.tesseract4android:tesseract4android:<version>
        //     Searched in the following locations:
        //       - https://dl.google.com/dl/android/maven2/...
        //       - https://repo.maven.apache.org/maven2/...
        // Only very old versions (4.1.1a and earlier) were ever mirrored to Maven
        // Central, which is why the error looks like a wrong version number when it is
        // really a missing repository.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Goya"
include(":app")
