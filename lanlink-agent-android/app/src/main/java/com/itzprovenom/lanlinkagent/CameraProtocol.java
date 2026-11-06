package com.itzprovenom.lanlinkagent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import android.util.Base64;
import android.net.wifi.WifiManager;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/** Bounded ONVIF WS-Discovery/control and RTSP inspection for same-subnet cameras. */
final class CameraProtocol {
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3500;
    private static final int MAX_XML_BYTES = 256 * 1024;
    private static final int MAX_RTSP_HEADER_BYTES = 32 * 1024;
    private static final int MAX_RTSP_BODY_BYTES = 64 * 1024;
    private static final String WS_DISCOVERY_GROUP = "239.255.255.250";
    private static final int WS_DISCOVERY_PORT = 3702;

    private CameraProtocol() {}

    static JSONObject onvif(BridgeService bridge, JSONObject args) throws Exception {
        String action = args.optString("action", "device_info");
        if ("discover".equals(action)) return discoverOnvif(bridge);

        String ip = args.optString("ip", "");
        if (!bridge.isSameLocalSubnet(ip)) {
            throw new SecurityException("ONVIF target must be on the Android TV bridge's private subnet.");
        }
        String endpoint = args.optString("endpoint", "");
        if (endpoint.isEmpty()) endpoint = "http://" + ip + "/onvif/device_service";
        validateEndpoint(endpoint, ip);

        String username = optionalCredential(args, "username", 128);
        String password = optionalCredential(args, "password", 256);
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("Supply both ONVIF username and password.");
        }

        if ("device_info".equals(action)) {
            Document doc = soap(endpoint, ip, "http://www.onvif.org/ver10/device/wsdl/GetDeviceInformation",
                    "<tds:GetDeviceInformation/>", username, password);
            JSONObject result = new JSONObject();
            putText(result, doc, "manufacturer", "Manufacturer");
            putText(result, doc, "model", "Model");
            putText(result, doc, "firmware_version", "FirmwareVersion");
            putText(result, doc, "serial_number", "SerialNumber");
            putText(result, doc, "hardware_id", "HardwareId");
            result.put("ip", ip);
            result.put("endpoint", endpoint);
            result.put("protocol", "ONVIF");
            return result;
        }

        if ("capabilities".equals(action)) {
            Document doc = soap(endpoint, ip, "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
                    "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>",
                    username, password);
            String media = nestedText(doc, "Media", "XAddr");
            String ptz = nestedText(doc, "PTZ", "XAddr");
            if (!media.isEmpty()) validateEndpoint(media, ip);
            if (!ptz.isEmpty()) validateEndpoint(ptz, ip);
            JSONObject result = new JSONObject();
            result.put("ip", ip);
            result.put("device_endpoint", endpoint);
            if (!media.isEmpty()) result.put("media_endpoint", media);
            if (!ptz.isEmpty()) result.put("ptz_endpoint", ptz);
            result.put("media_supported", !media.isEmpty());
            result.put("ptz_supported", !ptz.isEmpty());
            return result;
        }

