package dev.pelopower.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Debug-build-only raw capture for the known legacy Bike+ read transaction. */
public final class RawBinderSnapshotProbe {
    private static final String PACKAGE = "com.onepeloton.affernetservice";
    private static final String SERVICE = "com.onepeloton.affernetservice.AffernetService";
    private static final String DESCRIPTOR = "com.onepeloton.affernetservice.IBikeInterface";
    private static final int LEGACY_READ_TRANSACTION = 14;

    private RawBinderSnapshotProbe() {}

    public static void capture(Context context, LegacyServiceProbe.Callback callback) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                executor.execute(() -> {
                    Parcel request = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        request.writeInterfaceToken(DESCRIPTOR);
                        boolean handled = binder.transact(LEGACY_READ_TRANSACTION, request, reply, 0);
                        byte[] raw = reply.marshall();
                        File root = new File(context.getExternalFilesDir(null), "probes");
                        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("mkdir failed");
                        String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                                .replace(':', '-');
                        File output = new File(root, "binder-transaction-14-" + stamp + ".bin");
                        try (FileOutputStream stream = new FileOutputStream(output)) {
                            stream.write(raw);
                        }
                        callback.complete("RAW SNAPSHOT SAVED\nHandled: " + handled
                                + "\nBytes: " + raw.length + "\n" + output.getAbsolutePath());
                    } catch (Exception e) {
                        callback.complete("RAW SNAPSHOT FAILED\n" + e);
                    } finally {
                        request.recycle();
                        reply.recycle();
                        try { context.unbindService(this); } catch (Exception ignored) {}
                        executor.shutdown();
                    }
                });
            }

            @Override public void onServiceDisconnected(ComponentName name) {}
        };

        Intent intent = new Intent(SERVICE).setAction(DESCRIPTOR).setPackage(PACKAGE);
        try {
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                callback.complete("RAW SNAPSHOT FAILED\nbindService returned false");
                executor.shutdown();
            }
        } catch (Exception e) {
            callback.complete("RAW SNAPSHOT FAILED\n" + e);
            executor.shutdown();
        }
    }
}

