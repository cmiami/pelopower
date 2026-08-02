package dev.pelopower.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressLint("MissingPermission") // Every entry point checks both revocable Bluetooth permissions.
public final class CyclingPowerGattServer {
    public interface StatusListener {
        void onBleStatus(String status);
    }

    private static final String TAG = "PeloPower";
    private static final UUID CPS = uuid16(0x1818);
    private static final UUID CSCS = uuid16(0x1816);
    private static final UUID MEASUREMENT = uuid16(0x2A63);
    private static final UUID FEATURE = uuid16(0x2A65);
    private static final UUID CSC_MEASUREMENT = uuid16(0x2A5B);
    private static final UUID CSC_FEATURE = uuid16(0x2A5C);
    private static final UUID SENSOR_LOCATION = uuid16(0x2A5D);
    private static final UUID CCCD = uuid16(0x2902);

    private final Context context;
    private final StatusListener statusListener;
    private final Set<BluetoothDevice> powerSubscribers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<BluetoothDevice> cadenceSubscribers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final CadenceTracker cadenceTracker = new CadenceTracker();
    private final WheelTracker wheelTracker = new WheelTracker();
    private BluetoothAdapter adapter;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic measurement;
    private BluetoothGattCharacteristic cadenceMeasurement;
    private BluetoothGattService pendingCadenceService;
    private BluetoothLeAdvertiser advertiser;
    private boolean advertising;
    private boolean starting;
    private AdvertiseSettings advertiseSettings;
    private AdvertiseData advertiseData;
    private AdvertiseData scanResponse;

    public CyclingPowerGattServer(Context context, StatusListener statusListener) {
        this.context = context.getApplicationContext();
        this.statusListener = statusListener;
    }

    public String start() {
        if (!hasBluetoothPermission()) return "Bluetooth permission missing";
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null) return "Bluetooth unavailable";
        adapter = manager.getAdapter();
        if (!adapter.isEnabled()) return "Bluetooth is off";
        if (!adapter.isMultipleAdvertisementSupported()) {
            return "BLE peripheral advertising unsupported";
        }

        stop();
        gattServer = manager.openGattServer(context, callback);
        if (gattServer == null) return "Could not open GATT server";

