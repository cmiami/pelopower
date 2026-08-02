package dev.pelopower.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible, one-shot entry point used by the sideload script to clear Android's stopped-package
 * state and start the bridge. It has no launcher icon and draws no window.
 */
public final class BootstrapActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent service = new Intent(this, BridgeService.class)
                .setAction(BridgeService.ACTION_START);
        startForegroundService(service);
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Intent service = new Intent(this, BridgeService.class)
                .setAction(BridgeService.ACTION_START);
        startForegroundService(service);
        finish();
    }
}
