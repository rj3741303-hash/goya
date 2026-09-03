# Building the APK in the cloud (no tools on your computer)

GitHub builds the app for you, downloads the Persian OCR and voice models, signs the APK,
and hands you a file you can install on a phone. You need nothing installed locally: no
JDK, no Android Studio, no Gradle, no 30 MB model downloads on your own connection.

The workflow lives in `.github/workflows/build-apk.yml`.

## Steps

1. **Create a repository on GitHub.** Private is fine; the build works either way.

2. **Upload the project.** Either drag the unzipped folder into GitHub's web uploader, or:

   ```bash
   cd goya
   git init
   git add .
   git commit -m "Goya: zero-click Persian OCR reader"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```

   The models are **not** uploaded and do not need to be. The build downloads them itself.

3. **Run the build.** Open the **Actions** tab -> **Build APK** -> **Run workflow**.
   A push to `main` also triggers it automatically.

4. **Download the result.** When the run finishes, open it and download the **goya-apk**
   artifact from the Artifacts section. It contains `goya-release-signed.apk`.

5. **Install on the phone.** Copy the APK across, tap it, and allow installation from
   unknown sources when asked. Grant the camera permission on first launch — this is the
   only prompt the app ever shows, and it is read aloud because the user cannot read it.

## Options when running manually

| Input | Default | Why change it |
|---|---|---|
| `voice` | `vits-piper-fa_IR-amir-medium` | Try `vits-piper-fa_IR-gyro-medium` for a different Persian voice, or `vits-mms-fas` if the Piper voices are unavailable |
| `ocr_quality` | `best` | `best` is now the default: it recognises faint photocopies, small medicine labels and low-contrast bills far more reliably. Switch to `fast` only if recognition feels slow on an old phone |
| `sherpa_aar_url` | empty (auto-detect) | Paste a direct link to the Sherpa-ONNX Android `.aar` if auto-detection fails |
| `tesseract_version` | empty (auto-detect) | Pin a specific `tesseract4android` version instead of letting the build resolve the newest one |

## Why the build might fail on purpose

Gradle will happily report success with the models missing, producing an app that installs,
opens the camera, and then never says a single word. That failure is invisible until the
phone is in the user's hands, so the workflow refuses to hand you an APK it cannot prove
is complete.

| Error | Meaning | Fix |
|---|---|---|
| `fas.traineddata is only N bytes` | The download returned an HTML error page, not a model | Re-run; if it persists, switch `ocr_quality` to the other option |
| `No .onnx voice directly inside .../tts/fa` | The tarball extracted into a nested folder | Already handled by the workflow; re-run. If it repeats, the release layout changed |
| `Could not find a Sherpa-ONNX Android AAR automatically` | The release asset was renamed | Copy the direct `.aar` link from the releases page into `sherpa_aar_url` |
| `AAR contains no arm64-v8a native libraries` | Wrong artifact (a JVM build, not the Android one) | Pick the asset whose name contains `android` |
| `APK is only N MB` | The voice model never made it in | Check the earlier download steps in the log |
| `Could not find cz.adaptech.tesseract4android:...` | A pinned library version no longer exists on Maven Central | Already fixed: the version is resolved from Maven metadata at build time. If it recurs, run `bash tools/list-tesseract-versions.sh` and pass a real version in `tesseract_version` |
| The log picked an AAR named `...-rknn.aar` | A Rockchip NPU variant was matched instead of the plain Android build | Already fixed: hardware variants are filtered out. The build only notes it now instead of using it |
| `Node 20 is being deprecated` | Informational notice from GitHub's runner about the Node version its actions use | Ignore it. It is not an error and does not affect the build |

## About the signing key

Each run generates a fresh self-signed key. That is enough to sideload the app, but Android
treats a new signature as a different app, so **uninstall the previous build before
installing a new one**.

If you plan to update the app on a phone repeatedly, use a stable key instead: generate a
keystore once, add it as a base64 repository secret, and decode it in the signing step
rather than calling `keytool`. A stable key is also required if you ever publish to Google
Play — and Play needs an app bundle (`./gradlew bundleRelease`), not this APK, because the
bundled voice pushes the APK past the 100 MB limit.

## Expected output

Roughly 80-110 MB, most of it the Persian voice model. That size is the price of an app
that works with no internet, no account, and no changes to the phone's settings.
