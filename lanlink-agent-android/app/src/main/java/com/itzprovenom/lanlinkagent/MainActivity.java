package com.itzprovenom.lanlinkagent;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.security.SecureRandom;

public final class MainActivity extends Activity {
    private EditText tokenInput;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ensureControllerToken();
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private void ensureControllerToken() {
        if (!SecureStore.readControllerToken(this).isEmpty()) return;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        SecureStore.writeControllerToken(this, token);
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
        title.setText("LANLink Android Agent");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(28);
        page.addView(title, full());

        TextView subtitle = new TextView(this);
        subtitle.setText("The network engine for your iPhone controller");
        subtitle.setTextColor(0xFF9CA9BD);
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        page.addView(subtitle, full());

        TextView pairHeading = label("iPhone controller pairing");
        pairHeading.setPadding(0, dp(6), 0, dp(6));
        page.addView(pairHeading, full());

        TextView address = label("TV address: " + localIPv4() + ":" + LocalControlServer.PORT);
        address.setTextColor(0xFF5DD8F5);
        address.setTextSize(17);
        address.setPadding(dp(12), dp(12), dp(12), dp(12));
        address.setBackgroundColor(0xFF202938);
        page.addView(address, full());

        TextView pairToken = label(SecureStore.readControllerToken(this));
        pairToken.setTextIsSelectable(true);
        pairToken.setTextSize(13);
        pairToken.setPadding(dp(12), dp(12), dp(12), dp(12));
        pairToken.setBackgroundColor(0xFF202938);
        LinearLayout.LayoutParams pairParams = full();
        pairParams.topMargin = dp(8);
        page.addView(pairToken, pairParams);

        TextView pairNote = label("Enter this TV address and pairing key in LANLink on your iPhone. Keep both devices on the same Wi-Fi/Ethernet network. The key is stored encrypted with Android Keystore.");
        pairNote.setTextColor(0xFF8997AC);
        pairNote.setTextSize(12);
        pairNote.setPadding(0, dp(8), 0, dp(16));
        page.addView(pairNote, full());

        TextView relayLabel = label("ChatGPT relay agent token");
        page.addView(relayLabel, full());
        TextView relay = new TextView(this);
        relay.setText(BridgeService.RELAY_BASE);
        relay.setTextColor(0xFFE2E8F0);
        relay.setTextSize(12);
        relay.setPadding(dp(12), dp(12), dp(12), dp(12));
        relay.setBackgroundColor(0xFF202938);
        page.addView(relay, full());

        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setTextColor(0xFFFFFFFF);
        tokenInput.setHintTextColor(0xFF7F8CA3);
        tokenInput.setHint("Paste the Android agent_ token");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(SecureStore.read(this));
        tokenInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        tokenInput.setBackgroundColor(0xFF202938);
        LinearLayout.LayoutParams inputParams = full();
        inputParams.topMargin = dp(8);
        page.addView(tokenInput, inputParams);

        Button save = button("Save relay token");
        page.addView(save, buttonParams());
        save.setOnClickListener(v -> {
            String token = tokenInput.getText().toString().trim();
            if (!token.startsWith("agent_") || token.length() < 35) {
                setStatus("That relay token does not look valid. Paste the full Android agent token.");
                return;
            }
            SecureStore.write(this, token);
            setStatus("Relay token saved securely on this TV.");
        });

        Button start = button("Start Android agent");
        page.addView(start, buttonParams());
        start.setOnClickListener(v -> {
            String token = tokenInput.getText().toString().trim();
            if (!token.startsWith("agent_")) {
                setStatus("Save your Android relay token first.");
                return;
            }
            SecureStore.write(this, token);
            Intent intent = new Intent(this, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            setStatus("Android agent starting. The iPhone controller can now connect to " + localIPv4() + ":" + LocalControlServer.PORT + ".");
        });

        Button pause = button("Pause ChatGPT relay");
        page.addView(pause, buttonParams());
        pause.setOnClickListener(v -> {
            Intent intent = new Intent(this, BridgeService.class);
            intent.setAction(BridgeService.ACTION_STOP_RELAY);
            startService(intent);
            setStatus("ChatGPT relay paused. The local iPhone controller remains available.");
        });

        status = label("Start the Android agent to enable iPhone control.");
        status.setTextColor(0xFFB9C7D9);
        status.setPadding(0, dp(18), 0, dp(10));
        page.addView(status, full());

        TextView note = new TextView(this);
        note.setText("This TV performs LAN discovery and device operations. The iPhone app is the remote controller. The Android foreground service remains available while the relay is paused, so the controller can start it again.");
        note.setTextColor(0xFF8997AC);
        note.setTextSize(13);
        note.setPadding(0, dp(8), 0, dp(12));
        page.addView(note, full());
    }

    private String localIPv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "Unknown";
            java.util.List<NetworkInterface> list = Collections.list(interfaces);
            Collections.sort(list, (a,b) -> Integer.compare(interfaceRank(a.getName()), interfaceRank(b.getName())));
            for (NetworkInterface ni : list) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && ia.getAddress().isSiteLocalAddress()) {
                        return ia.getAddress().getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private int interfaceRank(String name) {
        String value = name.toLowerCase();
        if (value.equals("eth0") || value.startsWith("en")) return 0;
        if (value.equals("wlan0") || value.startsWith("wlan")) return 1;
        return 2;
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
