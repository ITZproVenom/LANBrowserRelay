package com.itzprovenom.lanlinkagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BridgeService extends Service {
    static final String RELAY_BASE = "https://odihwrrcgchtqohrgtdb.supabase.co/functions/v1/lanlink-mcp";
    private static final String CHANNEL = "lanlink_bridge";
    private static final int NOTIFICATION_ID = 7441;
    private static final int[] PORTS = {22, 23, 53, 80, 443, 554, 5555, 8008, 8009, 8080, 8443, 9000};
    private static final String[] NAMES = {"ssh","telnet","dns","http","https","rtsp","adb","http-alt","cast","http-alt","https-alt","http-alt"};
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService worker;
    private String token = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Waiting for bridge token"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        token = SecureStore.read(this);
        if (token.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running.compareAndSet(false, true)) {
            worker = Executors.newSingleThreadExecutor();
            worker.execute(this::loop);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running.set(false);
        if (worker != null) worker.shutdownNow();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void loop() {
        while (running.get()) {
            try {
                JSONObject response = request("/agent/poll", "GET", null);
                JSONArray commands = response.optJSONArray("commands");
                if (commands != null) {
                    for (int i = 0; i < commands.length() && running.get(); i++) {
                        JSONObject command = commands.optJSONObject(i);
                        if (command != null) executeCommand(command);
                    }
                }
            } catch (Exception e) {
                updateNotification("Relay unavailable: " + safe(e.getMessage()));
            }
            try { Thread.sleep(3000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    private void executeCommand(JSONObject command) {
        String id = command.optString("id", "");
        String kind = command.optString("kind", "");
        JSONObject payload = command.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();
        try {
            JSONObject result;
            JSONObject report = new JSONObject();
            if ("discover".equals(kind)) {
                result = discover();
                report.put("devices", result.optJSONArray("devices"));
            } else if ("probe".equals(kind)) {
                result = probe(payload.optString("ip", ""));
            } else if ("http".equals(kind)) {
                result = httpRequest(payload);
            } else if ("wol".equals(kind)) {
                result = wakeOnLan(payload);
            } else if ("adb".equals(kind)) {
                throw new IllegalStateException("ADB control is not available in the Android TV bridge build.");
            } else {
                throw new IllegalArgumentException("Unsupported command kind: " + kind);
            }
            report.put("command_id", id);
            report.put("result", result);
            request("/agent/report", "POST", report);
            updateNotification("Connected • last task: " + kind);
        } catch (Exception e) {
            try {
                JSONObject report = new JSONObject();
                report.put("command_id", id);
                report.put("error", safe(e.getMessage()));
                request("/agent/report", "POST", report);
            } catch (Exception ignored) {}
            updateNotification("Task failed: " + safe(e.getMessage()));
        }
    }

    private JSONObject request(String path, String method, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELAY_BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "LANLink-TV-Agent/0.1.0");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String raw = in == null ? "{}" : readLimited(in, 2_000_000);
        connection.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("Relay HTTP " + code + ": " + raw.substring(0, Math.min(raw.length(), 300)));
        return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
    }

    private static String readLimited(InputStream input, int max) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > max) throw new IllegalStateException("Response exceeds limit");
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static final class Lan {
        final String ip;
        final long network;
        final long broadcast;
        final int prefix;
        Lan(String ip, long network, long broadcast, int prefix) {
            this.ip = ip; this.network = network; this.broadcast = broadcast; this.prefix = prefix;
        }
        boolean contains(String value) throws Exception {
            long number = ipv4ToLong(InetAddress.getByName(value));
            return number >= network && number <= broadcast;
        }
        String subnet() { return longToIp(network) + "/" + prefix; }
    }

    private Lan localLan() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) throw new IllegalStateException("No network interfaces found");
        List<NetworkInterface> list = Collections.list(interfaces);
        Collections.sort(list, (a,b) -> {
            int pa = preferred(a.getName()), pb = preferred(b.getName());
            return Integer.compare(pa, pb);
        });
        for (NetworkInterface ni : list) {
            try {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = ni.getName().toLowerCase();
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("lo") || name.startsWith("rmnet")) continue;
                for (InterfaceAddress address : ni.getInterfaceAddresses()) {
                    InetAddress ia = address.getAddress();
                    if (!(ia instanceof Inet4Address) || !ia.isSiteLocalAddress()) continue;
                    int prefix = address.getNetworkPrefixLength();
                    if (prefix < 1 || prefix > 30) continue;
                    prefix = Math.max(prefix, 24); // Never scan more than 256 hosts.
                    long ip = ipv4ToLong(ia);
                    long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                    long net = ip & mask;
                    long bcast = net | (~mask & 0xFFFFFFFFL);
                    return new Lan(ia.getHostAddress(), net, bcast, prefix);
                }
            } catch (Exception ignored) {}
        }
        throw new IllegalStateException("Cannot find a private Wi-Fi/Ethernet IPv4 address on this TV.");
    }

    private static int preferred(String name) {
        String n = name.toLowerCase();
        if (n.equals("eth0") || n.startsWith("en")) return 0;
        if (n.equals("wlan0") || n.startsWith("wlan")) return 1;
        return 2;
    }

    private static long ipv4ToLong(InetAddress address) {
        byte[] b = address.getAddress();
        return ((b[0] & 255L) << 24) | ((b[1] & 255L) << 16) | ((b[2] & 255L) << 8) | (b[3] & 255L);
    }

    private static String longToIp(long value) {
        return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255);
    }

    private static boolean privateIp(String value) throws Exception {
        InetAddress address = InetAddress.getByName(value);
        if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        return (b[0] & 255) == 10 || ((b[0] & 255) == 172 && (b[1] & 255) >= 16 && (b[1] & 255) <= 31)
                || ((b[0] & 255) == 192 && (b[1] & 255) == 168);
    }

    private JSONObject discover() throws Exception {
        Lan lan = localLan();
        Map<String, String> arp = arpTable();
        List<Callable<JSONObject>> tasks = new ArrayList<>();
        for (long value = lan.network + 1; value < lan.broadcast; value++) {
            String ip = longToIp(value);
            if (ip.equals(lan.ip)) continue;
            tasks.add(() -> inspectHost(ip, arp.get(ip)));
        }
        ExecutorService scanPool = Executors.newFixedThreadPool(48);
        JSONArray devices = new JSONArray();
        JSONObject self = new JSONObject();
        self.put("ip", lan.ip); self.put("hostname", "LANLink TV"); self.put("mac", arp.get(lan.ip));
        self.put("services", new JSONArray());
        devices.put(self);
        try {
            List<Future<JSONObject>> results = scanPool.invokeAll(tasks, 25, TimeUnit.SECONDS);
            for (Future<JSONObject> future : results) {
                if (future.isCancelled()) continue;
                JSONObject item = future.get();
                if (item != null) devices.put(item);
            }
        } finally {
            scanPool.shutdownNow();
        }
        for (Map.Entry<String,String> entry : arp.entrySet()) {
            if (!lan.contains(entry.getKey())) continue;
            boolean exists = false;
            for (int i=0; i<devices.length(); i++) if (entry.getKey().equals(devices.optJSONObject(i).optString("ip"))) { exists=true; break; }
            if (!exists) {
                JSONObject item = new JSONObject();
                item.put("ip", entry.getKey()); item.put("mac", entry.getValue());
                item.put("hostname", JSONObject.NULL); item.put("services", new JSONArray());
                devices.put(item);
            }
        }
        JSONObject result = new JSONObject();
        result.put("subnet", lan.subnet());
        result.put("bridge_ip", lan.ip);
        result.put("device_count", devices.length());
        result.put("devices", devices);
        return result;
    }

    private JSONObject inspectHost(String ip, String mac) {
        try {
            JSONArray services = new JSONArray();
            for (int i=0; i<PORTS.length; i++) {
                try (Socket socket = new Socket()) {
                    socket.connect(new java.net.InetSocketAddress(ip, PORTS[i]), 130);
                    JSONObject service = new JSONObject();
                    service.put("port", PORTS[i]); service.put("service", NAMES[i]);
                    services.put(service);
                } catch (Exception ignored) {}
            }
            if (services.length() == 0 && mac == null) return null;
            JSONObject item = new JSONObject();
            item.put("ip", ip);
            item.put("mac", mac == null ? JSONObject.NULL : mac);
            item.put("hostname", JSONObject.NULL);
            item.put("services", services);
            return item;
        } catch (Exception ignored) { return null; }
    }

    private JSONObject probe(String value) throws Exception {
        Lan lan = localLan();
        if (!privateIp(value) || !lan.contains(value)) throw new IllegalArgumentException("Target must be on the TV's local subnet.");
        JSONObject item = inspectHost(value, arpTable().get(value));
        if (item == null) { item = new JSONObject(); item.put("ip", value); item.put("services", new JSONArray()); }
        return item;
    }

    private Map<String,String> arpTable() {
        Map<String,String> result = new HashMap<>();
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/net/arp"))) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4 && parts[3].matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) result.put(parts[0], parts[3].toLowerCase());
            }
        } catch (Exception ignored) {}
        return result;
    }

    private JSONObject httpRequest(JSONObject p) throws Exception {
        String ip = p.optString("ip", "");
        Lan lan = localLan();
        if (!privateIp(ip) || !lan.contains(ip)) throw new IllegalArgumentException("HTTP target must be on the TV's local subnet.");
        int port = p.optInt("port", 0);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid TCP port.");
        String scheme = p.optString("scheme", "http");
        if (!"http".equals(scheme) && !"https".equals(scheme)) throw new IllegalArgumentException("Invalid URL scheme.");
        String method = p.optString("method", "GET").toUpperCase();
        if (!List.of("GET","HEAD","POST","PUT","PATCH","DELETE").contains(method)) throw new IllegalArgumentException("HTTP method is not allowed.");
        String path = p.optString("path", "/");
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\\\") || path.contains("\\r") || path.contains("\\n") || path.length() > 2048 || java.util.Arrays.asList(path.split("/")).contains("..")) throw new IllegalArgumentException("Unsafe request path.");
        String body = p.optString("body", "");
        if (body.getBytes(StandardCharsets.UTF_8).length > 16384) throw new IllegalArgumentException("Request body is too large.");
        String contentType = p.optString("content_type", "application/json");
        if (!List.of("application/json","text/plain","application/x-www-form-urlencoded").contains(contentType)) throw new IllegalArgumentException("Content-Type not allowed.");
        String endpoint = scheme + "://" + ip + ":" + port + path;
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(6000); c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Content-Type", contentType);
        c.setRequestProperty("User-Agent", "LANLink-TV-Agent/0.1.0");
        if (!body.isEmpty() && !("GET".equals(method) || "HEAD".equals(method))) {
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String response = in == null ? "" : readLimited(in, 200000);
        JSONObject result = new JSONObject();
        result.put("target", endpoint); result.put("status", code);
        result.put("body", response);
        JSONObject headers = new JSONObject();
        for (String h : new String[]{"Content-Type","Server","WWW-Authenticate","Location"}) {
            String v = c.getHeaderField(h);
            if (v != null) headers.put(h.toLowerCase(), v.length() > 500 ? v.substring(0,500) : v);
        }
        result.put("headers", headers);
        c.disconnect();
        return result;
    }

    private JSONObject wakeOnLan(JSONObject p) throws Exception {
        String mac = p.optString("mac", "").replace('-', ':').toLowerCase();
        if (!mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) throw new IllegalArgumentException("Invalid MAC address.");
        byte[] address = new byte[6];
        String[] octets = mac.split(":");
        for (int i=0;i<6;i++) address[i] = (byte) Integer.parseInt(octets[i],16);
        byte[] packet = new byte[102];
        for (int i=0;i<6;i++) packet[i] = (byte)0xFF;
        for (int i=6;i<packet.length;i++) packet[i] = address[(i-6)%6];
        Lan lan = localLan();
        String broadcast = p.optString("broadcast", longToIp(lan.broadcast));
        if (!lan.contains(broadcast) && !broadcast.equals("255.255.255.255")) throw new IllegalArgumentException("Broadcast must be on the local subnet.");
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(broadcast), 9));
        }
        JSONObject result = new JSONObject();
        result.put("sent", true); result.put("mac", mac); result.put("broadcast", broadcast);
        return result;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "LANLink bridge", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification notification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL)
                    .setContentTitle("LANLink TV Agent")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("LANLink TV Agent")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(text));
    }

    private static String safe(String value) { return value == null ? "Unknown error" : value.substring(0, Math.min(300, value.length())); }
}
