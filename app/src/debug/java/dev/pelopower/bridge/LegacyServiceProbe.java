package dev.pelopower.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Build;

public final class LegacyServiceProbe {
    public interface Callback { void complete(String result); }

    private static final String PACKAGE = "com.onepeloton.affernetservice";
    private static final String SERVICE = "com.onepeloton.affernetservice.AffernetService";
    private static final String ACTION = "com.onepeloton.affernetservice.IBikeInterface";

    private LegacyServiceProbe() {}

    public static void run(Context context, Callback callback) {
        String version;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE, 0);
            long versionCode = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode()
                    : info.versionCode;
            version = info.versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            callback.complete("Legacy sensor package is not installed: " + PACKAGE);
            return;
        }

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                String descriptor;
                try {
                    descriptor = binder.getInterfaceDescriptor();
                } catch (Exception e) {
                    descriptor = "descriptor error: " + e;
                }
                callback.complete("BOUND\nPackage: " + version + "\nComponent: " + name
                        + "\nDescriptor: " + descriptor);
                context.unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                callback.complete("Disconnected: " + name);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                callback.complete("Service returned a null Binder: " + name);
                context.unbindService(this);
            }
        };

        Intent intent = new Intent(SERVICE)
                .setAction(ACTION)
                .setPackage(PACKAGE);
        try {
            boolean requested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!requested) callback.complete("Package exists but bindService returned false. Version: " + version);
        } catch (SecurityException e) {
            callback.complete("BIND DENIED\nPackage: " + version + "\n" + e);
        }
    }
}
