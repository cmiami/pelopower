package dev.pelopower.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

public final class BridgeService extends Service implements PowerSource.Listener {
    public static final String ACTION_START = "dev.pelopower.bridge.START";
    public static final String ACTION_STOP = "dev.pelopower.bridge.STOP";
    public static final String ACTION_TEST_WATTS = "dev.pelopower.bridge.TEST_WATTS";
    public static final String EXTRA_WATTS = "watts";
    private static final String TAG = "PeloPower";
    private static final int NOTIFICATION_ID = 4108;
    private static final String CHANNEL_ID = "pelopower_bridge";

    private static volatile String status = "Stopped";
    private static volatile int lastWatts;
    private static volatile int lastCadence;
    private static volatile int lastSpeedCentiKph;
    private static volatile String bleState = "Stopped";
    private static volatile WorkoutGate.State workoutState = WorkoutGate.State.WAITING;

    private final WorkoutGate gate = new WorkoutGate();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerSource source;
    private CyclingPowerGattServer ble;
    private long lastSampleAt;
    private long lastMetricsLoggedAt;

    public static String statusLine() {
        return status + "\nAndroid BLE: " + bleState
                + "\nWorkout: " + workoutState + "\nPower: " + lastWatts
                + " W\nCadence: " + lastCadence + " rpm\nSpeed: "
                + String.format(java.util.Locale.US, "%.2f km/h", lastSpeedCentiKph / 100.0);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("Starting"));
        ble = new CyclingPowerGattServer(this, newState -> {
            bleState = newState;
            setStatus(newState + "; source=" + (source == null ? "starting" : source.name()));
        });
        workoutState = gate.state();
        ensureBle();
        source = PelotonPowerSource.forDevice(this);
        source.start(this);
        IntentFilter bluetoothFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothStateReceiver, bluetoothFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bluetoothStateReceiver, bluetoothFilter);
        }
        handler.postDelayed(staleDataCheck, 1_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && !ble.isReady()) ensureBle();
        if (BuildConfig.DEBUG && ACTION_TEST_WATTS.equals(action)) {
            int watts = intent.getIntExtra(EXTRA_WATTS, 0);
            // Three samples make the movement gate enter ACTIVE immediately.
            int cadence = watts > 0 ? 90 : 0;
            int speed = watts > 0 ? 3_000 : 0;
            onSample(new PowerSample(watts, cadence, speed, SystemClock.elapsedRealtime()));
            onSample(new PowerSample(watts, cadence, speed, SystemClock.elapsedRealtime() + 400));
            onSample(new PowerSample(watts, cadence, speed, SystemClock.elapsedRealtime() + 800));
        }
        return START_STICKY;
    }

    @Override
    public void onSample(PowerSample sample) {
        lastSampleAt = sample.monotonicMillis();
        WorkoutGate.State previous = workoutState;
        workoutState = gate.accept(sample);
        lastWatts = sample.watts();
        lastCadence = sample.cadenceRpm();
        lastSpeedCentiKph = sample.speedCentiKph();
        if (sample.monotonicMillis() - lastMetricsLoggedAt >= 3_000L) {
            lastMetricsLoggedAt = sample.monotonicMillis();
            Log.i(TAG, "Metrics power=" + sample.watts()
                    + "W cadence=" + sample.cadenceRpm()
                    + "rpm speed=" + String.format(
                            java.util.Locale.US, "%.2f", sample.speedCentiKph() / 100.0)
                    + "km/h clients=" + ble.subscriberCount());
        }
        if (workoutState == WorkoutGate.State.ACTIVE) {
            if (previous == WorkoutGate.State.WAITING) {
                ble.beginWorkout(sample.monotonicMillis());
            }
            ble.publish(sample);
        }
        if (previous == WorkoutGate.State.ACTIVE && workoutState == WorkoutGate.State.WAITING) {
            ble.publish(new PowerSample(0, 0, 0, sample.monotonicMillis()));
        }
        setStatus("Bridge ready; source=" + source.name() + "; clients=" + ble.subscriberCount());
    }

    @Override
    public void onStatus(String newStatus) {
        setStatus(newStatus);
    }

    private void setStatus(String newStatus) {
        if (newStatus.equals(status)) return;
        status = newStatus;
        Log.i(TAG, newStatus);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(newStatus));
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(staleDataCheck);
        unregisterReceiver(bluetoothStateReceiver);
        if (source != null) source.stop();
        if (ble != null) ble.stop();
        status = "Stopped";
        super.onDestroy();
    }

    private void ensureBle() {
        String bleStatus = ble.start();
        setStatus(bleStatus);
    }

    private String sourceName() {
        return source == null ? "starting" : source.name();
    }

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                ensureBle();
            } else if (state == BluetoothAdapter.STATE_OFF) {
                ble.stop();
                bleState = "Bluetooth is off";
                setStatus("Waiting for Android Bluetooth; source="
                        + sourceName());
            }
        }
    };

    private final Runnable staleDataCheck = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            if (workoutState == WorkoutGate.State.ACTIVE && lastSampleAt > 0
                    && now - lastSampleAt >= 30_000L) {
                onSample(new PowerSample(0, now));
            }
            handler.postDelayed(this, 1_000L);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "PeloPower bridge", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification notification(String message) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(dev.pelopower.bridge.R.drawable.ic_power)
                .setContentTitle("PeloPower")
                .setContentText(message)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
