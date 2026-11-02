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

## Release build

Release APKs must be signed. Generate a keystore once (keep it backed up, never commit it):

```bash
keytool -genkeypair -keystore ~/vifora-release.jks -alias vifora \
  -keyalg RSA -keysize 2048 -validity 9125
```

Put the credentials in `~/.gradle/gradle.properties` (personal, untracked):

```properties
VIFORA_STORE_FILE=/home/user/vifora-release.jks
VIFORA_STORE_PASSWORD=...
VIFORA_KEY_ALIAS=vifora
VIFORA_KEY_PASSWORD=...
```

Then:

```bash
./gradlew :app:assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk` (signed). Without the
properties above, the build still succeeds but produces `app-release-unsigned.apk`,
which cannot be installed.