        if ("profiles".equals(action) || "snapshot_uri".equals(action)) {
            String mediaEndpoint = args.optString("media_endpoint", "");
            if (mediaEndpoint.isEmpty()) mediaEndpoint = endpoint;
            validateEndpoint(mediaEndpoint, ip);
            if ("profiles".equals(action)) {
                Document doc = soap(mediaEndpoint, ip, "http://www.onvif.org/ver10/media/wsdl/GetProfiles",
                        "<trt:GetProfiles/>", username, password);
                JSONArray profiles = parseProfiles(doc);
                JSONObject result = new JSONObject();
                result.put("ip", ip);
                result.put("media_endpoint", mediaEndpoint);
                result.put("profiles", profiles);
                return result;
            }
            String profile = args.optString("profile_token", "");
            if (profile.isEmpty()) {
                Document profilesDoc = soap(mediaEndpoint, ip, "http://www.onvif.org/ver10/media/wsdl/GetProfiles",
                        "<trt:GetProfiles/>", username, password);
                JSONArray profiles = parseProfiles(profilesDoc);
                if (profiles.length() == 0) throw new IllegalStateException("The camera returned no ONVIF media profiles.");
                profile = profiles.getJSONObject(0).optString("token", "");
            }
            if (profile.isEmpty()) throw new IllegalArgumentException("An ONVIF profile token is required.");
            Document doc = soap(mediaEndpoint, ip, "http://www.onvif.org/ver10/media/wsdl/GetSnapshotUri",
                    "<trt:GetSnapshotUri><trt:ProfileToken>" + xml(profile)
                            + "</trt:ProfileToken></trt:GetSnapshotUri>", username, password);
            String uri = firstText(doc, "Uri");
            if (uri.isEmpty()) throw new IllegalStateException("The camera did not return a snapshot URI.");
            JSONObject result = new JSONObject();
            result.put("ip", ip);
            result.put("profile_token", profile);
            result.put("snapshot_uri", stripUserInfo(uri));
            try {
                Document streamDoc = soap(mediaEndpoint, ip,
                        "http://www.onvif.org/ver10/media/wsdl/GetStreamUri",
                        "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>"
                                + "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>"
                                + "</trt:StreamSetup><trt:ProfileToken>" + xml(profile)
                                + "</trt:ProfileToken></trt:GetStreamUri>", username, password);
                String streamUri = firstText(streamDoc, "Uri");
                if (!streamUri.isEmpty()) result.put("stream_uri", stripUserInfo(streamUri));
            } catch (Exception streamError) {
                result.put("stream_uri_error", clean(streamError.getMessage(), 240));
            }
            return result;
        }

        if ("ptz_move".equals(action) || "ptz_stop".equals(action)) {
            String ptzEndpoint = args.optString("ptz_endpoint", "");
            if (ptzEndpoint.isEmpty()) {
                throw new IllegalArgumentException("A PTZ endpoint from ONVIF capabilities is required.");
            }
            validateEndpoint(ptzEndpoint, ip);
            String profile = args.optString("profile_token", "");
            if (profile.isEmpty()) throw new IllegalArgumentException("An ONVIF profile token is required.");
            String body;
            String soapAction;
            if ("ptz_stop".equals(action)) {
                body = "<tptz:Stop><tptz:ProfileToken>" + xml(profile)
                        + "</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>";
                soapAction = "http://www.onvif.org/ver20/ptz/wsdl/Stop";
            } else {
                double pan = bounded(args.optDouble("pan", 0), -1, 1, "pan");
                double tilt = bounded(args.optDouble("tilt", 0), -1, 1, "tilt");
                double zoom = bounded(args.optDouble("zoom", 0), -1, 1, "zoom");
                body = "<tptz:ContinuousMove><tptz:ProfileToken>" + xml(profile)
                        + "</tptz:ProfileToken><tptz:Velocity><tt:PanTilt x=\"" + pan
                        + "\" y=\"" + tilt + "\"/><tt:Zoom x=\"" + zoom
                        + "\"/></tptz:Velocity><tptz:Timeout>PT1S</tptz:Timeout></tptz:ContinuousMove>";
                soapAction = "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove";
            }
            soap(ptzEndpoint, ip, soapAction, body, username, password);
            JSONObject result = new JSONObject();
            result.put("ip", ip);
            result.put("action", action);
            result.put("completed", true);
            return result;
        }

