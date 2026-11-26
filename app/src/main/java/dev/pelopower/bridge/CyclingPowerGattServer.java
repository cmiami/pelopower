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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    private static final String ADVERTISING_NAME = "PeloPower";
    private static final long SERVICE_REGISTRATION_TIMEOUT_MILLIS = 3_000L;
    private static final long STARTUP_RETRY_DELAY_MILLIS = 5_000L;
    private static final long NAME_PROPAGATION_DELAY_MILLIS = 750L;
    private static final int MAX_NAME_CHANGE_ATTEMPTS = 3;
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
    private record Delivery(BluetoothGattCharacteristic characteristic, byte[] value, boolean confirm) {}
    // Accessed on the main looper. Keep the newest unsent measurement per characteristic.
    private static final class ClientQueue {
        final ArrayDeque<Delivery> waiting = new ArrayDeque<>();
        Delivery inFlight;
    }
    private final Map<BluetoothDevice, ClientQueue> queues = new HashMap<>();
    private final SharedPreferences subscriptions;
    private byte[] latestPower = CyclingPowerMeasurement.metrics(0,
            new WheelTracker.State(0, 0), new CadenceTracker.State(0, 0));
    private byte[] latestCadence = CyclingCadenceMeasurement.wheelAndCrankData(
            new WheelTracker.State(0, 0), new CadenceTracker.State(0, 0));
    private BluetoothAdapter adapter;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic measurement;
    private BluetoothGattCharacteristic cadenceMeasurement;
    private BluetoothGattCharacteristic cadenceControlPoint;
    private BluetoothGattService pendingCadenceService;
    private BluetoothGattService pendingDeviceInformationService;
    private BluetoothLeAdvertiser advertiser;
    private boolean powerAdvertising;
    private boolean starting;
    private AdvertiseSettings advertiseSettings;
    private AdvertiseData powerAdvertiseData;
    private AdvertiseData scanResponse;
    private UUID registeringService;
    private int nameChangeAttempts;
    private volatile boolean shouldRun;

    public CyclingPowerGattServer(Context context, StatusListener statusListener) {
        this.context = context.getApplicationContext();
        this.statusListener = statusListener;
        subscriptions = context.getSharedPreferences("bonded_cccd", Context.MODE_PRIVATE);
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
        if (!ensureAdvertisingName()) {
            return "Setting Bluetooth sensor name to " + ADVERTISING_NAME;
        }

        stopTransport();
        gattServer = manager.openGattServer(context, callback);
        if (gattServer == null) {
            failStartup("Could not open GATT server");
            return "Retrying GATT startup";
        }

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
        if (advertiser == null) {
            failStartup("BLE advertiser unavailable");
            return "Retrying advertiser startup";
        }
        advertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();
        // A passive sensor search sees only the primary packet. Include our short
        // name there when Android accepted the rename, keeping the legacy payload bounded.
        boolean includePrimaryName = ADVERTISING_NAME.equals(adapter.getName());
        powerAdvertiseData = new AdvertiseData.Builder()
                .setIncludeDeviceName(includePrimaryName)
                .addServiceUuid(new ParcelUuid(CPS))
                .setIncludeTxPowerLevel(false)
                .build();
        scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(!includePrimaryName)
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

    public void beginWorkout(long monotonicMillis) {
        // Resume counting from this ride, excluding the gap without idle measurements.
        cadenceTracker.accept(0, monotonicMillis);
        wheelTracker.accept(0, monotonicMillis);
    }

    public void publish(PowerSample sample) {
        if (gattServer == null || measurement == null || !hasBluetoothPermission()) return;
        CadenceTracker.State cadence = cadenceTracker.accept(
                sample.cadenceRpm(), sample.monotonicMillis());
        WheelTracker.State wheel = wheelTracker.accept(
                sample.speedCentiKph(), sample.monotonicMillis());
        latestPower = CyclingPowerMeasurement.metrics(sample.watts(), wheel, cadence);
        latestCadence = CyclingCadenceMeasurement.wheelAndCrankData(wheel, cadence);
        for (BluetoothDevice device : powerSubscribers) {
            enqueue(device, new Delivery(measurement, latestPower, false));
        }
        for (BluetoothDevice device : cadenceSubscribers) {
            enqueue(device, new Delivery(cadenceMeasurement, latestCadence, false));
        }
    }

    void bondChanged(BluetoothDevice device, int state) {
        if (device == null || !hasBluetoothPermission()) return;
        if (state == BluetoothDevice.BOND_NONE) {
            subscriptions.edit().remove(device.getAddress()).apply();
        } else if (state == BluetoothDevice.BOND_BONDED) {
            saveSubscriptions(device);
        }
    }

    private void saveSubscriptions(BluetoothDevice device) {
        if (!hasBluetoothPermission() || device.getBondState() != BluetoothDevice.BOND_BONDED) return;
        Set<String> enabled = new HashSet<>();
        if (powerSubscribers.contains(device)) enabled.add(MEASUREMENT.toString());
        if (cadenceSubscribers.contains(device)) enabled.add(CSC_MEASUREMENT.toString());
        if (controlPointSubscribers.contains(device)) enabled.add(SC_CONTROL_POINT.toString());
        subscriptions.edit().putStringSet(device.getAddress(), enabled).apply();
    }

    private void restoreSubscriptions(BluetoothDevice device) {
        if (!hasBluetoothPermission()) return;
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            subscriptions.edit().remove(device.getAddress()).apply();
            return;
        }
        Set<String> enabled = subscriptions.getStringSet(device.getAddress(), Collections.emptySet());
        if (enabled.contains(MEASUREMENT.toString())) powerSubscribers.add(device);
        if (enabled.contains(CSC_MEASUREMENT.toString())) cadenceSubscribers.add(device);
        if (enabled.contains(SC_CONTROL_POINT.toString())) controlPointSubscribers.add(device);
        if (powerSubscribers.contains(device)) sendSnapshot(device, measurement);
        if (cadenceSubscribers.contains(device)) sendSnapshot(device, cadenceMeasurement);
    }

    private void sendSnapshot(BluetoothDevice device, BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        if (characteristic == measurement && powerSubscribers.contains(device)) {
            enqueue(device, new Delivery(characteristic, latestPower, false));
        } else if (characteristic == cadenceMeasurement && cadenceSubscribers.contains(device)) {
            enqueue(device, new Delivery(characteristic, latestCadence, false));
        }
    }

    private void enqueue(BluetoothDevice device, Delivery delivery) {
        ClientQueue queue = queues.computeIfAbsent(device, ignored -> new ClientQueue());
        if (!delivery.confirm()) {
            queue.waiting.removeIf(old -> old.characteristic() == delivery.characteristic());
        }
        queue.waiting.addLast(delivery);
        drain(device, queue);
    }

    private void drain(BluetoothDevice device, ClientQueue queue) {
        while (queue.inFlight == null && !queue.waiting.isEmpty()) {
            Delivery next = queue.waiting.removeFirst();
            BluetoothGattCharacteristic characteristic = next.characteristic();
            if (!subscribersFor(characteristic.getDescriptor(CCCD)).contains(device)) continue;
            if (gattServer == null || !hasBluetoothPermission()) return;
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) {
                accepted = gattServer.notifyCharacteristicChanged(device, characteristic,
                        next.confirm(), next.value()) == BluetoothGatt.GATT_SUCCESS;
            } else {
                characteristic.setValue(next.value());
                accepted = gattServer.notifyCharacteristicChanged(device, characteristic, next.confirm());
            }
            if (accepted) queue.inFlight = next;
            else Log.w(TAG, "BLE send rejected for " + characteristic.getUuid());
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
                    advertiser.stopAdvertising(powerAdvertiseCallback);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not stop BLE advertising cleanly", e);
                }
            }
            if (gattServer != null) gattServer.close();
        }
        queues.clear();
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
        powerAdvertising = false;
        starting = false;
        advertiseSettings = null;
        powerAdvertiseData = null;
        scanResponse = null;
        registeringService = null;
    }

    public boolean isReady() {
        return (powerAdvertising || starting) && gattServer != null;
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

    private boolean ensureAdvertisingName() {
        String currentName;
        try {
            currentName = adapter.getName();
        } catch (SecurityException e) {
            Log.w(TAG, "Could not read Bluetooth name", e);
            return true;
        }
        if (ADVERTISING_NAME.equals(currentName)) {
            nameChangeAttempts = 0;
            return true;
        }
        if (nameChangeAttempts >= MAX_NAME_CHANGE_ATTEMPTS) {
            Log.w(TAG, "Bluetooth name remained " + currentName + "; advertising anyway");
            return true;
        }
        nameChangeAttempts++;
        try {
            if (!adapter.setName(ADVERTISING_NAME)) {
                Log.w(TAG, "Bluetooth stack rejected sensor name change");
                return true;
            }
            handler.removeCallbacks(startupRetry);
            handler.postDelayed(startupRetry, NAME_PROPAGATION_DELAY_MILLIS);
            return false;
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth sensor name change denied", e);
            return true;
        }
    }

    private void beginAdvertising() {
        if (advertiser == null || advertiseSettings == null
                || powerAdvertiseData == null
                || powerAdvertising) return;
        registeringService = null;
        handler.removeCallbacks(serviceRegistrationTimeout);
        try {
            advertiser.startAdvertising(
                    advertiseSettings,
                    powerAdvertiseData,
                    scanResponse,
                    powerAdvertiseCallback);
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

    private final AdvertiseCallback powerAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            handler.post(() -> {
                if (!shouldRun) {
                    if (advertiser != null && hasBluetoothPermission()) {
                        advertiser.stopAdvertising(this);
                    }
                    return;
                }
                powerAdvertising = true;
                Log.i(TAG, "Dedicated Cycling Power sensor is advertising");
                starting = false;
                statusListener.onBleStatus("Advertising power with crank cadence");
            });
        }

        @Override
        public void onStartFailure(int errorCode) {
            failStartup("Cycling Power advertising failed: " + advertiseError(errorCode));
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
        public void onNotificationSent(BluetoothDevice device, int status) {
            handler.post(() -> {
                ClientQueue queue = queues.get(device);
                if (queue == null || queue.inFlight == null) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "BLE delivery failed for " + queue.inFlight.characteristic().getUuid()
                            + " status=" + status);
                }
                queue.inFlight = null;
                drain(device, queue);
            });
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            handler.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    restoreSubscriptions(device);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    queues.remove(device);
                    powerSubscribers.remove(device);
                    cadenceSubscribers.remove(device);
                    controlPointSubscribers.remove(device);
                }
            });
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
            handler.post(() -> {
                Set<BluetoothDevice> target = subscribersFor(descriptor);
                byte[] enableValue = isControlPointDescriptor(descriptor)
                        ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                boolean newlyEnabled = false;
                int status = BluetoothGatt.GATT_SUCCESS;
                if (preparedWrite || offset != 0) {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                } else if (java.util.Arrays.equals(value, enableValue)) {
                    newlyEnabled = target.add(device);
                } else if (java.util.Arrays.equals(
                        value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    target.remove(device);
                } else {
                    status = BluetoothGatt.GATT_FAILURE;
                }
                BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
                String characteristicUuid = characteristic == null
                        ? "unknown" : characteristic.getUuid().toString();
                Log.i(TAG, "BLE subscription characteristic=" + characteristicUuid
                        + " enabled=" + target.contains(device) + " status=" + status);
                if (responseNeeded && hasBluetoothPermission() && gattServer != null) {
                    gattServer.sendResponse(device, requestId, status, offset, value);
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    saveSubscriptions(device);
                    if (newlyEnabled) sendSnapshot(device, characteristic);
                }
            });
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
        handler.post(() -> {
            if (cadenceControlPoint != null && controlPointSubscribers.contains(device)) {
                enqueue(device, new Delivery(cadenceControlPoint, response, true));
            }
        });
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
