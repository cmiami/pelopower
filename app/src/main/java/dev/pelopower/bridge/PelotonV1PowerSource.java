package dev.pelopower.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

/** Callback-based metric source used by the original Peloton Bike. */
public final class PelotonV1PowerSource implements PowerSource {
    private static final String PACKAGE = "com.onepeloton.affernetservice";
    private static final String SERVICE = "com.onepeloton.affernetservice.AffernetService";
    private static final String DESCRIPTOR = "com.onepeloton.affernetservice.IV1Interface";
    private static final String CALLBACK_DESCRIPTOR = "com.onepeloton.affernetservice.IV1Callback";
    private static final long REBIND_DELAY_MILLIS = 2_000L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private IBinder binder;
    private boolean started;
    private boolean bindRequested;
    private boolean registered;

    public PelotonV1PowerSource(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void start(Listener newListener) {
        if (started) return;
        started = true;
        listener = newListener;
        mainHandler.post(this::bind);
    }

    @Override
    public void stop() {
        started = false;
        mainHandler.removeCallbacks(rebind);
        unregisterCallback();
        unlinkDeathRecipient();
        binder = null;
        if (bindRequested) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Already unbound by Android.
            }
            bindRequested = false;
        }
        listener = null;
    }

    @Override
    public String name() {
        return "peloton-original-bike-v1";
    }

    private void bind() {
        if (!started || bindRequested) return;
        reportStatus("Connecting to original Peloton Bike sensor service");
        try {
            Intent intent = new Intent(SERVICE).setAction(DESCRIPTOR).setPackage(PACKAGE);
            bindRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bindRequested) scheduleRebind();
        } catch (RuntimeException e) {
            reportStatus("Original Bike sensor bind failed: " + e);
            scheduleRebind();
        }
    }

    private void connected(IBinder newBinder) {
        binder = newBinder;
        try {
            newBinder.linkToDeath(deathRecipient, 0);
            transactCallback(1);
            registered = true;
            reportStatus("Original Peloton Bike sensor connected");
        } catch (RemoteException | RuntimeException e) {
            recover("Original Bike callback registration failed: " + e);
        }
    }

    private void transactCallback(int code) throws RemoteException {
        IBinder current = binder;
        if (current == null) throw new RemoteException("sensor Binder unavailable");
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeStrongBinder(callback);
            request.writeString("PeloPower");
            if (!current.transact(code, request, reply, 0)) {
                throw new RemoteException("callback transaction " + code + " was not handled");
            }
            reply.readException();
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    private void unregisterCallback() {
        if (!registered) return;
        try {
            transactCallback(2);
        } catch (RemoteException | RuntimeException ignored) {
            // A dead service has already discarded the callback.
        }
        registered = false;
    }

    private void recover(String reason) {
        if (!started) return;
        reportStatus(reason + "; reconnecting");
        registered = false;
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

    private void reportStatus(String status) {
        mainHandler.post(() -> {
            Listener current = listener;
            if (started && current != null) current.onStatus(status);
        });
    }

    private void deliver(long cadenceRpm, long powerCentiwatts) {
        try {
            PowerSample sample = PelotonMetricConverter.fromRaw(
                    cadenceRpm, powerCentiwatts, SystemClock.elapsedRealtime());
            mainHandler.post(() -> {
                Listener current = listener;
                if (started && current != null) current.onSample(sample);
            });
        } catch (IllegalArgumentException e) {
            reportStatus("Original Bike returned unexpected metrics: " + e.getMessage());
        }
    }

    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == 1) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                if (data.readInt() != 0) deliver(data.readLong(), data.readLong());
                if (reply != null) reply.writeNoException();
                return true;
            }
            if (code == 2) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                long error = data.readLong();
                reportStatus("Original Bike sensor error: " + error);
                if (reply != null) reply.writeNoException();
                return true;
            }
            if (code == 3) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                data.readInt();
                data.readInt();
                data.readLong();
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private final Runnable rebind = this::bind;
    private final IBinder.DeathRecipient deathRecipient =
            () -> recover("Original Bike sensor Binder died");

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            connected(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recover("Original Bike sensor service disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            recover("Original Bike sensor binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            recover("Original Bike sensor returned a null Binder");
        }
    };
}
