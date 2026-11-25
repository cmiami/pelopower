package dev.pelopower.bridge.tools;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Handler;
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

    private final BluetoothAdapter adapter;
    private final AdvertisingSetParameters parameters;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeAdvertiser advertiser;
    private PowerCallback current;
    private AdvertisingSet activeSet;
    private final Runnable checkSet = this::requestAddress;
    private final Runnable retry = this::startIfEnabled;
    private final Runnable timeout = () -> retryAfterFailure("Controller callback timed out");

    private PublicAddressAdvertiser(BluetoothAdapter adapter) throws ReflectiveOperationException {
        this.adapter = adapter;
        parameters = parametersWithAddressType(ADDRESS_TYPE_PUBLIC);
    }

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
        if (adapter == null) throw new IllegalStateException("No Bluetooth adapter");
        PublicAddressAdvertiser helper = new PublicAddressAdvertiser(adapter);
        helper.checkRadio.run();
        Looper.loop();
    }

    // A shell app_process has no registered Android application to receive adapter broadcasts.
    // Observe radio transitions and recheck the controller even if a fast reset fell between polls.
    private int previousState = BluetoothAdapter.ERROR;
    private final Runnable checkRadio = new Runnable() {
        @Override
        public void run() {
            int state = adapter.getState();
            if (state != previousState) {
                System.out.println("Bluetooth state=" + state);
                previousState = state;
                if (state == BluetoothAdapter.STATE_ON) startIfEnabled();
                else reset();
            }
            handler.postDelayed(this, 1000);
        }
    };

    private void startIfEnabled() {
        if (current != null || !adapter.isEnabled()) return;
        handler.removeCallbacks(retry);
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                retryAfterFailure("BLE advertiser unavailable");
                return;
            }
            current = new PowerCallback();
            advertiser.startAdvertisingSet(parameters, new AdvertiseData.Builder()
                    .addServiceUuid(CPS).build(), new AdvertiseData.Builder()
                    .setIncludeDeviceName(true).build(), null, null, current);
            handler.postDelayed(timeout, 10000);
        } catch (RuntimeException e) {
            retryAfterFailure("Advertising startup failed: " + e);
        }
    }

    private void reset() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(checkSet);
        activeSet = null;
        AdvertisingSetCallback previous = current;
        current = null;
        if (previous != null && advertiser != null) {
            try {
                advertiser.stopAdvertisingSet(previous);
            } catch (RuntimeException e) {
                Log.w(TAG, "Controller already unavailable", e);
            }
        }
        advertiser = null;
    }

    private void retryAfterFailure(String reason) {
        System.out.println(reason);
        reset();
        handler.postDelayed(retry, 3000);
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

    private void requestAddress() {
        if (activeSet == null) return;
        try {
            Method getOwnAddress = AdvertisingSet.class.getDeclaredMethod("getOwnAddress");
            getOwnAddress.setAccessible(true);
            getOwnAddress.invoke(activeSet);
            handler.postDelayed(timeout, 10000);
        } catch (ReflectiveOperationException | RuntimeException e) {
            retryAfterFailure("Controller address check failed: " + e);
        }
    }

    private final class PowerCallback extends AdvertisingSetCallback {
        private boolean loggedAddress;

        @Override
        public void onAdvertisingSetStopped(AdvertisingSet set) {
            if (current == this) retryAfterFailure("Advertising set stopped");
        }

        @Override
        public void onAdvertisingSetStarted(
                AdvertisingSet advertisingSet, int txPower, int status) {
            if (current != this) return;
            handler.removeCallbacks(timeout);
            System.out.println("Public CPS advertiser start status=" + status + " txPower=" + txPower);
            if (status != ADVERTISE_SUCCESS || advertisingSet == null) {
                retryAfterFailure("Controller rejected start");
                return;
            }
            activeSet = advertisingSet;
            requestAddress();
        }

        // Hidden system callback paired with AdvertisingSet.getOwnAddress(). Declaring it here
        // still overrides the runtime framework method when this class is launched by app_process.
        @SuppressWarnings("unused")
        public void onOwnAddressRead(
                AdvertisingSet advertisingSet, int addressType, String address) {
            if (current != this) return;
            handler.removeCallbacks(timeout);
            if (addressType != ADDRESS_TYPE_PUBLIC) {
                retryAfterFailure("Controller returned a non-public address");
                return;
            }
            if (!loggedAddress) {
                System.out.println("Public CPS advertiser addressType=" + addressType + " address=" + address);
                loggedAddress = true;
            }
            handler.removeCallbacks(checkSet);
            handler.postDelayed(checkSet, 30000);
        }
    }
}