        throw new IllegalArgumentException("Unsupported ONVIF action: " + action);
    }

    static JSONObject rtsp(BridgeService bridge, JSONObject args) throws Exception {
        String ip = args.optString("ip", "");
        if (!bridge.isSameLocalSubnet(ip)) {
            throw new SecurityException("RTSP target must be on the Android TV bridge's private subnet.");
        }
        int port = args.optInt("port", 554);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid RTSP port.");
        String path = args.optString("path", "/");
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\")
                || path.contains("\r") || path.contains("\n") || path.contains("..") || path.length() > 1024) {
            throw new IllegalArgumentException("RTSP path must be a safe origin-form path.");
        }
        String username = optionalCredential(args, "username", 128);
        String password = optionalCredential(args, "password", 256);
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("Supply both RTSP username and password.");
        }

        String uri = "rtsp://" + ip + ":" + port + path;
        RtspResponse options = rtspRequest(ip, port, "OPTIONS", uri, 1, null, null);
        RtspResponse describe = rtspRequest(ip, port, "DESCRIBE", uri, 2, null, null);
        if (describe.statusCode == 401 && username != null) {
            String challenge = describe.header("WWW-Authenticate");
            String authorization = authorization(challenge, username, password, "DESCRIBE", uri);
            if (authorization != null) {
                describe = rtspRequest(ip, port, "DESCRIBE", uri, 3, authorization, null);
            }
        }

        JSONObject result = new JSONObject();
        result.put("ip", ip);
        result.put("port", port);
        result.put("uri", uri);
        result.put("options_status", options.statusLine);
        result.put("describe_status", describe.statusLine);
        String server = describe.header("Server");
        if (server.isEmpty()) server = options.header("Server");
        if (!server.isEmpty()) result.put("server", clean(server, 200));
        String methods = options.header("Public");
        if (!methods.isEmpty()) result.put("methods", clean(methods, 300));
        result.put("authentication_required", describe.statusCode == 401);
        result.put("reachable", options.statusCode > 0 || describe.statusCode > 0);
        String contentType = describe.header("Content-Type");
        if (!contentType.isEmpty()) result.put("content_type", clean(contentType, 120));
        if (describe.statusCode == 200 && !describe.body.isEmpty()) {
            String sdp = clean(describe.body, 6000);
            result.put("sdp", sdp);
            result.put("tracks", parseTracks(describe.body));
        } else if (describe.statusCode == 401) {
            result.put("message", "RTSP endpoint requires camera credentials.");
        } else if (describe.statusCode == 404) {
            result.put("message", "RTSP is reachable, but this stream path does not exist.");
        }
        return result;
    }

    private static JSONObject discoverOnvif(BridgeService bridge) throws Exception {
        String probe = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\""
                + " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                + "<e:Header><w:MessageID>urn:uuid:" + UUID.randomUUID()
                + "</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
                + "<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>"
                + "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";
        byte[] bytes = probe.getBytes(StandardCharsets.UTF_8);
        JSONArray devices = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        long deadline = android.os.SystemClock.elapsedRealtime() + 2800L;
        WifiManager wifi = (WifiManager) bridge.getApplicationContext()
                .getSystemService(android.content.Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicastLock = null;
        try {
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("lanlink-onvif-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            try (MulticastSocket socket = new MulticastSocket()) {
                socket.setTimeToLive(1);
                socket.setSoTimeout(350);
                InetAddress group = InetAddress.getByName(WS_DISCOVERY_GROUP);
                socket.send(new DatagramPacket(bytes, bytes.length, group, WS_DISCOVERY_PORT));
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    byte[] buffer = new byte[16384];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (java.net.SocketTimeoutException timeout) {
                        continue;
                    }
                    String sourceIp = packet.getAddress().getHostAddress();
                    if (!bridge.isSameLocalSubnet(sourceIp)) continue;
                    String response = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    Document doc;
                    try { doc = parseXml(response); } catch (Exception ignored) { continue; }
                    String xaddrs = firstText(doc, "XAddrs");
                    String types = firstText(doc, "Types");
                    String scopes = firstText(doc, "Scopes");
                    if (xaddrs.isEmpty()) continue;
                    for (String xaddr : xaddrs.trim().split("\\s+")) {
                        try {
                            validateEndpoint(xaddr, sourceIp);
                            if (!seen.add(xaddr)) continue;
                            JSONObject item = new JSONObject();
                            item.put("ip", sourceIp);
                            item.put("endpoint", xaddr);
                            if (!types.isEmpty()) item.put("types", clean(types, 500));
                            if (!scopes.isEmpty()) item.put("scopes", clean(scopes, 1000));
                            item.put("protocol", "ONVIF");
                            devices.put(item);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } finally {
            if (multicastLock != null && multicastLock.isHeld()) {
                try { multicastLock.release(); } catch (Exception ignored) {}
            }
        }
        JSONObject result = new JSONObject();
        result.put("protocol", "ONVIF WS-Discovery");
        result.put("device_count", devices.length());
        result.put("devices", devices);
        return result;
    }

    private static Document soap(String endpoint, String expectedIp, String action, String body,
                                 String username, String password) throws Exception {
        String header = username == null ? "" : wsSecurityHeader(username, password);
        String envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\""
                + " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""
                + " xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\""
                + " xmlns:tt=\"http://www.onvif.org/ver10/schema\""
                + " xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">"
                + "<s:Header>" + header + "</s:Header><s:Body>" + body + "</s:Body></s:Envelope>";
        URL url = new URL(endpoint);
        if (!expectedIp.equals(url.getHost())) throw new SecurityException("ONVIF endpoint host changed unexpectedly.");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + action + "\"");
        connection.setRequestProperty("SOAPAction", "\"" + action + "\"");
        connection.setRequestProperty("Accept", "application/soap+xml, text/xml");
        byte[] request = envelope.getBytes(StandardCharsets.UTF_8);
        if (request.length > MAX_XML_BYTES) throw new IllegalArgumentException("ONVIF request exceeded size limit.");
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(request); }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) throw new IllegalStateException("ONVIF device returned HTTP " + status + " without a response body.");
            byte[] response = readBounded(input, MAX_XML_BYTES);
            if (status < 200 || status >= 300) {
                String detail = new String(response, StandardCharsets.UTF_8);
                String fault = "";
                try { fault = firstText(parseXml(detail), "Text"); } catch (Exception ignored) {}
                throw new IllegalStateException("ONVIF HTTP " + status + (fault.isEmpty() ? "" : ": " + clean(fault, 240)));
            }
            return parseXml(new String(response, StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static String wsSecurityHeader(String username, String password) throws Exception {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String nonceBase64 = Base64.encodeToString(nonce, Base64.NO_WRAP);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String created = format.format(new Date());
        ByteArrayOutputStream material = new ByteArrayOutputStream();
        material.write(nonce);
        material.write(created.getBytes(StandardCharsets.UTF_8));
        material.write(password.getBytes(StandardCharsets.UTF_8));
        String digest = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(material.toByteArray()), Base64.NO_WRAP);
        return "<wsse:Security s:mustUnderstand=\"1\""
                + " xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\""
                + " xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">"
                + "<wsse:UsernameToken><wsse:Username>" + xml(username) + "</wsse:Username>"
                + "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">"
                + digest + "</wsse:Password><wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"
                + nonceBase64 + "</wsse:Nonce><wsu:Created>" + created + "</wsu:Created></wsse:UsernameToken></wsse:Security>";
    }

    private static JSONArray parseProfiles(Document doc) throws Exception {
        JSONArray profiles = new JSONArray();
        NodeList nodes = doc.getElementsByTagNameNS("*", "Profiles");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            String token = element.getAttribute("token");
            if (token.isEmpty()) continue;
            JSONObject profile = new JSONObject();
            profile.put("token", clean(token, 256));
            String name = childText(element, "Name");
            if (!name.isEmpty()) profile.put("name", clean(name, 160));
            NodeList encoders = element.getElementsByTagNameNS("*", "VideoEncoderConfiguration");
            if (encoders.getLength() > 0) {
                Element encoder = (Element) encoders.item(0);
                String encoding = childText(encoder, "Encoding");
                if (!encoding.isEmpty()) profile.put("video_encoding", clean(encoding, 60));
                NodeList resolutions = encoder.getElementsByTagNameNS("*", "Resolution");
                if (resolutions.getLength() > 0) {
                    Element resolution = (Element) resolutions.item(0);
                    JSONObject size = new JSONObject();
                    size.put("width", childText(resolution, "Width"));
                    size.put("height", childText(resolution, "Height"));
                    profile.put("resolution", size);
                }
            }
            profiles.put(profile);
        }
        return profiles;
    }

    private static JSONArray parseTracks(String sdp) throws Exception {
        JSONArray tracks = new JSONArray();
        String[] lines = sdp.split("\\r?\\n");
        JSONObject current = null;
        for (String line : lines) {
            if (line.startsWith("m=")) {
                if (current != null) tracks.put(current);
                String[] parts = line.substring(2).trim().split("\\s+");
                current = new JSONObject();
                if (parts.length > 0) current.put("media", parts[0]);
                if (parts.length > 1) current.put("port", parts[1]);
                if (parts.length > 2) current.put("transport", parts[2]);
                if (parts.length > 3) current.put("payload_types", parts[3]);
            } else if (current != null && line.startsWith("a=control:")) {
                current.put("control", clean(line.substring(10), 500));
            } else if (current != null && line.startsWith("a=rtpmap:")) {
                current.put("codec", clean(line.substring(9), 200));
            }
        }
        if (current != null) tracks.put(current);
        return tracks;
    }

    private static RtspResponse rtspRequest(String ip, int port, String method, String uri,
                                            int cseq, String authorization, String body) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            String requestBody = body == null ? "" : body;
            String request = method + " " + uri + " RTSP/1.0\r\n"
                    + "CSeq: " + cseq + "\r\n"
                    + "User-Agent: LANLink/0.6\r\n"
                    + (authorization == null ? "" : "Authorization: " + authorization + "\r\n")
                    + ("DESCRIBE".equals(method) ? "Accept: application/sdp\r\n" : "")
                    + "Content-Length: " + requestBody.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                    + "\r\n" + requestBody;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return readRtspResponse(socket.getInputStream());
        }
    }

    private static RtspResponse readRtspResponse(InputStream input) throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        while (header.size() < MAX_RTSP_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) break;
            header.write(value);
            if ((matched == 0 && value == '\r') || (matched == 2 && value == '\r')) matched++;
            else if ((matched == 1 && value == '\n') || (matched == 3 && value == '\n')) matched++;
            else matched = value == '\r' ? 1 : 0;
            if (matched == 4) break;
        }
        if (matched != 4) throw new IllegalStateException("RTSP server returned an incomplete response header.");
        String headerText = new String(header.toByteArray(), StandardCharsets.US_ASCII);
        RtspResponse response = new RtspResponse();
        response.statusLine = firstLine(headerText);
        Matcher status = Pattern.compile("^RTSP/\\d\\.\\d\\s+(\\d{3})").matcher(response.statusLine);
        response.statusCode = status.find() ? Integer.parseInt(status.group(1)) : 0;
        String[] lines = headerText.split("\\r\\n");
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) response.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                    lines[i].substring(colon + 1).trim());
        }
        int length = 0;
        try { length = Integer.parseInt(response.header("Content-Length")); } catch (Exception ignored) {}
        if (length < 0 || length > MAX_RTSP_BODY_BYTES) throw new IllegalStateException("RTSP response body exceeded size limit.");
        if (length > 0) {
            byte[] body = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = input.read(body, offset, length - offset);
                if (count < 0) break;
                offset += count;
            }
            response.body = new String(body, 0, offset, StandardCharsets.UTF_8);
        }
        return response;
    }

    private static String authorization(String challenge, String username, String password,
                                         String method, String uri) throws Exception {
        if (challenge == null || challenge.isEmpty()) return null;
        if (challenge.regionMatches(true, 0, "Basic", 0, 5)) {
            String value = username + ":" + password;
            return "Basic " + Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
        if (!challenge.regionMatches(true, 0, "Digest", 0, 6)) return null;
        String realm = challengeValue(challenge, "realm");
        String nonce = challengeValue(challenge, "nonce");
        String qop = challengeValue(challenge, "qop");
        String algorithm = challengeValue(challenge, "algorithm");
        if (realm.isEmpty() || nonce.isEmpty() || (!algorithm.isEmpty() && !"MD5".equalsIgnoreCase(algorithm))) return null;
        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        String cnonce = UUID.randomUUID().toString().replace("-", "");
        String response;
        String qopValue = qop.toLowerCase(Locale.US).contains("auth") ? "auth" : "";
        String nc = "00000001";
        if (!qopValue.isEmpty()) response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qopValue + ":" + ha2);
        else response = md5(ha1 + ":" + nonce + ":" + ha2);
        StringBuilder header = new StringBuilder("Digest username=\"").append(quote(username))
                .append("\", realm=\"").append(quote(realm)).append("\", nonce=\"").append(quote(nonce))
                .append("\", uri=\"").append(quote(uri)).append("\", response=\"").append(response).append("\"");
        if (!qopValue.isEmpty()) header.append(", qop=").append(qopValue).append(", nc=").append(nc)
                .append(", cnonce=\"").append(cnonce).append("\"");
        if (!algorithm.isEmpty()) header.append(", algorithm=").append(algorithm);
        String opaque = challengeValue(challenge, "opaque");
        if (!opaque.isEmpty()) header.append(", opaque=\"").append(quote(opaque)).append("\"");
        return header.toString();
    }

    private static String challengeValue(String challenge, String key) {
        Matcher matcher = Pattern.compile("(?i)(?:^|[,\\s])" + Pattern.quote(key) + "=\"?([^\",]+)\"?").matcher(challenge);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(Locale.US, "%02x", b & 0xff));
        return result.toString();
    }

    private static String nestedText(Document doc, String parent, String child) {
        NodeList parents = doc.getElementsByTagNameNS("*", parent);
        if (parents.getLength() == 0 || !(parents.item(0) instanceof Element)) return "";
        return childText((Element) parents.item(0), child);
    }

    private static String childText(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagNameNS("*", name);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent().trim();
    }

    private static String firstText(Document doc, String name) {
        NodeList nodes = doc.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static void putText(JSONObject json, Document doc, String key, String node) throws Exception {
        String value = firstText(doc, node);
        if (!value.isEmpty()) json.put(key, clean(value, 300));
    }

    private static Document parseXml(String value) throws Exception {
        if (value == null || value.length() > MAX_XML_BYTES) throw new IllegalArgumentException("XML response exceeded size limit.");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(value)));
    }

    private static void validateEndpoint(String endpoint, String expectedIp) throws Exception {
        URL url = new URL(endpoint);
        if (!("http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol()))) {
            throw new SecurityException("ONVIF endpoint must use HTTP or HTTPS.");
        }
        if (!expectedIp.equals(url.getHost())) throw new SecurityException("ONVIF endpoint must point to the discovered camera IP.");
        if (url.getPort() > 65535 || url.getPort() == 0) throw new IllegalArgumentException("Invalid ONVIF endpoint port.");
        if (url.getUserInfo() != null) throw new SecurityException("ONVIF endpoint must not embed credentials.");
    }

    private static String optionalCredential(JSONObject args, String name, int max) {
        if (!args.has(name) || args.isNull(name)) return null;
        String value = args.optString(name, null);
        if (value == null || value.length() > max) throw new IllegalArgumentException("Invalid " + name + " length.");
        return value;
    }

    private static byte[] readBounded(InputStream input, int maximum) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) >= 0) {
                total += count;
                if (total > maximum) throw new IllegalStateException("Device response exceeded size limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String stripUserInfo(String uri) {
        try {
            URL url = new URL(uri);
            if (url.getUserInfo() == null) return clean(uri, 2000);
            return url.getProtocol() + "://" + url.getHost() + (url.getPort() < 0 ? "" : ":" + url.getPort())
                    + (url.getFile() == null ? "" : url.getFile());
        } catch (Exception ignored) {
            return clean(uri.replaceAll("//[^/@\\s]+@", "//"), 2000);
        }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String clean(String value, int maximum) {
        if (value == null) return "";
        String safe = value.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", "");
        return safe.length() > maximum ? safe.substring(0, maximum) : safe;
    }

    private static double bounded(double value, double min, double max, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("Invalid ONVIF " + name + " value.");
        }
        return value;
    }

    private static String firstLine(String value) {
        int index = value.indexOf("\r\n");
        return index < 0 ? value.trim() : value.substring(0, index).trim();
    }

    private static final class RtspResponse {
        int statusCode;
        String statusLine = "";
        String body = "";
        final java.util.Map<String, String> headers = new java.util.HashMap<>();
        String header(String key) {
            String value = headers.get(key.toLowerCase(Locale.US));
            return value == null ? "" : value;
        }
    }
}
