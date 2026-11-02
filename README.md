# Airsense Android companion

Native Android app for the existing ESP32 → MQTT → Raspberry Pi Airsense device. Android 8.0 or newer. No Pi packages, firmware changes, API server, or calibration feature.

## Install and connect

1. Install the development APK from `app/build/outputs/apk/debug/app-debug.apk`. Android may ask you to allow installation from the app used to open it.
2. Connect the phone to the Raspberry Pi hotspot. Keep the connection if Android says the hotspot has no internet. The app's Wi-Fi button opens Android Wi-Fi settings.
3. In Settings, enter the Pi address, SSH username, password or an existing private key, and the absolute log directory. Defaults match the repository: `192.168.4.1`, SSH port `22`, username `ragur`, `/home/ragur/airsense_logs`, MQTT port `1883`, topic `esp32/sensors`. No password is included in the app.
4. Tap **Save & connect** for live MQTT readings. SSH and MQTT have independent connections: a working live dashboard does not prove that SSH credentials are correct.
5. Return to Settings and choose **Test saved SSH settings**. On first use, compare the displayed SSH fingerprint with the Pi's host key and accept it. For a read-only check on the Pi, `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256` displays its Ed25519 fingerprint. If another host-key algorithm was negotiated, check the corresponding public key file in `/etc/ssh/`.
6. Open Logs and tap **Refresh Pi logs**. **Download & analyse** saves a local snapshot and opens its graph. Use **Export CSV** to choose Downloads or another Android document location. **On this phone** lists downloaded, imported, and sample CSVs and works without Wi-Fi.
7. Open Screen, choose **Find dashboard**, then select the pane whose command is Python and whose session is your dashboard. The current repository auto-attaches terminals to the `main` tmux session. Captures are read-only and do not attach another terminal, send keys, or resize the display.

Use **Explore sample data** in Settings to try the interface and a generated one-hour CSV without a device. A persistent SAMPLE MODE banner distinguishes generated data from device readings.

## Features

- Seven live parameters: NOx, SO₂, NH₃, PM2.5, PM10, noise, and light, with units and the original dashboard thresholds.
- Live updates use the existing MQTT topic. Automatic reconnect uses a 1–15 second backoff. Readings become stale after 15 seconds; missing sensors appear offline, never zero. Retained messages are not presented as fresh measurements.
- Android network sockets prefer the connected Wi-Fi network even if mobile data remains the default internet route.
- Daily CSVs and timestamp-suffixed rotated CSVs are listed through read-only SFTP.
- Downloads stop at the remote file's initial length and omit an unfinished final CSV line. They are byte-for-byte snapshots through the last complete line, not reformatted exports. A growing file can be downloaded again for a newer snapshot. Existing local snapshots are replaced only after a new transfer succeeds.
- Device archives are separated by connection identity on the phone, so logs from different Pi addresses/folders do not overwrite each other.
- Offline charts support sensor selection, date/time filtering, pinch zoom, horizontal pan, tap-to-inspect, configured threshold lines, minimum/average/maximum, and a CSV preview. Each sensor uses its own units. Time filters use `YYYY-MM-DD HH:mm:ss` in the recorded device clock; the app does not infer an RTC timezone.
- Graphs use two streaming passes and a maximum of 2048 time buckets, keeping first/last/extreme values. Buckets containing missing data are gaps. Statistics include every valid reading in the requested range, regardless of graph reduction.
- The CSV reader supports BOMs, CRLF/LF, quoted fields, and the older `SOx (ppm)` / `Noise (lvl)` column labels. Invalid dates and incomplete-width rows are skipped and counted. Missing, `Nil`, non-numeric, NaN, and infinite measurements are treated as missing.
- Screen view reproduces terminal text and ANSI colours, including 256-colour and RGB sequences. It supports horizontal scrolling, text-size adjustment, and optional refresh every three seconds. It is not a pixel screenshot or remote control of the physical display.
- Password and existing private-key authentication. Connection secrets are encrypted with an Android Keystore AES-GCM key. SSH host fingerprints are verified on every connection. Android backups are disabled. No analytics or cloud service is used.

