# Development build verification — 2026-10-29

Artifact: `app/build/outputs/apk/debug/app-debug.apk`

- Application: `com.airsense.companion`, version `0.1.0` (1)
- Android 8.0+ (minimum API 26), target/compile API 36
- APK size: 15,560,304 bytes
- APK SHA-256: `50D1758CABA8F400E44C98CA10A3D250CFA3EE6FEBF8F5D7F135154999BCD539`
- `:app:assembleDebug`: passed
- `:app:testDebugUnitTest`: **29 tests passed**, zero failures or errors
- Android SDK `apksigner verify --verbose`: passed, APK Signature Scheme v2
- `git diff --check`: passed

Test groups: CSV parsing/analysis (9), completed-line download snapshots (3), MQTT codec and local socket broker exchange (8), SSH crypto/fingerprint compatibility (2), terminal colours/settings/path handling (7).

## Build environment and limits

Built with the installed Android Studio JDK 21, Gradle 9.3.1, AGP 9.1.0, SDK platform 36, and cached AndroidX dependencies, using `scripts/build-local.ps1`.

Maven access is blocked in this environment. This development APK uses the Android Studio SSHJ bundle through the explicit `offlineSshJar` build override. The compatibility test reports `SSHJ_0.38.1_SNAPSHOT`; that local bundle's SHA-256 is `0D258D098BD424A271A6E0C45E039694559E8B6DD11F50A0261D35EB34C23A7C`. The normal source build declares upstream SSHJ 0.40.0. A normal online dependency-resolution build has **not** been verified here; rebuild with that dependency before distributing a release.

Android Lint was attempted but could not resolve the uncached `com.android.tools.lint:lint-gradle:32.1.0` package in offline mode. It has not passed. Gradle also reports that it cannot write its optional analytics settings outside the permitted workspace; that warning does not prevent compilation, tests, or APK signing.

No Android phone, emulator system image, or Raspberry Pi is connected here. UI behavior on a phone, real SSH authentication/SFTP transfers, live sensor subscriptions, and capture of the actual Pi tmux session remain unverified. Follow the hardware-validation steps in README.md.

No Raspberry Pi Python, firmware, startup, hotspot, or MQTT configuration files were changed. Source changes are confined to the new `android/` project and Android artifact exclusions in the repository `.gitignore`.
