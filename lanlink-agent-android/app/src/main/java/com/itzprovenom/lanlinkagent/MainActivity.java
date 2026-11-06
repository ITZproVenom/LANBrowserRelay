package com.itzprovenom.lanlinkagent;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Deliberately non-navigable TV surface. Opening the app only starts the
 * headless service; all management is performed from the paired iPhone app.
 */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        TextView status = new TextView(this);
        status.setText("LANLink is running\n\nUse the LANLink app on your iPhone.\nKeep both devices on the same network.");
        status.setTextSize(22);
        status.setTextColor(0xFFFFFFFF);
        status.setGravity(android.view.Gravity.CENTER);
        status.setPadding(32, 32, 32, 32);
        status.setBackgroundColor(0xFF10131A);
        setContentView(status);

        Intent service = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
    }
}
