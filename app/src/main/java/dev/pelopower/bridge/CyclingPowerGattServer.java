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
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
    private static final UUID DEVICE_INFORMATION = uuid16(0x180A);
    private static final UUID MEASUREMENT = uuid16(0x2A63);
    private static final UUID FEATURE = uuid16(0x2A65);
    private static final UUID CSC_MEASUREMENT = uuid16(0x2A5B);
    private static final UUID CSC_FEATURE = uuid16(0x2A5C);
    private static final UUID SENSOR_LOCATION = uuid16(0x2A5D);
    private static final UUID SC_CONTROL_POINT = uuid16(0x2A55);
    private static final UUID MANUFACTURER_NAME = uuid16(0x2A29);
    private static final UUID MODEL_NUMBER = uuid16(0x2A24);
    private static final UUID FIRMWARE_REVISION = uuid16(0x2A26);
    private static final UUID CCCD = uuid16(0x2902);
    private static final long SERVICE_REGISTRATION_TIMEOUT_MILLIS = 3_000L;
    private static final long STARTUP_RETRY_DELAY_MILLIS = 5_000L;
    private static final int SC_CP_SET_CUMULATIVE_VALUE = 0x01;
    private static final int SC_CP_RESPONSE_CODE = 0x10;
    private static final int SC_CP_SUCCESS = 0x01;
    private static final int SC_CP_OPCODE_NOT_SUPPORTED = 0x02;
    private static final int SC_CP_INVALID_PARAMETER = 0x03;
    private static final int SC_CP_CCCD_IMPROPERLY_CONFIGURED = 0x81;

    private final Context context;
    private final StatusListener statusListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<BluetoothDevice> powerSubscribers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<BluetoothDevice> cadenceSubscribers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<BluetoothDevice> controlPointSubscribers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final CadenceTracker cadenceTracker = new CadenceTracker();
    private final WheelTracker wheelTracker = new WheelTracker();
    private BluetoothAdapter adapter;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic measurement;
    private BluetoothGattCharacteristic cadenceMeasurement;
    private BluetoothGattCharacteristic cadenceControlPoint;
    private BluetoothGattService pendingCadenceService;
    private BluetoothGattService pendingDeviceInformationService;
    private BluetoothLeAdvertiser advertiser;
    private boolean advertising;
    private boolean starting;
    private AdvertiseSettings advertiseSettings;
    private AdvertiseData advertiseData;
    private AdvertiseData scanResponse;
    private UUID registeringService;
    private volatile boolean shouldRun;

    public CyclingPowerGattServer(Context context, StatusListener statusListener) {
        this.context = context.getApplicationContext();
        this.statusListener = statusListener;
    }

    public String start() {
        shouldRun = true;
        handler.removeCallbacks(startupRetry);
        return startAttempt();
    }

    private String startAttempt() {
        if (!hasBluetoothPermission()) return "Bluetooth permission missing";
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null) return "Bluetooth unavailable";
        adapter = manager.getAdapter();
        if (!adapter.isEnabled()) return "Bluetooth is off";
        if (!adapter.isMultipleAdvertisementSupported()) {
            return "BLE peripheral advertising unsupported";
        }

        stopTransport();
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
        location.setValue(new byte[] {0x0D}); // Rear hub.

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
        cadenceLocation.setValue(new byte[] {0x0D}); // Rear hub.
        cadenceControlPoint = new BluetoothGattCharacteristic(
                SC_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        cadenceControlPoint.addDescriptor(new BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        pendingCadenceService.addCharacteristic(cadenceMeasurement);
        pendingCadenceService.addCharacteristic(cadenceFeature);
        pendingCadenceService.addCharacteristic(cadenceLocation);
        pendingCadenceService.addCharacteristic(cadenceControlPoint);

        pendingDeviceInformationService = new BluetoothGattService(
                DEVICE_INFORMATION, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        pendingDeviceInformationService.addCharacteristic(readOnlyString(
                MANUFACTURER_NAME, "PeloPower"));
        pendingDeviceInformationService.addCharacteristic(readOnlyString(
                MODEL_NUMBER, "Peloton BLE Bridge"));
        pendingDeviceInformationService.addCharacteristic(readOnlyString(
                FIRMWARE_REVISION, BuildConfig.VERSION_NAME));

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
                // Garmin may use a passive scan while adding sensors. Keep the local name in the
                // primary packet so discovery does not depend on receiving a scan response.
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .build();
        scanResponse = new AdvertiseData.Builder()
                .addManufacturerData(0xFFFF, new byte[] {'P', 'e', 'l', 'o', 'P', 'o', 'w', 'e', 'r'})
                .build();

        service.addCharacteristic(measurement);
        service.addCharacteristic(feature);
        service.addCharacteristic(location);
        starting = true;
        registeringService = CPS;
        if (!gattServer.addService(service)) {
            failStartup("Could not register Cycling Power Service");
            return "Could not register Cycling Power Service; retry scheduled";
        }
        scheduleRegistrationTimeout();
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
        shouldRun = false;
        handler.removeCallbacks(startupRetry);
        stopTransport();
    }

    private void stopTransport() {
        handler.removeCallbacks(serviceRegistrationTimeout);
        if (hasBluetoothPermission()) {
            if (advertiser != null) {
                try {
                    advertiser.stopAdvertising(advertiseCallback);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not stop BLE advertising cleanly", e);
                }
            }
            if (gattServer != null) gattServer.close();
        }
        powerSubscribers.clear();
        cadenceSubscribers.clear();
        controlPointSubscribers.clear();
        advertiser = null;
        gattServer = null;
        measurement = null;
        cadenceMeasurement = null;
        cadenceControlPoint = null;
        pendingCadenceService = null;
        pendingDeviceInformationService = null;
        advertising = false;
        starting = false;
        advertiseSettings = null;
        advertiseData = null;
        scanResponse = null;
        registeringService = null;
    }

    public boolean isReady() {
        return (advertising || starting) && gattServer != null;
    }

    public int subscriberCount() {
        java.util.HashSet<BluetoothDevice> all = new java.util.HashSet<>(powerSubscribers);
        all.addAll(cadenceSubscribers);
        all.addAll(controlPointSubscribers);
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
        registeringService = null;
        handler.removeCallbacks(serviceRegistrationTimeout);
        try {
            advertiser.startAdvertising(
                    advertiseSettings, advertiseData, scanResponse, advertiseCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "BLE advertising permission denied", e);
            failStartup("Advertising permission denied");
        } catch (RuntimeException e) {
            Log.e(TAG, "BLE advertising startup failed", e);
            failStartup("Advertising startup failed: " + e.getClass().getSimpleName());
        }
    }

    private void scheduleRegistrationTimeout() {
        handler.removeCallbacks(serviceRegistrationTimeout);
        handler.postDelayed(
                serviceRegistrationTimeout, SERVICE_REGISTRATION_TIMEOUT_MILLIS);
    }

    private void failStartup(String reason) {
        handler.post(() -> {
            if (!shouldRun) return;
            Log.e(TAG, reason);
            stopTransport();
            statusListener.onBleStatus(reason + "; retrying in 5 seconds");
            handler.removeCallbacks(startupRetry);
            handler.postDelayed(startupRetry, STARTUP_RETRY_DELAY_MILLIS);
        });
    }

    private final Runnable serviceRegistrationTimeout = () -> {
        UUID timedOutService = registeringService;
        if (!shouldRun || !starting || timedOutService == null) return;
        failStartup("GATT registration timed out for " + serviceName(timedOutService));
    };

    private final Runnable startupRetry = this::retryStartup;

    private void retryStartup() {
        if (!shouldRun) return;
        String retryStatus = startAttempt();
        statusListener.onBleStatus(retryStatus);
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            handler.post(() -> {
                if (!shouldRun) {
                    if (advertiser != null && hasBluetoothPermission()) {
                        advertiser.stopAdvertising(this);
                    }
                    return;
                }
                starting = false;
                advertising = true;
                Log.i(TAG, "Cycling Power Service is advertising");
                statusListener.onBleStatus("Advertising CPS + CSCS");
            });
        }

        @Override
        public void onStartFailure(int errorCode) {
            failStartup("Advertising failed: " + advertiseError(errorCode));
        }
    };

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            handler.post(() -> {
                if (!shouldRun || gattServer == null || service == null) return;
                UUID serviceUuid = service.getUuid();
                if (!serviceUuid.equals(registeringService)) {
                    Log.w(TAG, "Ignoring unexpected GATT registration callback for " + serviceUuid);
                    return;
                }
                handler.removeCallbacks(serviceRegistrationTimeout);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failStartup("GATT registration failed for " + serviceName(serviceUuid)
                            + ": " + status);
                    return;
                }
                if (CPS.equals(serviceUuid)
                        && pendingCadenceService != null
                        && gattServer != null) {
                    registeringService = CSCS;
                    if (!gattServer.addService(pendingCadenceService)) {
                        failStartup("Could not register Cycling Speed and Cadence Service");
                        return;
                    }
                    scheduleRegistrationTimeout();
                } else if (CSCS.equals(serviceUuid)
                        && pendingDeviceInformationService != null
                        && gattServer != null) {
                    registeringService = DEVICE_INFORMATION;
                    if (!gattServer.addService(pendingDeviceInformationService)) {
                        failStartup("Could not register Device Information Service");
                        return;
                    }
                    scheduleRegistrationTimeout();
                } else if (DEVICE_INFORMATION.equals(serviceUuid)) {
                    beginAdvertising();
                }
            });
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                powerSubscribers.remove(device);
                cadenceSubscribers.remove(device);
                controlPointSubscribers.remove(device);
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
            byte[] value;
            if (!target.contains(device)) {
                value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            } else if (isControlPointDescriptor(descriptor)) {
                value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            } else {
                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            }
            if (offset > value.length) {
                gattServer.sendResponse(
                        device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            } else {
                gattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        java.util.Arrays.copyOfRange(value, offset, value.length));
            }
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
            byte[] enableValue = isControlPointDescriptor(descriptor)
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            int status = BluetoothGatt.GATT_SUCCESS;
            if (preparedWrite || offset != 0) {
                status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            } else if (java.util.Arrays.equals(value, enableValue)) {
                target.add(device);
            } else if (java.util.Arrays.equals(
                    value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                target.remove(device);
            } else {
                status = BluetoothGatt.GATT_FAILURE;
            }
            if (responseNeeded && hasBluetoothPermission() && gattServer != null) {
                gattServer.sendResponse(device, requestId, status, offset, value);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattCharacteristic characteristic,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value) {
            if (!hasBluetoothPermission() || gattServer == null) return;
            if (!SC_CONTROL_POINT.equals(characteristic.getUuid())) {
                if (responseNeeded) {
                    gattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset,
                            null);
                }
                return;
            }
            if (!controlPointSubscribers.contains(device)) {
                if (responseNeeded) {
                    gattServer.sendResponse(
                            device,
                            requestId,
                            SC_CP_CCCD_IMPROPERLY_CONFIGURED,
                            offset,
                            null);
                }
                return;
            }
            if (preparedWrite || offset != 0 || value == null || value.length == 0) {
                if (responseNeeded) {
                    gattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                            offset,
                            null);
                }
                return;
            }

            int opcode = value[0] & 0xff;
            int result = SC_CP_OPCODE_NOT_SUPPORTED;
            if (opcode == SC_CP_SET_CUMULATIVE_VALUE) {
                if (value.length == 5) {
                    long requestedRevolutions = Integer.toUnsignedLong(
                            ByteBuffer.wrap(value, 1, 4)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .getInt());
                    wheelTracker.setCumulativeRevolutions(requestedRevolutions);
                    result = SC_CP_SUCCESS;
                } else {
                    result = SC_CP_INVALID_PARAMETER;
                }
            }
            if (responseNeeded) {
                gattServer.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
            indicateControlPointResult(device, opcode, result);
        }
    };

    private void indicateControlPointResult(BluetoothDevice device, int requestOpcode, int result) {
        if (gattServer == null || cadenceControlPoint == null || !hasBluetoothPermission()) return;
        byte[] response = new byte[] {
                (byte) SC_CP_RESPONSE_CODE, (byte) requestOpcode, (byte) result
        };
        if (Build.VERSION.SDK_INT >= 33) {
            gattServer.notifyCharacteristicChanged(device, cadenceControlPoint, true, response);
        } else {
            cadenceControlPoint.setValue(response);
            gattServer.notifyCharacteristicChanged(device, cadenceControlPoint, true);
        }
    }

    private Set<BluetoothDevice> subscribersFor(BluetoothGattDescriptor descriptor) {
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        if (characteristic == null) return powerSubscribers;
        if (CSC_MEASUREMENT.equals(characteristic.getUuid())) return cadenceSubscribers;
        if (SC_CONTROL_POINT.equals(characteristic.getUuid())) return controlPointSubscribers;
        return powerSubscribers;
    }

    private static boolean isControlPointDescriptor(BluetoothGattDescriptor descriptor) {
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        return characteristic != null && SC_CONTROL_POINT.equals(characteristic.getUuid());
    }

    private static UUID uuid16(int value) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", value));
    }

    private static BluetoothGattCharacteristic readOnlyString(UUID uuid, String value) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        characteristic.setValue(value.getBytes(StandardCharsets.UTF_8));
        return characteristic;
    }

    private static String serviceName(UUID uuid) {
        if (CPS.equals(uuid)) return "Cycling Power Service";
        if (CSCS.equals(uuid)) return "Cycling Speed and Cadence Service";
        if (DEVICE_INFORMATION.equals(uuid)) return "Device Information Service";
        return String.valueOf(uuid);
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
