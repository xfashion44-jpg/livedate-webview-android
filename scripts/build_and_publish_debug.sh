#!/usr/bin/env bash
set -euo pipefail

ROOT="/var/www/freenote/public/livedate-webview-android"
APK_SRC="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PUBLISH_DIR="/var/www/freenote/public/downloads"
APK_DST="$PUBLISH_DIR/livedate-debug.apk"
SHA_DST="$PUBLISH_DIR/livedate-debug.apk.sha256"

cd "$ROOT"
GRADLE_USER_HOME="$ROOT/.gradle-home" \
ANDROID_HOME="/var/www/freenote/public/.local-build/android-sdk" \
./gradlew --no-daemon assembleDebug

mkdir -p "$PUBLISH_DIR"
cp -f "$APK_SRC" "$APK_DST"
sha256sum "$APK_DST" | awk '{print $1}' > "$SHA_DST"

echo "Published:"
echo "  $APK_DST"
echo "  $SHA_DST"
