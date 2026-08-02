# PeloPower

> [!WARNING]
> **Proof of concept — external-radio hardware path not yet validated.** The Android APK and
> nRF52840 firmware build successfully, and the APK has been exercised on a G700 Peloton, but the
> USB dongle-to-Garmin path has not been tested with physical hardware yet. Treat all packaged
> artifacts as prerelease software until an nRF52840 Dongle and Garmin complete an over-the-air
> power/cadence/speed validation.

PeloPower is a minimal Android BLE power, cadence, and speed bridge for Peloton bikes. It runs on
the Peloton tablet, reads the local sensor service, and exposes standard Bluetooth Cycling Power
and Cycling Speed and Cadence services to Garmin, Zwift, and other BLE receivers.

Current status: metric decoding and both BLE GATT services are validated on a G700 Cross Training
Bike+ running Android 13, Affernet 2.4.508, and tablet build TA.260520.D. An iPhone running nRF
Connect detects `PLTN-RTR01`, connects, and discovers the Cycling Power and Cycling Speed and
Cadence services, proving that the tablet's internal BLE radio is transmitting. A Garmin epix Pro
(Gen 2) does not yet list it in native sensor search. The Android build now provides the required
CSC control point, Device Information, a concrete sensor location, and the local name in the
primary advertisement; that compatibility revision still needs a Garmin retest. The remaining
known profile gap is the Cycling Power Sensor Appearance field: the Bluetooth profile requires it
in advertising or scan-response data, while Android's application advertising API on this image
does not expose that AD type. PeloPower 1.1 also includes a direct nRF52840 USB-radio path capable
of emitting the complete raw advertisement, but that hardware path remains untested.

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

## Repository shape

- `CyclingPowerGattServer`: dependency-free Android GATT server advertising Cycling Power Service
  `0x1818` and Cycling Speed and Cadence Service `0x1816`. Cadence and speed are present as crank
  and wheel revolution data in both profiles for broad receiver compatibility.
- `UsbBleBridge`: direct Android USB-host transport for the external nRF52840 BLE radio. It runs in
  parallel with the built-in-radio path, so one APK works on affected and unaffected bikes.
- `firmware/nrf52840-dongle`: Zephyr firmware that owns BLE advertising, CPS, CSCS, the CSC control
  point, Garmin connections, and notifications without using the Peloton Bluetooth controller.
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
An external USB radio needs Android's separate one-time USB-device approval. Accepting the attach
dialog makes PeloPower the handler and grants access until disconnection; the app reconnects to the
dongle automatically after service or tablet restarts.

## Sideload and discovery

See [docs/DISCOVERY.md](docs/DISCOVERY.md). The short path is:

```bash
adb devices -l
export ADB_SERIAL=192.168.1.100:PORT
./scripts/probe-bike.sh
./scripts/install-debug.sh
adb logcat -s PeloPower
```

## USB BLE radio workaround

A normal PC Bluetooth 5.x dongle will enumerate on the G700, but it will not automatically become a
second Android `BluetoothAdapter`. This image exposes one vendor HAL and binds its internal USB
controller through MediaTek's `btmtk_usb_unify` driver rather than a generic USB Bluetooth path.
Replacing the Android Bluetooth framework would require root/system-image changes.

The supported bypass is a [Nordic nRF52840 Dongle](https://www.nordicsemi.com/Products/Development-hardware/nrf52840-dongle),
which is itself a USB-powered Bluetooth 5 radio with native USB and an integrated antenna. PeloPower
puts the GATT server on that radio and uses Android's supported USB-host API only as a metric cable:

```text
Peloton sensor service -> PeloPower APK -> USB CDC -> nRF52840 -> BLE CPS/CSCS -> Garmin
```

Build and flash it with the instructions in
[`firmware/nrf52840-dongle/README.md`](firmware/nrf52840-dongle/README.md), or program the packaged
`firmware/nrf52840-dongle/artifacts/pelopower-nrf52840-dongle-1.0.0.hex` using Nordic's nRF Connect
Programmer. Plug it
into a bike USB host port (use the appropriate OTG adapter if the exposed connector is USB-C),
accept the one-time Android USB prompt, and search the Garmin for `PeloPower` as both a power sensor
and a speed/cadence sensor. No Peloton workout or pedaling is needed for discovery.

The firmware advertises continuously at +8 dBm, exposes Cycling Power `0x1818` and Cycling Speed and
Cadence `0x1816`, uses the standard Cycling Power Sensor appearance, supports Garmin's CSC cumulative
wheel setup procedure, and retains a stable BLE identity across power cycles. It reports idle zeros
when the APK is absent or USB data is stale. This path still needs a physical nRF52840/Garmin test;
the checked-in firmware has been compiled for the exact dongle target but cannot be RF-tested without
the dongle.

## BLE behavior

The service advertises continuously at a fast interval and high transmit-power setting so a Garmin
or other receiver can be paired before a workout. The bike is mains-powered, so advertising is not
throttled for battery life. A connected receiver gets zero power/cadence/speed once per second while
the bike is idle; live values replace those zeros as soon as movement is detected. The bridge also
re-registers its GATT services automatically if the tablet's Bluetooth radio is toggled.
Once the power source produces three moving samples (about 400 ms on Bike+/G700), it notifies
instantaneous power, cadence, and virtual-wheel speed; after 30 seconds without movement it sends
a final zero measurement and returns to waiting.

Advertising does not depend on pedaling or on a Peloton workout. A receiver that cannot discover
the service while the bike is idle will not be fixed by starting a workout. On G700 build
TA.260520.D, the internal advertisement and GATT services are visible in nRF Connect, but native
Garmin pairing is not yet validated; see the discovery runbook for the current evidence.

Power, cadence, and speed are in scope. FTMS, ERG/SIM control, ANT+, ride recording, and resistance
writes remain out of scope for this small sensor bridge.

BLE cycling profiles represent speed as cumulative wheel revolutions rather than instantaneous
km/h. PeloPower uses a virtual wheel circumference of **2096 mm**. Configure the receiving device
to that wheel size (manual rather than automatic calibration) so its derived speed matches the bike.
The BikeData contract exposes centiwatts and RPM. PeloPower derives Peloton-style speed from power
using the established display curve and converts it to cumulative wheel revolutions for BLE.

## Safety and support boundary

This project is unaffiliated with Peloton and depends on undocumented interfaces that an OTA update
can change. The discovery path performs read-only service binding and capture. It does not root the
tablet, modify system packages, write resistance, bypass a membership, or contact Peloton services.