## Build

Open this `android` directory in Android Studio. Required components:

- JDK 17 or newer (the bundled Android Studio JDK 21 also works)
- Android SDK platform 36
- Gradle 9.3.1 (included wrapper), Android Gradle plugin 9.1.0

Set `sdk.dir` in an untracked `local.properties`, or set `ANDROID_HOME` to your SDK. Then:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On macOS/Linux, use `./gradlew` instead. Dependency downloads require internet on the development computer, not on the phone or Pi. A local debug keystore is generated inside `app/.debug/`; keep it to install subsequent development builds over this one. Release signing must use your own release key.

The JVM tests cover current/older CSV formats, missing readings, date filtering, malformed rows, large-file graph reduction, MQTT framing and a local socket broker exchange, ANSI colours, connection validation, and SSH crypto initialization. HTML results are in `app/build/reports/tests/testDebugUnitTest/`; Android Lint reports are in `app/build/reports/`.

### Offline development build in this workspace

Maven downloads are blocked in the current coding environment. `scripts/build-local.ps1` uses the installed Gradle distribution, the existing read-only dependency cache, and the SSHJ bundle installed with Android Studio. It does not copy these libraries into source control. This is a development verification fallback, **not** a claim that the normal Maven dependency graph was verified here. The regular build continues to request upstream `com.hierynomus:sshj:0.40.0`.

```powershell
.\scripts\build-local.ps1
```

The script builds an APK, runs unit tests, and uses only project-local writable caches. Android Lint was attempted but cannot run here because `com.android.tools.lint:lint-gradle:32.1.0` is not cached and downloads are blocked. Run the normal `:app:lintDebug` task when Maven access is available, or pass `-RunLint` to this script after populating that cache. The script's optional parameters let you point at other local SDK/JDK/Gradle/library locations. The SSH compatibility test records the actual SSH implementation version in its test report.

## Repository layout

```text
app/src/main/java/com/airsense/companion/
├── MainActivity.kt
├── AppViewModel.kt          UI state, reconnect, downloads, analysis
├── data/                   Models, streaming CSV reader, ANSI parser, encrypted settings
├── network/                Read-only SSH/SFTP, MQTT subscriber, Wi-Fi routing
└── ui/                     Compose dashboard, logs, charts, screen, settings
app/src/test/               JVM tests
scripts/                    Local verification build
```

## Hardware validation still required

The cloned configuration establishes the expected interfaces but does not establish the running Pi's current state. Device testing needs your Pi and an Android phone; no device is connected to this workspace. Verify:

1. SSH/SFTP are already enabled and the configured account can read the existing log folder.
2. The current Mosquitto listener accepts connections from the hotspot. If anonymous access is disabled, enter the broker's existing MQTT credentials.
3. The dashboard is inside tmux under the same SSH account. Select its actual pane; pane IDs can change after reboot. A dashboard running outside tmux cannot be captured by this implementation.
4. Live data stays available with mobile data enabled, reconnects after Wi-Fi interruption, and is marked stale if the ESP32 stops sending.
5. Downloads of today's growing log open correctly; exporting to Downloads and reopening imported CSVs works on your phone.
6. The terminal view matches the existing dashboard and does not change its layout when the phone rotates.

The Pi's existing logging behavior is retained: `DASHBOARD.py` writes logs while it is running and skips packets with no valid RTC date. This app does not fix or modify that Pi-side behavior. Live subscriptions and automatic screen refresh pause when the app goes into the background; the Pi continues operating independently. There is no background logging or notification service on the phone.

MQTT uses the existing local unencrypted port 1883. SSH/SFTP are encrypted and host-key checked. Existing CSVs have no separate raw/calibrated fields; the app reads their values as logged and applies no offsets.

## Dependencies

AndroidX Compose/Activity/Lifecycle/Core (Apache-2.0), Kotlin/coroutines (Apache-2.0), SSHJ (Apache-2.0), and Bouncy Castle (Bouncy Castle licence). The MQTT subscriber and CSV/ANSI parsers are implemented in this project. See the upstream dependency licences when preparing a public release.
