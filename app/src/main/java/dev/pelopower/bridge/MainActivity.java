package dev.pelopower.bridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView probe;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        requestRuntimePermissions();
        startBridge();
        handler.post(statusRefresh);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(statusRefresh);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) startBridge();
    }

    private View buildUi() {
        int pad = dp(24);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);
        body.setBackgroundColor(Color.rgb(245, 247, 248));

        TextView title = text("PeloPower", 30, Color.rgb(16, 20, 24));
        body.addView(title);
        body.addView(text("Peloton workout metrics and BLE cycling bridge",
                16, Color.DKGRAY));

        status = text("Starting…", 18, Color.rgb(0, 105, 92));
        status.setPadding(0, dp(20), 0, dp(16));
        body.addView(status);

        body.addView(button("Start bridge", view -> startBridge()));
        body.addView(button("Stop bridge", view -> {
            startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_STOP));
        }));
        body.addView(button("Probe legacy Bike+ sensor service", view -> {
            probe.setText(R.string.probing);
            LegacyServiceProbe.run(this, result -> runOnUiThread(() -> probe.setText(result)));
        }));

        if (BuildConfig.DEBUG) {
            body.addView(button("Capture raw legacy Binder snapshot", view -> captureDebugSnapshot()));
            body.addView(button("Test 150 W", view -> injectTestWatts(150)));
            body.addView(button("Test 300 W", view -> injectTestWatts(300)));
            body.addView(button("Test 0 W", view -> injectTestWatts(0)));
        }

        probe = text(deviceDiagnostics(), 14, Color.DKGRAY);
        probe.setTextIsSelectable(true);
        probe.setPadding(0, dp(20), 0, 0);
        body.addView(probe);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        return scroll;
    }

    private void startBridge() {
        Intent intent = new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_START);
        startForegroundService(intent);
    }

    private void injectTestWatts(int watts) {
        Intent intent = new Intent(this, BridgeService.class)
                .setAction(BridgeService.ACTION_TEST_WATTS)
                .putExtra(BridgeService.EXTRA_WATTS, watts);
        startForegroundService(intent);
    }

    private void captureDebugSnapshot() {
        try {
            Class<?> type = Class.forName("dev.pelopower.bridge.RawBinderSnapshotProbe");
            Method method = type.getMethod("capture", android.content.Context.class, LegacyServiceProbe.Callback.class);
            method.invoke(null, this, (LegacyServiceProbe.Callback) result ->
                    runOnUiThread(() -> probe.setText(result)));
        } catch (Exception e) {
            probe.setText(getString(R.string.snapshot_probe_unavailable, e));
        }
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            java.util.ArrayList<String> wanted = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) wanted.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) wanted.add(Manifest.permission.POST_NOTIFICATIONS);
            if (!wanted.isEmpty()) requestPermissions(wanted.toArray(new String[0]), 100);
        }
    }

    private String deviceDiagnostics() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        boolean ble = getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        String peripheral = "unknown (permission needed)";
        try {
            if (manager != null && manager.getAdapter() != null) {
                peripheral = Boolean.toString(manager.getAdapter().isMultipleAdvertisementSupported());
            }
        } catch (SecurityException ignored) {}
        return "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + "\nBuild: " + Build.DISPLAY
                + "\nBLE feature: " + ble
                + "\nBLE peripheral advertising: " + peripheral
                + "\nApp: " + BuildConfig.VERSION_NAME;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            if (status != null) status.setText(BridgeService.statusLine());
            handler.postDelayed(this, 1000);
        }
    };
}
