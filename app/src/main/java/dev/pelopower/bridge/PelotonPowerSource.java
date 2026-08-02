package dev.pelopower.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Build;

/**
 * Read-only adapter for the Bike+/G700 Affernet sensor service.
 *
 * <p>The service returns a nullable BikeData parcel from transaction 14. Cadence and power are the
 * first two parcel fields, so the bridge intentionally does not depend on the rest of Peloton's
 * private parcelable. Any unexpected layout is rejected by conservative metric bounds.</p>
 */
public final class PelotonPowerSource implements PowerSource {
    private static final String PACKAGE = "com.onepeloton.affernetservice";
    private static final String SERVICE = "com.onepeloton.affernetservice.AffernetService";
    private static final String DESCRIPTOR = "com.onepeloton.affernetservice.IBikeInterface";
    private static final int READ_TRANSACTION = 14;
    private static final long POLL_INTERVAL_MILLIS = 200L;
    private static final long REBIND_DELAY_MILLIS = 2_000L;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread pollThread;
    private Handler pollHandler;
    private Listener listener;
    private IBinder binder;
    private boolean started;
    private boolean bindRequested;
    private int consecutiveErrors;

    public PelotonPowerSource(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Selects the Bike+/Cross Trainer polling contract or the original Bike callback contract. */
    public static PowerSource forDevice(Context context) {
        return usesBikePlusContract(Build.MODEL)
                ? new PelotonPowerSource(context)
                : new PelotonV1PowerSource(context);
    }

    static boolean usesBikePlusContract(String model) {
        String normalized = model == null ? "" : model.toUpperCase(java.util.Locale.US);
        return normalized.contains("G700")
                || normalized.startsWith("PLTN-ATR")
                || normalized.contains("PLTN-T");
    }

    @Override
    public void start(Listener newListener) {
        if (started) return;
        started = true;
        listener = newListener;
        pollThread = new HandlerThread("PeloPower-sensor");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        mainHandler.post(this::bind);
    }

    @Override
    public void stop() {
        started = false;
        mainHandler.removeCallbacks(rebind);
        if (pollHandler != null) pollHandler.removeCallbacks(poll);
        unlinkDeathRecipient();
        binder = null;
        if (bindRequested) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // The framework may already have discarded a dead binding.
            }
            bindRequested = false;
        }
        if (pollThread != null) pollThread.quitSafely();
        pollThread = null;
        pollHandler = null;
        listener = null;
    }

    @Override
    public String name() {
        return "peloton-affernet-v2";
    }

    private void bind() {
        if (!started || bindRequested) return;
        Intent intent = new Intent(SERVICE).setAction(DESCRIPTOR).setPackage(PACKAGE);
        reportStatus("Connecting to Peloton sensor service " + packageVersion());
        try {
            bindRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bindRequested) {
                reportStatus("Peloton sensor bind returned false " + packageVersion());
                scheduleRebind();
            }
        } catch (SecurityException e) {
            reportStatus("Peloton sensor permission denied " + packageVersion() + ": "
                    + e.getMessage());
            scheduleRebind();
        } catch (RuntimeException e) {
            reportStatus("Peloton sensor bind failed " + packageVersion() + ": " + e);
            scheduleRebind();
        }
    }

    private void connected(IBinder newBinder) {
        if (!started) return;
        unlinkDeathRecipient();
        binder = newBinder;
        consecutiveErrors = 0;
        try {
            newBinder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            recoverBinding("Peloton sensor Binder was already dead");
            return;
        }
        reportStatus("Peloton sensor connected " + packageVersion());
        if (pollHandler != null) {
            pollHandler.removeCallbacks(poll);
            pollHandler.post(poll);
        }
    }

    private void recoverBinding(String reason) {
        if (!started) return;
        reportStatus(reason + "; reconnecting");
        if (pollHandler != null) pollHandler.removeCallbacks(poll);
        unlinkDeathRecipient();
        binder = null;
        mainHandler.post(() -> {
            if (bindRequested) {
                try {
                    context.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                    // Already unbound by Android.
                }
                bindRequested = false;
            }
            scheduleRebind();
        });
    }

    private void scheduleRebind() {
        if (!started) return;
        mainHandler.removeCallbacks(rebind);
        mainHandler.postDelayed(rebind, REBIND_DELAY_MILLIS);
    }

    private void unlinkDeathRecipient() {
        IBinder current = binder;
        if (current == null) return;
        try {
            current.unlinkToDeath(deathRecipient, 0);
        } catch (java.util.NoSuchElementException ignored) {
            // No live death recipient remained.
        }
    }

    private void readSample() {
        IBinder current = binder;
        if (!started || current == null) return;
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            if (!current.transact(READ_TRANSACTION, request, reply, 0)) {
                throw new RemoteException("transaction " + READ_TRANSACTION + " was not handled");
            }
            reply.readException();
            int present = reply.readInt();
            if (present == 0) throw new IllegalStateException("BikeData was null");
            long cadenceRpm = reply.readLong();
            long powerCentiwatts = reply.readLong();
            PowerSample sample = PelotonMetricConverter.fromRaw(
                    cadenceRpm, powerCentiwatts, SystemClock.elapsedRealtime());
            consecutiveErrors = 0;
            deliver(sample);
        } catch (RuntimeException | RemoteException e) {
            consecutiveErrors++;
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                recoverBinding("Peloton sensor read failed " + MAX_CONSECUTIVE_ERRORS
                        + " times: " + e);
                return;
            }
        } finally {
            request.recycle();
            reply.recycle();
        }
        if (started && pollHandler != null && binder != null) {
            pollHandler.postDelayed(poll, POLL_INTERVAL_MILLIS);
        }
    }

    private void deliver(PowerSample sample) {
        mainHandler.post(() -> {
            Listener current = listener;
            if (started && current != null) current.onSample(sample);
        });
    }

    private void reportStatus(String status) {
        mainHandler.post(() -> {
            Listener current = listener;
            if (started && current != null) current.onStatus(status);
        });
    }

    private String packageVersion() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE, 0);
            long versionCode = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode()
                    : info.versionCode;
            return "v" + info.versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "(" + PACKAGE + " not installed)";
        }
    }

    private final Runnable poll = this::readSample;
    private final Runnable rebind = this::bind;
    private final IBinder.DeathRecipient deathRecipient =
            () -> recoverBinding("Peloton sensor Binder died");

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            connected(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recoverBinding("Peloton sensor service disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            recoverBinding("Peloton sensor binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            recoverBinding("Peloton sensor returned a null Binder");
        }
    };
}
