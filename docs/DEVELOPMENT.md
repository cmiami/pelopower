# Developer guide

## Project layout

- `CyclingPowerGattServer`: dependency-free Android GATT server advertising Cycling Power Service
  `0x1818` and exposing Cycling Speed and Cadence Service `0x1816`. Cadence and speed are present as crank
  and wheel revolution data in both profiles for broad receiver compatibility.
- `PowerSource`: the decoder boundary.
- `PelotonPowerSource`: read-only Bike+/G700 polling adapter for the Affernet `IBikeInterface`.
- `PelotonV1PowerSource`: callback adapter selected automatically on original Peloton Bike models.
- `LegacyServiceProbe`: verifies the older Bike+ service package/action/descriptor without issuing
  a command.
- `RawBinderSnapshotProbe`: debug-only read snapshot of the known legacy transaction for offline
  correlation.
- `WorkoutGate`: movement-based transmitting state.
- `BootReceiver` + `BridgeService`: automatic foreground startup and continuous readiness.
- `BootstrapActivity`: a no-window, no-launcher entry point used once by the headless installer.
- `scripts/`: read-only ADB inventory, workout log capture, install, and probe retrieval.

## Build

Requirements: JDK 17+ and Android SDK 36.

```bash
export ANDROID_HOME=/home/user/android-sdk
./gradlew testDebugUnitTest assembleDebug assembleHeadless
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. It includes diagnostics, test-power
buttons, and raw Binder capture. The signed, minified headless APK is
`app/build/outputs/apk/headless/app-headless.apk`; it has no launcher icon or visible activity.
The install scripts stop the other flavor first because Android permits only one owner for the
standard cycling GATT services. The debug flavor does not auto-start at boot.

## Headless operation

The discovery UI exists only in the debug build. The normal bike-side build runs as a foreground
service with no app window or launcher icon, starts once during installation, and restarts from
`BOOT_COMPLETED` after subsequent tablet boots:

```bash
./gradlew assembleHeadless
./scripts/install-headless.sh
```

"Foreground" is Android lifecycle terminology: no app screen stays open. On Android 13+, the
installer intentionally does not grant notification permission, so the service is listed in Android's
active-app Task Manager without placing an ongoing item in the notification drawer. On older Android
versions, the OS may show a silent ongoing notification. Force-stopping the package disables
automatic boot delivery until the installer/bootstrap component is invoked again.
The installer grants the two required Bluetooth permissions through ADB, so a successful headless
install does not display a Bluetooth or location prompt. Location and scan permission are not used.

## Sideload and discovery

Installing the debug build force-stops the headless bridge on the target bike. To restore it, rerun the headless installer. See the [discovery runbook](DISCOVERY.md).

```bash
adb devices -l
export ADB_SERIAL=192.168.1.100:PORT
./scripts/probe-bike.sh
./scripts/install-debug.sh
adb -s "$ADB_SERIAL" logcat -s PeloPower
```

## Prepare an update

Run `./scripts/build-release.sh` with JDK 17, Android SDK 36, and Build Tools 36.0.0. It runs the unit tests, builds the headless APK, verifies its APK signature, and checks the signer against the public v1.3.1 release certificate. A new machine's generated debug key will fail this check.

The original distribution used the maintainer's Android debug keystore. To build with a restored keystore, set `PELOPOWER_KEYSTORE` to its absolute path and supply `PELOPOWER_STORE_PASSWORD`, `PELOPOWER_KEY_ALIAS`, and `PELOPOWER_KEY_PASSWORD` through your local secret store. Preserve an independently recoverable private backup. The certificate fingerprint and the published APK contain only the public certificate.

Before uploading a new release, install v1.3.1 on a test device, then use `adb install -r` with the candidate APK and verify its version with `adb shell dumpsys package dev.pelopower.bridge`.
