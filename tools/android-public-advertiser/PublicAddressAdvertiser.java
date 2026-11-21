package dev.pelopower.bridge.tools;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.content.Context;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * ADB-shell companion that advertises PeloPower's existing GATT database from the controller's
 * public address. Android intentionally reserves address selection for BLUETOOTH_PRIVILEGED
 * callers; the shell UID has that permission on the target Peloton image.
 */
public final class PublicAddressAdvertiser {
    private static final String TAG = "PeloPowerPublic";
    private static final int ADDRESS_TYPE_PUBLIC = 0;
    private static final ParcelUuid CPS = new ParcelUuid(
            UUID.fromString("00001818-0000-1000-8000-00805f9b34fb"));

    private PublicAddressAdvertiser() {}

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> serviceManagerClass = Class.forName("android.os.BluetoothServiceManager");
        Object serviceManager = serviceManagerClass.getDeclaredConstructor().newInstance();
        Class<?> frameworkInitializerClass = Class.forName(
                "android.bluetooth.BluetoothFrameworkInitializer");
        Method setServiceManager = frameworkInitializerClass.getDeclaredMethod(
                "setBluetoothServiceManager", serviceManagerClass);
        setServiceManager.setAccessible(true);
        setServiceManager.invoke(null, serviceManager);
        System.out.println("Bluetooth service manager initialized");

        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object activityThread = systemMain.invoke(null);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context context = (Context) getSystemContext.invoke(activityThread);
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            throw new IllegalStateException("Bluetooth adapter unavailable or disabled");
        }
        BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            throw new IllegalStateException("BLE advertiser unavailable");
        }

        AdvertisingSetParameters powerParameters = parametersWithAddressType(
                ADDRESS_TYPE_PUBLIC);
        System.out.println("Public Power own-address type selected");

        AdvertiseData powerData = new AdvertiseData.Builder()
                .addServiceUuid(CPS)
                .build();
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        advertiser.startAdvertisingSet(
                powerParameters,
                powerData,
                scanResponse,
                null,
                null,
                powerCallback);
        System.out.println("Public CPS advertiser requested; "
                + "waiting for controller callbacks");
        Log.i(TAG, "Requested public-address CPS advertisement");
        Looper.loop();
    }

    private static AdvertisingSetParameters parametersWithAddressType(int addressType)
            throws ReflectiveOperationException {
        AdvertisingSetParameters.Builder builder = new AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setScannable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH);
        Method setOwnAddressType = AdvertisingSetParameters.Builder.class.getDeclaredMethod(
                "setOwnAddressType", int.class);
        setOwnAddressType.setAccessible(true);
        setOwnAddressType.invoke(builder, addressType);
        return builder.build();
    }

    private static final AdvertisingSetCallback powerCallback =
            new LoggingAdvertisingSetCallback("Cycling Power");

    private static final class LoggingAdvertisingSetCallback extends AdvertisingSetCallback {
        private final String label;

        LoggingAdvertisingSetCallback(String label) {
            this.label = label;
        }

        @Override
        public void onAdvertisingSetStarted(
                AdvertisingSet advertisingSet, int txPower, int status) {
            Log.i(TAG, "Public " + label + " advertiser start status=" + status
                    + " txPower=" + txPower);
            System.out.println("Public " + label + " advertiser start status=" + status
                    + " txPower=" + txPower);
            if (status != ADVERTISE_SUCCESS || advertisingSet == null) return;
            try {
                Method getOwnAddress = AdvertisingSet.class.getDeclaredMethod("getOwnAddress");
                getOwnAddress.setAccessible(true);
                getOwnAddress.invoke(advertisingSet);
            } catch (ReflectiveOperationException e) {
                Log.w(TAG, "Could not request advertiser address", e);
            }
        }

        // Hidden system callback paired with AdvertisingSet.getOwnAddress(). Declaring it here
        // still overrides the runtime framework method when this class is launched by app_process.
        @SuppressWarnings("unused")
        public void onOwnAddressRead(
                AdvertisingSet advertisingSet, int addressType, String address) {
            Log.i(TAG, "Public " + label + " advertiser addressType=" + addressType
                    + " address=" + address);
            System.out.println("Public " + label + " advertiser addressType=" + addressType
                    + " address=" + address);
        }
    }
}
