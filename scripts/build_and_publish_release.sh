#!/usr/bin/env bash
set -euo pipefail

ROOT="/var/www/freenote/public/livedate-webview-android"
PUBLISH_DIR="/var/www/freenote/public/downloads"
APK_SRC="$ROOT/app/build/outputs/apk/release/app-release.apk"
AAB_SRC="$ROOT/app/build/outputs/bundle/release/app-release.aab"
APK_META="$ROOT/app/build/outputs/apk/release/output-metadata.json"

cd "$ROOT"
GRADLE_USER_HOME="$ROOT/.gradle-home" \
ANDROID_HOME="/var/www/freenote/public/.local-build/android-sdk" \
./gradlew --no-daemon assembleRelease bundleRelease

if [[ ! -f "$APK_SRC" ]]; then
  echo "Release APK not found: $APK_SRC" >&2
  exit 1
fi
if [[ ! -f "$AAB_SRC" ]]; then
  echo "Release AAB not found: $AAB_SRC" >&2
  exit 1
fi

version_code="$(sed -n 's/.*"versionCode"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$APK_META" | head -n1)"
version_name="$(sed -n 's/.*"versionName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$APK_META" | head -n1)"
if [[ -z "${version_code:-}" || -z "${version_name:-}" ]]; then
  echo "Unable to parse version from $APK_META" >&2
  exit 1
fi

build_date="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

mkdir -p "$PUBLISH_DIR"

apk_latest="$PUBLISH_DIR/livedate-release.apk"
aab_latest="$PUBLISH_DIR/livedate-release.aab"
apk_versioned="$PUBLISH_DIR/livedate-v${version_name}-${version_code}-release.apk"
aab_versioned="$PUBLISH_DIR/livedate-v${version_name}-${version_code}-release.aab"

cp -f "$APK_SRC" "$apk_latest"
cp -f "$AAB_SRC" "$aab_latest"
cp -f "$APK_SRC" "$apk_versioned"
cp -f "$AAB_SRC" "$aab_versioned"

apk_sha="$(sha256sum "$apk_latest" | awk '{print $1}')"
aab_sha="$(sha256sum "$aab_latest" | awk '{print $1}')"
echo "$apk_sha" > "$apk_latest.sha256"
echo "$aab_sha" > "$aab_latest.sha256"
echo "$apk_sha" > "$apk_versioned.sha256"
echo "$aab_sha" > "$aab_versioned.sha256"

cat > "$PUBLISH_DIR/manifest.json" <<EOF
{
  "app": "LiveDate",
  "updated_at_utc": "$build_date",
  "version_name": "$version_name",
  "version_code": $version_code,
  "artifacts": {
    "release_apk": {
      "path": "/downloads/livedate-release.apk",
      "sha256": "$apk_sha"
    },
    "release_aab": {
      "path": "/downloads/livedate-release.aab",
      "sha256": "$aab_sha"
    },
    "debug_apk": {
      "path": "/downloads/livedate-debug.apk"
    }
  }
}
EOF

echo "Published release:"
echo "  $apk_latest"
echo "  $aab_latest"
echo "  $PUBLISH_DIR/manifest.json"
