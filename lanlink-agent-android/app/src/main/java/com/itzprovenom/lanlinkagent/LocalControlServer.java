package com.itzprovenom.lanlinkagent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authenticated, same-subnet control API for the iOS LANLink controller.
 * The listener is only a control plane; all scanning and device operations run
 * in BridgeService, so iOS does not become a second LAN agent.
 */
final class LocalControlServer {
    static final int PORT = 43821;
    private static final int MAX_HEADER_BYTES = 16_384;
    private static final int MAX_BODY_BYTES = 65_536;

    private final BridgeService service;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService clients = Executors.newFixedThreadPool(4);
    private ServerSocket serverSocket;
    private Thread acceptThread;

    LocalControlServer(BridgeService service) {
        this.service = service;
    }

    void start() throws Exception {
        if (!running.compareAndSet(false, true)) return;
        try {
            serverSocket = new ServerSocket(PORT, 16, InetAddress.getByName("0.0.0.0"));
            acceptThread = new Thread(this::acceptLoop, "lanlink-local-api");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (Exception e) {
            running.set(false);
            throw e;
        }
    }

    void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        clients.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(15_000);
                clients.execute(() -> handle(socket));
            } catch (Exception e) {
                if (running.get()) service.updateLocalApiStatus("Local controller listener error");
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            InetAddress remote = client.getInetAddress();
            if (remote == null || !service.isAllowedControllerClient(remote)) {
                respond(client.getOutputStream(), 403, new JSONObject().put("error", "Controller must be on the TV's private LAN."));
                return;
            }

            InputStream input = client.getInputStream();
            String requestLine = readLine(input, 4096);
            if (requestLine == null) return;
            String[] requestParts = requestLine.split(" ");
            if (requestParts.length != 3 || !requestParts[2].startsWith("HTTP/1.")) {
                respond(client.getOutputStream(), 400, new JSONObject().put("error", "Malformed HTTP request."));
                return;
            }

            String method = requestParts[0].toUpperCase();
            String path = requestParts[1];
            String authorization = "";
            int contentLength = 0;
            int headerBytes = requestLine.length();

            while (true) {
                String line = readLine(input, 8192);
                if (line == null) return;
                headerBytes += line.length();
                if (headerBytes > MAX_HEADER_BYTES) {
                    respond(client.getOutputStream(), 431, new JSONObject().put("error", "Request headers are too large."));
                    return;
                }
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                if ("authorization".equals(name)) authorization = value;
                if ("content-length".equals(name)) {
                    try { contentLength = Integer.parseInt(value); }
                    catch (Exception ignored) { contentLength = -1; }
                }
                if ("transfer-encoding".equals(name) && !value.isEmpty()) {
                    respond(client.getOutputStream(), 400, new JSONObject().put("error", "Chunked requests are not supported."));
                    return;
                }
            }

            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                respond(client.getOutputStream(), 413, new JSONObject().put("error", "Request body is too large."));
                return;
            }
            byte[] bodyBytes = readExact(input, contentLength);
            if (bodyBytes == null) {
                respond(client.getOutputStream(), 400, new JSONObject().put("error", "Incomplete request body."));
                return;
            }

            JSONObject body = bodyBytes.length == 0
                    ? new JSONObject()
                    : new JSONObject(new String(bodyBytes, StandardCharsets.UTF_8));

            // First-connection enrollment: only the first controller on this
            // private subnet can claim the single controller slot. The iOS app
            // generates the random key and retains it in Keychain.
            if ("POST".equals(method) && "/v1/pair".equals(path)) {
                JSONObject paired = service.pairController(body.optString("controller_token", ""));
                respond(client.getOutputStream(), 200, paired);
                return;
            }

            if (!constantTimeTokenMatch(authorization)) {
                respond(client.getOutputStream(), 401, new JSONObject().put("error", "Invalid controller token."));
                return;
            }

            JSONObject result;
            if ("GET".equals(method) && "/v1/status".equals(path)) {
                result = service.controlStatus();
            } else if ("GET".equals(method) && "/v1/devices".equals(path)) {
                result = service.controlDevices();
            } else if ("GET".equals(method) && "/v1/activity".equals(path)) {
                result = service.controlActivity();
            } else if ("GET".equals(method) && "/v1/logs".equals(path)) {
                result = service.controlLogs();
            } else if ("POST".equals(method) && "/v1/scan".equals(path)) {
                result = service.controlScan();
            } else if ("POST".equals(method) && "/v1/relay/credential".equals(path)) {
                result = service.controlInstallRelayCredential(body.optString("agent_token", ""));
            } else if ("POST".equals(method) && "/v1/bridge/start".equals(path)) {
                result = service.controlStartBridge();
            } else if ("POST".equals(method) && "/v1/bridge/stop".equals(path)) {
                result = service.controlStopBridge();
            } else if ("POST".equals(method) && "/v1/command".equals(path)) {
                result = service.controlCommand(body);
            } else {
                respond(client.getOutputStream(), 404, new JSONObject().put("error", "Unknown LANLink controller route."));
                return;
            }
            respond(client.getOutputStream(), 200, result);
        } catch (SecurityException e) {
            try { respond(socket.getOutputStream(), 403, new JSONObject().put("error", safe(e.getMessage()))); }
            catch (Exception ignored) {}
        } catch (Exception e) {
            try { respond(socket.getOutputStream(), 500, new JSONObject().put("error", safe(e.getMessage()))); }
            catch (Exception ignored) {}
        }
    }

    private boolean constantTimeTokenMatch(String authorization) {
        String controllerToken = SecureStore.readControllerToken(service);
        if (controllerToken == null || controllerToken.isEmpty()) return false;
        String expected = "Bearer " + controllerToken;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        while (out.size() <= limit) {
            int value = input.read();
            if (value == -1) return out.size() == 0 ? null : out.toString("UTF-8");
            if (previous == '\r' && value == '\n') {
                byte[] bytes = out.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            out.write(value);
            previous = value;
        }
        throw new IllegalArgumentException("HTTP line too long.");
    }

    private static byte[] readExact(InputStream input, int length) throws Exception {
        if (length == 0) return new byte[0];
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) return null;
            offset += count;
        }
        return data;
    }

    private static void respond(OutputStream output, int code, JSONObject body) throws Exception {
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 401 ? "Unauthorized"
                : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 413 ? "Payload Too Large"
                : code == 431 ? "Request Header Fields Too Large" : "Internal Server Error";
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(data);
        output.flush();
    }

    private static String safe(String value) {
        if (value == null) return "Unknown error";
        return value.substring(0, Math.min(300, value.length()));
    }
}
