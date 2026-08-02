# PeloPower nRF52840 USB BLE radio

This firmware turns a Nordic nRF52840 Dongle (`PCA10059`) into a self-contained Bluetooth
Cycling Power and Cycling Speed/Cadence sensor. The Android APK sends 32-byte metric packets over
USB CDC ACM; the dongle owns the BLE controller, antenna, advertising, GATT connections, and
notifications. It does not use the Peloton's MediaTek Bluetooth path.

## Build

Use Zephyr 4.4.0 and its ARM toolchain:

```sh
west build -p always -b nrf52840dongle/nrf52840 firmware/nrf52840-dongle
```

The output to program is `build/zephyr/zephyr.hex`. The normal board target preserves Nordic's
factory USB bootloader; do not use the `/bare` variant unless an external SWD debugger is attached.

## Flash through the factory USB bootloader

1. Plug the dongle into the Mac/PC and press its recessed RESET button. Its red LED pulses.
2. Open Nordic **nRF Connect for Desktop → Programmer**, select the dongle, add the checked-in
   `artifacts/pelopower-nrf52840-dongle-1.0.0.hex`, and choose **Write**.
3. Move the dongle to a USB host port on the Peloton. Accept Android's one-time USB access prompt.

The private prototype USB identity is `1915:521f`. Allocate a project-owned VID/PID before public
distribution and change the two firmware Kconfig values, `UsbBleBridge`, and the Android USB filter
together.
