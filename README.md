# LiveDate WebView Android

## Build

```bash
cd /var/www/freenote/public/livedate-webview-android
GRADLE_USER_HOME=/var/www/freenote/public/livedate-webview-android/.gradle-home \
ANDROID_HOME=/var/www/freenote/public/.local-build/android-sdk \
./gradlew --no-daemon assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`

Published download URL:

`https://freenote.kr/downloads/livedate-debug.apk`

## Build + Publish

```bash
cd /var/www/freenote/public/livedate-webview-android
./scripts/build_and_publish_debug.sh
```

Published files:

- `/var/www/freenote/public/downloads/livedate-debug.apk`
- `/var/www/freenote/public/downloads/livedate-debug.apk.sha256`

## Release Signing

1. Copy `keystore.properties.example` to `keystore.properties`
2. Set your real keystore path/password/alias values
3. Run release publish script

If `keystore.properties` is missing, release build falls back to debug signing.

## Release Build + Publish

```bash
cd /var/www/freenote/public/livedate-webview-android
./scripts/build_and_publish_release.sh
```

Published release files:

- `/var/www/freenote/public/downloads/livedate-release.apk`
- `/var/www/freenote/public/downloads/livedate-release.aab`
- `/var/www/freenote/public/downloads/manifest.json`

## Windows Auto Flow (Git Pull + Actions APK)

```powershell
cd C:\Users\xfash\AndroidStudioProjects\livedate-android
powershell -ExecutionPolicy Bypass -File .\scripts\pull_and_fetch_apk.ps1 `
  -RepoRoot . `
  -Repo "xfashion44-jpg/livedate-android" `
  -Workflow "Android Debug APK" `
  -ArtifactName "app-debug-apk" `
  -ArtifactsDir ".\artifacts"
```

## Current App Behavior

- Launch flow: `SplashActivity` -> `MainActivity`
- Default URL: `https://freenote.kr/`
- Back key: navigate WebView history first, then close app
- External links:
  - Open inside WebView: `http`, `https`
  - Open external app: `intent://`, `tel:`, `mailto:`, `sms:`, `market://` and other non-http schemes
  - Handle `intent://` fallback URL and Play Store package fallback
- File upload: `<input type="file">` is supported through Android file chooser

## Key Files

- `app/src/main/java/kr/freenote/livedate/SplashActivity.java`
- `app/src/main/java/kr/freenote/livedate/MainActivity.java`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/layout/activity_splash.xml`
- `app/src/main/res/layout/activity_main.xml`
