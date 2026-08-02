# Cross Training Bike+ discovery runbook

The 2025 Cross Training Bike+ has a new tablet and no public sensor API. PeloPower's read-only
Bike+ contract was validated on model `g700`, Android 13, tablet build `TA.260520.D`, and Affernet
2.4.508 on 2026-08-02. Keep recording version information because Peloton OTA updates can still
change the private interface.

The same G700 image has a confirmed firmware-level BLE-peripheral blocker. Android reports
multiple-advertisement support, registers CPS/CSCS, and calls the advertising success callback,
but an epix Pro (Gen 2) and raw Mac CoreBluetooth scans detect no packet. The result is unchanged
with power/cadence at zero, high and low transmit power, fast and slow intervals, advertisement-only
payloads, legacy and extended advertising, random and public addresses, all relevant permissions,
a Bluetooth restart, and Peloton's heart-rate scanner stopped. Do not use pedaling or workout state
as a discovery diagnostic: advertising starts before either one.

An untouched build of doudar Grupetto v0.0.37 was run as the control. Its watchdog reported active
advertising and Android showed FTMS, CPS, CSCS, and Device Information registered, but an external
scan still found zero bike packets. Its source uses the same Android advertiser API and differs by
adding FTMS/DIS, scan-response metadata, restart logic, and older permissions; none changes the RF
result on this firmware. Its repository has no declared license, so treat it as prior art unless
the author clarifies reuse terms.

The decisive capture came from Android's Developer Options Bluetooth HCI snoop log. It contains
the `PPROBE-modern-legacy` payload and successful controller completions for Extended Advertising
Parameters (`0x2036`), Data (`0x2037`), Scan Response (`0x2038`), and Enable (`0x2039`). Parameters
requested all three advertising channels, +1 dBm, and a 100–131 ms interval. A privileged shell
control repeated the sequence with the public adapter address and received the same successful
completion, while the external scanner found 188 other devices and zero bike packets. This places
the failure after HCI command acceptance, in the MediaTek controller/firmware RF path.

The production workaround does not ask that controller to transmit. PeloPower 1.1 can send the
already-encoded CPS/CSCS measurements over Android USB host mode to the checked-in nRF52840 firmware.
The dongle then advertises and serves GATT on its own controller and antenna. The G700 advertises
`android.hardware.usb.host`, exposes xHCI host buses, and makes USB endpoints available through
`UsbManager`; the remaining acceptance step is a physical dongle/Garmin test.

## 1. Establish ADB access

On the Bike+, open Device Settings, enable developer options by tapping Build number seven times,
then enable USB debugging. Connect a data-capable USB-C cable and approve the host key.

```bash
adb devices -l
export ADB_SERIAL=192.168.1.100:PORT
./scripts/probe-bike.sh
```

If `adb devices` is empty, try another cable/port before changing software. OpenPelo is a current
GUI alternative for enabling and using ADB: <https://github.com/doudar/Openpelo>.

## 2. Install the discovery APK

```bash
./gradlew assembleDebug
./scripts/install-debug.sh
adb logcat -s PeloPower
```

In the app:

1. Confirm `BLE peripheral advertising: true`. Central-only BLE hardware cannot impersonate a
   power meter.
2. Tap **Probe legacy Bike+ sensor service**. A `BOUND` result proves that the package, action,
   permission, and Binder descriptor still exist on this image.
3. Tap **Capture raw legacy Binder snapshot** once idle and several times while pedaling at stable
   displayed power/cadence/speed combinations (for example idle, about 100 W/60 rpm, and about
   200 W/90 rpm). Pull the files with
   `./scripts/pull-probes.sh`.
4. Tap **Test 150 W**, then scan for a Bluetooth power sensor on an external device. The advertised
   local name is the tablet's Bluetooth name; identify it by its Cycling Power Service.

The raw snapshot action uses the legacy read-only transaction observed in older Bike+ software.
It is intentionally debug-only. If the service is missing or access is denied, stop using that
path and inspect the Cross Training APKs before trying any other transaction numbers.

## 3. Correlate UI state and raw data

```bash
./scripts/capture-workout.sh
```

Run a short sequence with spoken or written markers: idle, workout loading, 0 W, steady low power,
steady high power, paused, ended. Keep screenshots or a phone video of displayed power, cadence,
and speed with a visible clock. Decode watts and RPM first; existing community code derives speed
from power, so verify that formula against the Cross Training display rather than guessing a raw
speed field from one capture.

For offline inspection, obtain package paths with `adb shell pm path PACKAGE`, pull the APKs, and
inspect them in JADX. Start with `com.onepeloton.affernetservice` and the foreground Peloton app.
Do not modify, replace, or resign system APKs.

## 4. Decoder acceptance criteria

The implemented decoder binds `IBikeInterface`, polls the read-only BikeData transaction, rejects
unexpected leading fields, and reconnects after Binder death or repeated read errors. For a newly
supported tablet build, validate that it:

- matches displayed watts, cadence, and speed across idle, low, medium, and sprint samples;
- survives workout start, pause, resume, and end;
- handles service death and Peloton app restarts without leaking Binder connections;
- emits no power after a stale-data timeout;
- reports package/build identifiers with failures so OTA regressions are diagnosable.

`WorkoutGate` starts transmission after three moving readings (at least 5 W, 10 rpm, or 1 km/h)
and returns to waiting after 30 seconds without movement. BLE advertising remains available while
waiting so an external receiver can connect before the ride begins.