        BluetoothGattService service = new BluetoothGattService(
                CPS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        measurement = new BluetoothGattCharacteristic(
                MEASUREMENT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        measurement.addDescriptor(new BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));

        BluetoothGattCharacteristic feature = new BluetoothGattCharacteristic(
                FEATURE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        // Wheel Revolution Data (bit 2) and Crank Revolution Data (bit 3).
        feature.setValue(new byte[] {0x0C, 0, 0, 0});

        BluetoothGattCharacteristic location = new BluetoothGattCharacteristic(
                SENSOR_LOCATION,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        location.setValue(new byte[] {0}); // Other/unspecified.

        pendingCadenceService = new BluetoothGattService(
                CSCS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        cadenceMeasurement = new BluetoothGattCharacteristic(
                CSC_MEASUREMENT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        cadenceMeasurement.addDescriptor(new BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        BluetoothGattCharacteristic cadenceFeature = new BluetoothGattCharacteristic(
                CSC_FEATURE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        cadenceFeature.setValue(new byte[] {0x03, 0x00}); // Wheel and crank data supported.
        BluetoothGattCharacteristic cadenceLocation = new BluetoothGattCharacteristic(
                SENSOR_LOCATION,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        cadenceLocation.setValue(new byte[] {0});
        pendingCadenceService.addCharacteristic(cadenceMeasurement);
        pendingCadenceService.addCharacteristic(cadenceFeature);
        pendingCadenceService.addCharacteristic(cadenceLocation);

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) return "BLE advertiser unavailable";
        advertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();
        advertiseData = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(CPS))
                .addServiceUuid(new ParcelUuid(CSCS))
                .setIncludeTxPowerLevel(false)
                .build();
        scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addManufacturerData(0xFFFF, new byte[] {'P', 'e', 'l', 'o', 'P', 'o', 'w', 'e', 'r'})
                .build();

        service.addCharacteristic(measurement);
        service.addCharacteristic(feature);
        service.addCharacteristic(location);
        if (!gattServer.addService(service)) return "Could not register Cycling Power Service";
        starting = true;
        return "Registering BLE Cycling Power Service";
    }

    public void publish(PowerSample sample) {
        if (gattServer == null || measurement == null || !hasBluetoothPermission()) return;
        CadenceTracker.State cadence = cadenceTracker.accept(
                sample.cadenceRpm(), sample.monotonicMillis());
        WheelTracker.State wheel = wheelTracker.accept(
                sample.speedCentiKph(), sample.monotonicMillis());
        byte[] powerValue = CyclingPowerMeasurement.metrics(sample.watts(), wheel, cadence);
        byte[] cadenceValue = CyclingCadenceMeasurement.wheelAndCrankData(wheel, cadence);
        for (BluetoothDevice device : powerSubscribers) {
            if (Build.VERSION.SDK_INT >= 33) {
                gattServer.notifyCharacteristicChanged(device, measurement, false, powerValue);
            } else {
                measurement.setValue(powerValue);
                gattServer.notifyCharacteristicChanged(device, measurement, false);
            }
        }
        for (BluetoothDevice device : cadenceSubscribers) {
            if (Build.VERSION.SDK_INT >= 33) {
                gattServer.notifyCharacteristicChanged(
                        device, cadenceMeasurement, false, cadenceValue);
            } else {
                cadenceMeasurement.setValue(cadenceValue);
                gattServer.notifyCharacteristicChanged(device, cadenceMeasurement, false);
            }
        }
    }

    public void stop() {
        if (hasBluetoothPermission()) {
            if (advertiser != null && advertising) advertiser.stopAdvertising(advertiseCallback);
            if (gattServer != null) gattServer.close();
        }
        powerSubscribers.clear();
        cadenceSubscribers.clear();
        advertiser = null;
        gattServer = null;
        measurement = null;
        cadenceMeasurement = null;
        pendingCadenceService = null;
        advertising = false;
        starting = false;
        advertiseSettings = null;
        advertiseData = null;
        scanResponse = null;
    }

    public boolean isReady() {
        return (advertising || starting) && gattServer != null;
    }

    public int subscriberCount() {
        java.util.HashSet<BluetoothDevice> all = new java.util.HashSet<>(powerSubscribers);
        all.addAll(cadenceSubscribers);
        return all.size();
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void beginAdvertising() {
        if (advertiser == null || advertiseSettings == null || advertising) return;
        try {
            advertiser.startAdvertising(
                    advertiseSettings, advertiseData, scanResponse, advertiseCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "BLE advertising permission denied", e);
            statusListener.onBleStatus("Advertising permission denied");
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            starting = false;
            advertising = true;
            Log.i(TAG, "Cycling Power Service is advertising");
            statusListener.onBleStatus("Advertising CPS + CSCS");
        }

        @Override
        public void onStartFailure(int errorCode) {
            starting = false;
            advertising = false;
            Log.e(TAG, "BLE advertising failed: " + errorCode);
            statusListener.onBleStatus("Advertising failed: " + advertiseError(errorCode));
        }
    };

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                starting = false;
                Log.e(TAG, "Cycling service registration failed: " + status);
                statusListener.onBleStatus("GATT registration failed: " + status);
                return;
            }
            if (CPS.equals(service.getUuid()) && pendingCadenceService != null && gattServer != null) {
                if (!gattServer.addService(pendingCadenceService)) {
                    Log.e(TAG, "Could not register Cycling Speed and Cadence Service");
                    statusListener.onBleStatus("Could not register CSCS");
                }
            } else if (CSCS.equals(service.getUuid())) {
                beginAdvertising();
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                powerSubscribers.remove(device);
                cadenceSubscribers.remove(device);
            }
            Log.i(TAG, "BLE client state=" + newState + " status=" + status);
        }

        @Override
        public void onCharacteristicReadRequest(
                BluetoothDevice device,
                int requestId,
                int offset,
                BluetoothGattCharacteristic characteristic) {
            if (!hasBluetoothPermission() || gattServer == null) return;
            byte[] value = characteristic.getValue();
            if (value == null) value = new byte[0];
            if (offset > value.length) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            } else {
                byte[] response = java.util.Arrays.copyOfRange(value, offset, value.length);
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response);
            }
        }

        @Override
        public void onDescriptorReadRequest(
                BluetoothDevice device,
                int requestId,
                int offset,
                BluetoothGattDescriptor descriptor) {
            if (!hasBluetoothPermission() || gattServer == null) return;
            Set<BluetoothDevice> target = subscribersFor(descriptor);
            byte[] value = target.contains(device)
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
        }

        @Override
        public void onDescriptorWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattDescriptor descriptor,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value) {
            Set<BluetoothDevice> target = subscribersFor(descriptor);
            if (java.util.Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                target.add(device);
            } else {
                target.remove(device);
            }
            if (responseNeeded && hasBluetoothPermission() && gattServer != null) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }
    };

    private Set<BluetoothDevice> subscribersFor(BluetoothGattDescriptor descriptor) {
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        return characteristic != null && CSC_MEASUREMENT.equals(characteristic.getUuid())
                ? cadenceSubscribers
                : powerSubscribers;
    }

    private static UUID uuid16(int value) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", value));
    }

    private static String advertiseError(int errorCode) {
        return switch (errorCode) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "unsupported";
            default -> "code " + errorCode;
        };
    }
}
