#!/usr/bin/env bash
# Lists the published versions of the Tesseract Android library, so you can pin a real one
# instead of guessing. Run from anywhere:   bash tools/list-tesseract-versions.sh
#
# Why this exists: the build failed once with
#     Could not find cz.adaptech.tesseract4android:tesseract4android:4.7.0
# because a version number was assumed rather than checked. Maven Central's own metadata
# is the only authoritative answer.
set -euo pipefail

GROUP_PATH="cz/adaptech/tesseract4android"
BASE="https://repo1.maven.org/maven2/$GROUP_PATH"

for ARTIFACT in tesseract4android tesseract4android-openmp; do
  echo "=== $ARTIFACT ==="
  META="$BASE/$ARTIFACT/maven-metadata.xml"
  if ! XML=$(curl -fsSL --retry 3 "$META"); then
    echo "  (could not read $META)"
    continue
  fi

  echo -n "  versions: "
  printf '%s' "$XML" | grep -oE '<version>[^<]+' | sed 's/<version>//' | tr '\n' ' '
  echo

  LATEST=$(printf '%s' "$XML" | grep -oE '<release>[^<]+' | sed 's/<release>//' | head -n1 || true)
  [ -z "$LATEST" ] && LATEST=$(printf '%s' "$XML" | grep -oE '<latest>[^<]+' | sed 's/<latest>//' | head -n1 || true)
  echo "  latest release: ${LATEST:-unknown}"
done

cat <<'EOF'

To pin a version, edit gradle.properties:
    tesseractVersion=<version from the list above>

Or build once without touching anything:
    ./gradlew assembleDebug -PtesseractVersion=<version>

The default in gradle.properties is "latest.release", which lets Gradle resolve the newest
published version itself. Pinning is better for reproducible builds once you know a
version that works on your machine.
EOF
