# Public-address advertiser for Garmin pairing

On the tested G700, this ADB shell helper advertised Cycling Power using the bike controller's
public Bluetooth address. The headless APK continued providing the GATT services and ride
measurements. The Garmin paired as one Power sensor while both were running.

### Use the release helper

Download [public-cps-probe.jar](https://github.com/cmiami/pelopower/releases/latest/download/public-cps-probe.jar)
to your computer. Install the headless APK and connect ADB to the bike, then run the following
from the download directory. If several devices appear in `adb devices`, set `ANDROID_SERIAL`
to the bike’s connection endpoint.

```sh
adb push public-cps-probe.jar /data/local/tmp/public-cps-probe.next.jar
adb shell 'ps -A -o PID,ARGS | while read -r pid args; do if [ "$args" = "app_process / dev.pelopower.bridge.tools.PublicAddressAdvertiser" ]; then kill "$pid"; fi; done'
adb shell mv /data/local/tmp/public-cps-probe.next.jar /data/local/tmp/public-cps-probe.jar
adb shell 'CLASSPATH=/data/local/tmp/public-cps-probe.jar nohup app_process / dev.pelopower.bridge.tools.PublicAddressAdvertiser >/data/local/tmp/public-cps-probe.log 2>&1 </dev/null &'
adb shell cat /data/local/tmp/public-cps-probe.log
```

Look for `start status=0` and `addressType=0`. If startup is still in progress, read the log
again. Search for a Power sensor on the watch. If a confirmation number appears on both devices,
check that the numbers match before confirming.

The helper uses an address-selection API available to the ADB shell on the tested Android image.
After a Bluetooth off/on cycle it requests a new advertising set automatically. It also checks that the controller still answers for its set every 30 seconds. Its process exits on a full tablet reboot. If discovery needs the helper again, rerun the final two commands
using the JAR already on the bike.

### Build the helper from source

Install the headless APK first. From the repository root, run the following on a computer
with JDK 17, Android SDK 36, build-tools 36.0.0 and an authorized ADB connection.
If multiple devices appear in `adb devices`, set `ANDROID_SERIAL` to the bike's connection endpoint.

```sh
work=$(mktemp -d)
mkdir "$work/classes"
android_jar="$ANDROID_HOME/platforms/android-36/android.jar"
javac --release 8 -classpath "$android_jar" -d "$work/classes" \
  tools/android-public-advertiser/PublicAddressAdvertiser.java
jar cf "$work/classes.jar" -C "$work/classes" .
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 33 --lib "$android_jar" \
  --output "$work/public-cps-probe.jar" "$work/classes.jar"
```

The resulting JAR is at `$work/public-cps-probe.jar`.
