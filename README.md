![PeloPower — Power and cadence over Bluetooth](docs/pelopower-banner.svg)

# PeloPower

Ride with the Peloton screen you already use and record watts and cadence together on Garmin.
PeloPower reads the bike’s sensors in the background and sends both measurements through one
Bluetooth Power sensor pairing. The tested setup used a G700 Cross Training Bike+, Android 13,
Affernet 3.4.1, and the bike’s built-in radio.

**[Download the headless APK](https://github.com/cmiami/pelopower/releases/latest/download/pelopower-headless.apk)** · [Releases and checksums](https://github.com/cmiami/pelopower/releases) · [Garmin pairing](#pair-a-garmin)

## Install on the bike

These steps use Wi-Fi on the Android 13 Bike+. Connect the bike and computer to the **same Wi-Fi network**.

**On the computer**

1. Download and unzip [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools) for your computer. Put the [APK](https://github.com/cmiami/pelopower/releases/latest/download/pelopower-headless.apk) and [install script](https://github.com/cmiami/pelopower/releases/latest/download/install-headless.sh) inside the extracted `platform-tools` folder, alongside `adb` or `adb.exe`.
2. Open a terminal in that folder (use **Git Bash** on Windows). Run these two lines and keep the terminal open. You should see an Android Debug Bridge version.

   ```bash
   export PATH="$PWD:$PATH"
   adb version
   ```

**On the bike**

3. Open the Peloton **Settings** menu → **Device Settings** → **About tablet** (on some builds, **System → About tablet**). Tap **Build number** seven times until Android confirms developer mode.
4. Go back to **Device Settings → System → Developer options**. Turn on **Wireless debugging** and tap **Allow** if prompted for this network. Then tap the **words “Wireless debugging”** to open its settings page.
5. Tap **Pair device with pairing code**. Leave the dialog open. It displays a **six-digit code** and an **IP address with a pairing port**.

**Back on the computer**

6. Run the command below using the address and port **from the pairing dialog** in place of the example. At the prompt, type the six-digit code and press Enter. Wait for `Successfully paired`.

   ```bash
   adb pair 192.168.1.50:37001
   ```

7. On the bike, return to the main **Wireless debugging** page. Read **IP address & port** there. This is the **connection port**. Replace the example address below with that value, then run all four lines in the same terminal.

   ```bash
   export ADB_SERIAL=192.168.1.50:41002
   adb connect "$ADB_SERIAL"
   adb -s "$ADB_SERIAL" get-state
   bash install-headless.sh ./pelopower-headless.apk
   ```

   The state check should print `device`. The installer prints `Success`, grants Bluetooth permissions, and starts PeloPower. There’s no app icon to open. Continue to [Pair a Garmin](#pair-a-garmin).

If the code expires, reopen **Pair device with pairing code** and repeat step 6 with the new values. If the connection is refused after pairing, reread **IP address & port** on the main Wireless debugging page and repeat step 7.

## Releases

| Download | Use |
| --- | --- |
| [Headless APK](https://github.com/cmiami/pelopower/releases/latest/download/pelopower-headless.apk) | Install on the Peloton tablet. |
| [Installer](https://github.com/cmiami/pelopower/releases/latest/download/install-headless.sh) | Run on the computer connected to the bike. |
| [Public-address advertiser](https://github.com/cmiami/pelopower/releases/latest/download/public-cps-probe.jar) | Optional pairing helper used in the successful Garmin test. |

## Pair a Garmin

1. [Install the headless APK](#install-on-the-bike). It starts automatically after installation and boot.
2. On the Garmin, open **Sensors & Accessories → Add New → Power** and select **PeloPower**.
3. If the watch cannot find or pair with the sensor, start the [public-address advertiser](tools/android-public-advertiser/README.md) through ADB and repeat the search. This helper was running during the successful G700 test.
4. Start an Indoor Bike activity with power and cadence data fields and pedal. Both readings share the same subscription.

The APK stays discoverable between rides and pauses measurement notifications after 30 seconds
without movement. The helper runs until the tablet reboots or its process stops. Reconnection
after reboot using the APK alone still needs testing. If the tablet only dims when idle, check
**Developer options → Stay awake**. Turning that setting off allowed the tested tablet's configured
display timeout to apply.

## How the ride reaches your watch

![Peloton sensors feed PeloPower, which sends watts and cadence to Garmin over Bluetooth](docs/ride-path.svg)

## Repository shape

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
The headless flavor uses the Android debug signing key for convenient private sideloading. Configure
a private release signing key before distributing the APK beyond bikes you manage.

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

See [docs/DISCOVERY.md](docs/DISCOVERY.md). The short path is:

```bash
adb devices -l
export ADB_SERIAL=192.168.1.100:PORT
./scripts/probe-bike.sh
./scripts/install-debug.sh
adb logcat -s PeloPower
```

## BLE behavior

The headless APK advertises a single Cycling Power sensor at a balanced interval, including
while idle for pairing. Pair it as a Power sensor to receive watts and crank cadence together.
The separate CSC service is available after connection. Android BLE measurements pause
between workouts. The bridge requests no display wake lock and uses the tablet's display timeout.
Once the power source produces three moving samples (about 400 ms on Bike+/G700), it notifies
instantaneous power, cadence, and virtual-wheel speed; after 30 seconds without movement it sends
a final zero measurement and returns to waiting.

Power, cadence, and speed are in scope. FTMS, ERG/SIM control, ANT+, ride recording, and resistance
writes remain out of scope for this small sensor bridge.

BLE cycling profiles represent speed as cumulative wheel revolutions rather than instantaneous
km/h. PeloPower uses a virtual wheel circumference of **2096 mm**. Configure the receiving device
to that wheel size (manual rather than automatic calibration) so its derived speed matches the bike.
The BikeData contract exposes centiwatts and RPM. PeloPower derives Peloton-style speed from power
using the established display curve and converts it to cumulative wheel revolutions for BLE.

## What already exists

This is not a greenfield idea:

- [QZ / qdomyos-zwift](https://github.com/cagnulein/qdomyos-zwift) is GPL-3.0, implements virtual
  fitness devices, and its 2026-07-15 nightly publishes a Bike+ build using a Grupetto backend. It
  is a broad app rather than a tiny appliance.
- [Grupetto](https://github.com/selalipop/grupetto) reverse-engineered the Peloton internal sensor
  service and displays live Bike/Bike+ metrics. The active
  [doudar fork](https://github.com/doudar/grupetto/releases/tag/v0.0.37) now detects `PLTN-ATR` and
  G700 Cross Trainer model strings and includes CPS, CSCS, and FTMS broadcasting. This is the first
  implementation to test on the target bike. Those changes remain in an unmerged pull request,
  current issue reports include BLE discovery/dropout failures, and neither Grupetto repository
  currently declares a license, so PeloPower does not copy its source.
- [OpenPelo](https://github.com/doudar/Openpelo) is an actively maintained ADB installer/device
  manager and is the easiest current sideload route.
- DFC and FitSwitch are hardware bridges. DFC targets the original Bike wiring; FitSwitch is a
  current commercial Bike/Bike+ solution with BLE, ANT+, FTMS, and control features.

The reason for this project is narrower: independently validate the new 2025 Cross Training Bike+
contract and produce a very small boot-starting CPS/CSCS broadcaster with no cloud, account,
overlay, workout storage, FTMS control, or analytics. If the doudar Grupetto release is stable on
the bike and Garmin, extending it may be less work; PeloPower remains useful as a minimal fallback.

## Safety and support boundary

This project is unaffiliated with Peloton and depends on undocumented interfaces that an OTA update
can change. The discovery path performs read-only service binding and capture. It does not root the
tablet, modify system packages, write resistance, bypass a membership, or contact Peloton services.
