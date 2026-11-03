package com.itzprovenom.lanlinkagent;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private EditText tokenInput;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, pad, pad, pad);
        page.setBackgroundColor(0xFF10131A);
        page.setFocusableInTouchMode(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("LANLink");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(title, full());

        TextView subtitle = new TextView(this);
        subtitle.setText("TV network bridge");
        subtitle.setTextColor(0xFF9CA9BD);
        subtitle.setTextSize(16);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        page.addView(subtitle, full());

        TextView relayLabel = label("Relay endpoint");
        page.addView(relayLabel, full());
        TextView relay = new TextView(this);
        relay.setText(BridgeService.RELAY_BASE);
        relay.setTextColor(0xFFE2E8F0);
        relay.setTextSize(12);
        relay.setPadding(dp(12), dp(12), dp(12), dp(12));
        relay.setBackgroundColor(0xFF202938);
        page.addView(relay, full());

        TextView tokenLabel = label("Private bridge token");
        tokenLabel.setPadding(0, dp(18), 0, dp(6));
        page.addView(tokenLabel, full());

        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setTextColor(0xFFFFFFFF);
        tokenInput.setHintTextColor(0xFF7F8CA3);
        tokenInput.setHint("Paste the agent_ token");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(SecureStore.read(this));
        tokenInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        tokenInput.setBackgroundColor(0xFF202938);
        page.addView(tokenInput, full());

        Button save = button("Save token");
        page.addView(save, buttonParams());
        save.setOnClickListener(v -> {
            String token = tokenInput.getText().toString().trim();
            if (!token.startsWith("agent_") || token.length() < 35) {
                setStatus("That token does not look valid. Paste the full agent token.");
                return;
            }
            SecureStore.write(this, token);
            setStatus("Token saved securely on this TV.");
        });

        Button start = button("Start LANLink bridge");
        page.addView(start, buttonParams());
        start.setOnClickListener(v -> {
            String token = tokenInput.getText().toString().trim();
            if (!token.startsWith("agent_")) {
                setStatus("Save your bridge token first.");
                return;
            }
            SecureStore.write(this, token);
            Intent intent = new Intent(this, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            setStatus("Starting bridge. Keep this app installed and allow it to run in the background.");
        });

        Button stop = button("Stop bridge");
        page.addView(stop, buttonParams());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            setStatus("Bridge stopped.");
        });

        status = label("Bridge is not started.");
        status.setTextColor(0xFFB9C7D9);
        status.setPadding(0, dp(18), 0, dp(10));
        page.addView(status, full());

        TextView note = new TextView(this);
        note.setText("Keep the TV connected to the same Wi-Fi/Ethernet network as the devices you want to discover. Discovery is limited to a local /24 or smaller subnet. The bridge only runs the operations exposed by LANLink; it cannot bypass device passwords or pairing.");
        note.setTextColor(0xFF8997AC);
        note.setTextSize(13);
        note.setPadding(0, dp(8), 0, dp(12));
        page.addView(note, full());
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFE6EDF7);
        view.setTextSize(15);
        return view;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setAllCaps(false);
        return b;
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(10);
        return p;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
