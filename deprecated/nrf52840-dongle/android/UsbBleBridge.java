package dev.pelopower.bridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

/** Sends cycling measurements to the PeloPower nRF52840 firmware over USB CDC ACM. */
public final class UsbBleBridge {
    public interface StatusListener {
        void onUsbStatus(String status);
    }

    // Private prototype identity used by deprecated/nrf52840-dongle/prj.conf.
    public static final int USB_VENDOR_ID = 0x1915;
    public static final int USB_PRODUCT_ID = 0x521f;
    private static final String ACTION_USB_PERMISSION =
            "dev.pelopower.bridge.USB_PERMISSION";
    private static final String TAG = "PeloPowerUSB";
    private static final long RETRY_MILLIS = 2_000L;

    private final Context context;
    private final UsbManager manager;
    private final StatusListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WheelTracker wheelTracker = new WheelTracker();
    private final CadenceTracker cadenceTracker = new CadenceTracker();
    private HandlerThread ioThread;
    private Handler ioHandler;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface controlInterface;
    private UsbInterface dataInterface;
    private UsbEndpoint outputEndpoint;
    private volatile byte[] latestPacket;
    private volatile String state = "USB BLE not connected";
    private boolean started;
    private int permissionRequestedDeviceId = -1;
    private int sequence;

    public UsbBleBridge(Context context, StatusListener listener) {
        this.context = context.getApplicationContext();
        this.manager = context.getSystemService(UsbManager.class);
        this.listener = listener;
    }

    public void start() {
        if (started) {
            scheduleConnect(0);
            return;
        }
        started = true;
        ioThread = new HandlerThread("PeloPower-usb");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        // Exported is required here because USB attach events originate from the system.
        ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        scheduleConnect(0);
    }

    public synchronized void publish(PowerSample sample) {
        WheelTracker.State wheel = wheelTracker.accept(
                sample.speedCentiKph(), sample.monotonicMillis());
        CadenceTracker.State cadence = cadenceTracker.accept(
                sample.cadenceRpm(), sample.monotonicMillis());
        byte[] packet = UsbMetricPacket.encode(sample, wheel, cadence, sequence++);
        latestPacket = packet;
        Handler handler = ioHandler;
        if (handler != null) handler.post(() -> write(packet));
    }

    public void stop() {
        if (!started) return;
        started = false;
        context.unregisterReceiver(receiver);
        Handler handler = ioHandler;
        if (handler != null) handler.post(this::closeConnection);
        if (ioThread != null) ioThread.quitSafely();
        ioThread = null;
        ioHandler = null;
        report("USB BLE stopped");
    }

    public String state() {
        return state;
    }

    public boolean isReady() {
        return connection != null && outputEndpoint != null;
    }

    private void scheduleConnect(long delayMillis) {
        Handler handler = ioHandler;
        if (started && handler != null) {
            handler.removeCallbacks(connectRunnable);
            handler.postDelayed(connectRunnable, delayMillis);
        }
    }

    private void connect() {
        if (!started || connection != null) return;
        if (manager == null) {
            report("USB host service unavailable");
            return;
        }
        UsbDevice candidate = findDongle();
        if (candidate == null) {
            report("USB BLE dongle not connected");
            return;
        }
        if (!manager.hasPermission(candidate)) {
            report("USB BLE permission required");
            if (permissionRequestedDeviceId != candidate.getDeviceId()) {
                permissionRequestedDeviceId = candidate.getDeviceId();
                Intent intent = new Intent(ACTION_USB_PERMISSION)
                        .setPackage(context.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        context, candidate.getDeviceId(), intent, flags);
                manager.requestPermission(candidate, pendingIntent);
            }
            return;
        }

        UsbInterface control = null;
        UsbInterface data = null;
        UsbEndpoint output = null;
        for (int i = 0; i < candidate.getInterfaceCount(); i++) {
            UsbInterface usbInterface = candidate.getInterface(i);
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) {
                control = usbInterface;
            } else if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                data = usbInterface;
                for (int e = 0; e < usbInterface.getEndpointCount(); e++) {
                    UsbEndpoint endpoint = usbInterface.getEndpoint(e);
                    if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                            && endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                        output = endpoint;
                    }
                }
            }
        }
        if (control == null || data == null || output == null) {
            report("USB BLE firmware has no CDC data interface");
            return;
        }

        UsbDeviceConnection opened = manager.openDevice(candidate);
        if (opened == null) {
            report("Could not open USB BLE dongle");
            scheduleConnect(RETRY_MILLIS);
            return;
        }
        if (!opened.claimInterface(control, true) || !opened.claimInterface(data, true)) {
            opened.close();
            report("Could not claim USB BLE interfaces");
            scheduleConnect(RETRY_MILLIS);
            return;
        }

        // Standard CDC ACM: 115200 8N1 and DTR/RTS asserted.
        byte[] lineCoding = new byte[] {0x00, (byte) 0xc2, 0x01, 0x00, 0, 0, 8};
        opened.controlTransfer(0x21, 0x20, 0, control.getId(),
                lineCoding, lineCoding.length, 1_000);
        opened.controlTransfer(0x21, 0x22, 0x03, control.getId(),
                null, 0, 1_000);

        device = candidate;
        connection = opened;
        controlInterface = control;
        dataInterface = data;
        outputEndpoint = output;
        permissionRequestedDeviceId = -1;
        report("USB BLE radio ready");
        byte[] packet = latestPacket;
        if (packet != null) write(packet);
    }

    private UsbDevice findDongle() {
        for (UsbDevice candidate : manager.getDeviceList().values()) {
            if (candidate.getVendorId() == USB_VENDOR_ID
                    && candidate.getProductId() == USB_PRODUCT_ID) {
                return candidate;
            }
        }
        return null;
    }

    private void write(byte[] packet) {
        UsbDeviceConnection current = connection;
        UsbEndpoint endpoint = outputEndpoint;
        if (current == null || endpoint == null) return;
        int written = current.bulkTransfer(endpoint, packet, packet.length, 250);
        if (written != packet.length) {
            Log.w(TAG, "USB write failed: " + written + "/" + packet.length);
            closeConnection();
            report("USB BLE disconnected; retrying");
            scheduleConnect(RETRY_MILLIS);
        }
    }

    private void closeConnection() {
        UsbDeviceConnection current = connection;
        connection = null;
        outputEndpoint = null;
        device = null;
        if (current != null) {
            if (dataInterface != null) current.releaseInterface(dataInterface);
            if (controlInterface != null) current.releaseInterface(controlInterface);
            current.close();
        }
        dataInterface = null;
        controlInterface = null;
    }

    private void report(String newState) {
        if (newState.equals(state)) return;
        state = newState;
        Log.i(TAG, newState);
        mainHandler.post(() -> listener.onUsbStatus(newState));
    }

    private final Runnable connectRunnable = this::connect;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            String action = intent.getAction();
            UsbDevice changed = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    permissionRequestedDeviceId = -1;
                    scheduleConnect(0);
                } else {
                    report("USB BLE permission denied; reconnect dongle to retry");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                scheduleConnect(0);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)
                    && changed != null && device != null
                    && changed.getDeviceId() == device.getDeviceId()) {
                Handler handler = ioHandler;
                if (handler != null) handler.post(() -> {
                    closeConnection();
                    report("USB BLE dongle removed");
                });
            }
        }
    };
}
