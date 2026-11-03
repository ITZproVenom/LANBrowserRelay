package com.itzprovenom.lanlinkagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DeviceFingerprint {
    private static final int SOCKET_TIMEOUT_MS = 850;
    private static final int MAX_RESPONSE_BYTES = 8192;

    private DeviceFingerprint() {}

    static void enrich(JSONObject device) {
        String ip = device.optString("ip", "");
        if (ip.isEmpty()) return;

        JSONArray services = device.optJSONArray("services");
        if (services == null) services = new JSONArray();
        for (int i = 0; i < services.length(); i++) {
            JSONObject service = services.optJSONObject(i);
            if (service == null) continue;
            int port = service.optInt("port", -1);
            if (port <= 0 || port > 65535) continue;
            try {
                JSONObject result = fingerprint(ip, port);
                for (java.util.Iterator<String> it = result.keys(); it.hasNext();) {
                    String key = it.next();
                    service.put(key, result.get(key));
                }
            } catch (Exception ignored) {
                // Fingerprinting is best-effort; a basic open-port result remains useful.
            }
        }
        if (hasPort(services, 53)) {
            String version = dnsVersion(ip);
            if (!version.isEmpty()) {
                try {
                    for (int i = 0; i < services.length(); i++) {
                        JSONObject service = services.optJSONObject(i);
                        if (service != null && service.optInt("port", -1) == 53) {
                            service.put("dns_version", version);
                            service.put("fingerprint", "DNS CHAOS TXT version.bind: " + version);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    static String resolveReverseHostname(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            String name = address.getCanonicalHostName();
            if (name == null || name.isEmpty() || name.equals(ip) || isNumericIPv4(name)) return "";
            if (name.length() > 253 || name.contains(" ")) return "";
            return name;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JSONObject fingerprint(String ip, int port) throws Exception {
        if (port == 22 || port == 21 || port == 23 || port == 25 || port == 110 || port == 143) {
            return readBanner(ip, port);
        }
        if (port == 554) return rtspOptions(ip, port);
        if (isHttpPort(port)) return httpProbe(ip, port);
        return new JSONObject();
    }

    private static boolean isHttpPort(int port) {
        return port == 80 || port == 81 || port == 5000 || port == 5001
                || port == 7000 || port == 8008 || port == 8009 || port == 8080
                || port == 8443 || port == 8888 || port == 9000 || port == 9090
                || port == 9100 || port == 32400 || port == 49152;
    }

    private static JSONObject readBanner(String ip, int port) {
        JSONObject result = new JSONObject();
        try (Socket socket = connect(ip, port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            byte[] data = readLimited(socket.getInputStream(), 1024);
            String banner = new String(data, StandardCharsets.UTF_8).trim();
            if (!banner.isEmpty()) {
                banner = clean(banner, 300);
                result.put("banner", banner);
                result.put("fingerprint", "TCP banner: " + banner);
                if (port == 22 && banner.startsWith("SSH-")) {
                    result.put("product", "SSH service");
                    result.put("server", banner);
                } else if (port == 21 && banner.startsWith("220")) {
                    result.put("product", "FTP service");
                    result.put("server", banner);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static JSONObject rtspOptions(String ip, int port) {
        JSONObject result = new JSONObject();
        try (Socket socket = connect(ip, port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            String request = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: LANLink/0.3\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            String response = new String(readLimited(socket.getInputStream(), MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
            String server = header(response, "Server");
            String status = firstLine(response);
            if (!status.isEmpty()) result.put("status", clean(status, 120));
            if (!server.isEmpty()) result.put("server", clean(server, 200));
            String methods = header(response, "Public");
            if (!methods.isEmpty()) result.put("rtsp_methods", clean(methods, 200));
            String fingerprint = "RTSP " + (status.isEmpty() ? "response" : status);
            if (!server.isEmpty()) fingerprint += " · " + server;
            if (!methods.isEmpty()) fingerprint += " · methods " + methods;
            result.put("fingerprint", clean(fingerprint, 400));
        } catch (Exception ignored) {}
        return result;
    }

    private static JSONObject httpProbe(String ip, int port) {
        JSONObject result = new JSONObject();
        String[] paths = port == 49152
                ? new String[]{"/rootDesc.xml", "/description.xml", "/"}
                : port == 32400 ? new String[]{"/identity", "/"} : new String[]{"/"};
        for (String path : paths) {
            try (Socket socket = connect(ip, port)) {
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                String request = "GET " + path + " HTTP/1.0\r\nHost: " + ip + "\r\n"
                        + "User-Agent: LANLink/0.3\r\nAccept: */*\r\nConnection: close\r\n\r\n";
                OutputStream output = socket.getOutputStream();
                output.write(request.getBytes(StandardCharsets.US_ASCII));
                output.flush();

                byte[] bytes = readLimited(socket.getInputStream(), MAX_RESPONSE_BYTES);
                String response = new String(bytes, StandardCharsets.UTF_8);
                String status = firstLine(response);
                if (status.isEmpty()) continue;
                result.put("status", clean(status, 120));
                String server = header(response, "Server");
                String poweredBy = header(response, "X-Powered-By");
                if (!server.isEmpty()) result.put("server", clean(server, 200));
                if (!poweredBy.isEmpty()) result.put("powered_by", clean(poweredBy, 200));

                String body = responseBody(response);
                String title = xmlOrHtmlValue(body, "title");
                if (!title.isEmpty()) result.put("title", title);
                String manufacturer = xmlOrHtmlValue(body, "manufacturer");
                String model = xmlOrHtmlValue(body, "modelName");
                String friendly = xmlOrHtmlValue(body, "friendlyName");
                if (!manufacturer.isEmpty()) result.put("upnp_manufacturer", manufacturer);
                if (!model.isEmpty()) result.put("upnp_model", model);
                if (!friendly.isEmpty()) result.put("upnp_friendly_name", friendly);

                String fingerprint = status;
                if (!server.isEmpty()) fingerprint += " · Server: " + server;
                if (!title.isEmpty()) fingerprint += " · Title: " + title;
                if (!manufacturer.isEmpty()) fingerprint += " · Manufacturer: " + manufacturer;
                if (!model.isEmpty()) fingerprint += " · Model: " + model;
                if (!friendly.isEmpty()) fingerprint += " · Name: " + friendly;
                if (!fingerprint.isEmpty()) result.put("fingerprint", clean(fingerprint, 700));
                if (status.contains(" 2") || status.contains(" 3") || (!title.isEmpty() || !manufacturer.isEmpty())) break;
            } catch (Exception ignored) {
                // Try the next known UPnP description path, or leave it unclassified.
            }
        }
        return result;
    }

    private static JSONObject dnsQuery(String ip, String name, int type, int dnsClass) {
        return new JSONObject();
    }

    private static String dnsVersion(String ip) {
        try {
            byte[] query = buildDnsQuery("version.bind", 16, 3);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(650);
                DatagramPacket outgoing = new DatagramPacket(query, query.length, InetAddress.getByName(ip), 53);
                socket.send(outgoing);
                byte[] buffer = new byte[1500];
                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                socket.receive(incoming);
                return parseDnsTxt(buffer, incoming.getLength());
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private static byte[] buildDnsQuery(String name, int type, int dnsClass) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int id = (int) (System.nanoTime() & 0xFFFF);
        out.write((id >> 8) & 255); out.write(id & 255);
        out.write(1); out.write(0); // standard query, recursion desired
        out.write(0); out.write(1); // one question
        out.write(0); out.write(0); out.write(0); out.write(0); out.write(0); out.write(0);
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(0);
        out.write((type >> 8) & 255); out.write(type & 255);
        out.write((dnsClass >> 8) & 255); out.write(dnsClass & 255);
        return out.toByteArray();
    }

    private static String parseDnsTxt(byte[] data, int length) {
        try {
            if (length < 12) return "";
            int answers = u16(data, 6);
            int offset = skipDnsName(data, 12);
            offset += 4; // question type/class
            for (int i = 0; i < answers && offset + 10 <= length; i++) {
                offset = skipDnsName(data, offset);
                int type = u16(data, offset);
                int dnsClass = u16(data, offset + 2);
                int rdLength = u16(data, offset + 8);
                offset += 10;
                if (rdLength < 1 || offset + rdLength > length) return "";
                if (type == 16 && dnsClass == 3) {
                    int textLength = data[offset] & 255;
                    if (textLength > 0 && textLength < rdLength) {
                        return clean(new String(data, offset + 1, textLength, StandardCharsets.UTF_8), 120);
                    }
                }
                offset += rdLength;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static int skipDnsName(byte[] data, int offset) throws Exception {
        int cursor = offset;
        int steps = 0;
        while (cursor < data.length && steps++ < 128) {
            int len = data[cursor] & 255;
            if (len == 0) return cursor + 1;
            if ((len & 0xC0) == 0xC0) return cursor + 2;
            if ((len & 0xC0) != 0 || len > 63 || cursor + 1 + len > data.length) throw new Exception("Invalid DNS label");
            cursor += 1 + len;
        }
        throw new Exception("Invalid DNS name");
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static Socket connect(String ip, int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(ip, port), SOCKET_TIMEOUT_MS);
        return socket;
    }

    private static byte[] readLimited(InputStream input, int maximum) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (output.size() < maximum) {
            int count;
            try {
                count = input.read(buffer, 0, Math.min(buffer.length, maximum - output.size()));
            } catch (java.net.SocketTimeoutException timeout) {
                break;
            }
            if (count < 0) break;
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String header(String response, String name) {
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : response.split("\\r?\\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) return line.substring(line.indexOf(':') + 1).trim();
        }
        return "";
    }

    private static String firstLine(String response) {
        int end = response.indexOf('\n');
        return (end < 0 ? response : response.substring(0, end)).trim();
    }

    private static String responseBody(String response) {
        int offset = response.indexOf("\r\n\r\n");
        if (offset >= 0) return response.substring(offset + 4);
        offset = response.indexOf("\n\n");
        return offset >= 0 ? response.substring(offset + 2) : "";
    }

    private static String xmlOrHtmlValue(String body, String tag) {
        Pattern pattern = Pattern.compile("<\\s*" + Pattern.quote(tag) + "\\s*>(.*?)<\\s*/\\s*" + Pattern.quote(tag) + "\\s*>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) return "";
        return clean(decodeEntities(matcher.group(1).replaceAll("<[^>]+>", " ").trim()), 160);
    }

    private static String decodeEntities(String value) {
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
    }

    private static String clean(String value, int max) {
        String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        return result.length() > max ? result.substring(0, max) : result;
    }

    private static boolean isNumericIPv4(String value) {
        return value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    }

    private static boolean hasPort(JSONArray services, int port) {
        for (int i = 0; i < services.length(); i++) {
            JSONObject service = services.optJSONObject(i);
            if (service != null && service.optInt("port", -1) == port) return true;
        }
        return false;
    }
}
