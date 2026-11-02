# Vifora

Video compression app (create VIdeos FOR Ants). Targets a specific output size so videos are easy to share. Android 14+.

## Build

Requirements: JDK 17, Android SDK (API 34–36, build-tools 36) with `sdk.dir` set in `local.properties`.

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:installDebug       # build + install to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
