package com.itzprovenom.lanlinkagent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class DeviceIdentity {
    private static final Object LOCK = new Object();
    private static volatile Map<String, String> vendors;
    private static final long CACHE_AGE_MS = 30L * 24L * 60L * 60L * 1000L;

    private DeviceIdentity() {}

    static void enrich(Context context, JSONObject device, String gatewayIp,
                       String hostname, String hostnameSource) {
        try {
            String ip = device.optString("ip", "");
            String mac = device.isNull("mac") ? "" : device.optString("mac", "");
            String existingHostname = device.optString("hostname", "");
            if ("null".equalsIgnoreCase(existingHostname)) existingHostname = "";
            String discoverySource = device.optString("discovery_source", "unknown");
            String effectiveHostname = hostname == null || hostname.isEmpty() ? existingHostname : hostname;
            VendorResult vendorResult = lookupVendor(context, mac);
            String vendor = vendorResult.vendor;
            String macType = macAddressType(mac);
            JSONArray services = device.optJSONArray("services");
            if (services == null) services = new JSONArray();

            String upnpManufacturer = firstServiceValue(services, "upnp_manufacturer");
            String upnpModel = firstServiceValue(services, "upnp_model");
            String friendlyName = firstServiceValue(services, "upnp_friendly_name");
            if (friendlyName.isEmpty()) friendlyName = firstServiceValue(services, "title");
            String serverProduct = firstServiceValue(services, "server");
            JSONArray evidence = new JSONArray();
            JSONArray fingerprints = new JSONArray();

            boolean isGateway = !gatewayIp.isEmpty() && gatewayIp.equals(ip);
            if (isGateway) evidence.put("Default gateway route points to this address.");
            if (!vendor.isEmpty()) evidence.put("MAC prefix registered to " + vendor + ".");
            if ("locally_administered".equals(macType)) evidence.put("MAC is locally administered; vendor lookup is not reliable.");
            if (!effectiveHostname.isEmpty()) {
                evidence.put(hostname != null && !hostname.isEmpty()
                        ? "Hostname resolved through " + (hostnameSource == null ? "reverse DNS" : hostnameSource) + "."
                        : "Name supplied by the LANLink agent.");
            }
            if (!upnpManufacturer.isEmpty()) evidence.put("UPnP description reports manufacturer " + upnpManufacturer + ".");
            if (!upnpModel.isEmpty()) evidence.put("UPnP description reports model " + upnpModel + ".");

            for (int i = 0; i < services.length(); i++) {
                JSONObject service = services.optJSONObject(i);
                if (service == null) continue;
                String fingerprint = service.optString("fingerprint", "");
                if (!fingerprint.isEmpty()) fingerprints.put(fingerprint);
                String server = service.optString("server", "");
                if (!server.isEmpty()) evidence.put("Service banner: " + server + ".");
            }

            boolean applePort = hasPort(services, 62078);
            boolean dnsPort = hasPort(services, 53);
            boolean printerPort = hasPort(services, 515) || hasPort(services, 631) || hasPort(services, 9100);
            boolean plexPort = hasPort(services, 32400);
            boolean rtspPort = hasPort(services, 554);
            boolean smbPort = hasPort(services, 445) || hasPort(services, 139);
            boolean rdpPort = hasPort(services, 3389);
            boolean sshPort = hasPort(services, 22);
            boolean upnpPort = hasPort(services, 49152) || hasPort(services, 1900);
            String type;
            String confidence;
            String method;

            if ("agent".equals(discoverySource)) {
                type = "LANLink Android agent";
                confidence = "high";
                method = "local_agent";
            } else if (!upnpModel.isEmpty() || !upnpManufacturer.isEmpty()) {
                type = !upnpModel.isEmpty() ? "UPnP device · " + upnpModel : "UPnP device";
                confidence = "high";
                method = "upnp_description";
            } else if (isGateway) {
                type = "Network gateway";
                confidence = "high";
                method = "default_route";
            } else if ("Apple, Inc.".equalsIgnoreCase(vendor) || applePort) {
                type = "Apple device";
                confidence = !vendor.isEmpty() && applePort ? "high" : "medium";
                method = !vendor.isEmpty() && applePort ? "oui_and_service" : "oui_or_service";
            } else if (printerPort) {
                type = "Printer / print server";
                confidence = "medium";
                method = "printer_service_port";
            } else if (plexPort) {
                type = "Plex media server";
                confidence = "high";
                method = "plex_service_port";
            } else if (rtspPort) {
                type = "RTSP media endpoint";
                confidence = "medium";
                method = "rtsp_service_port";
            } else if (rdpPort) {
                type = "Remote Desktop host";
                confidence = "medium";
                method = "rdp_service_port";
            } else if (smbPort) {
                type = "File-sharing host";
                confidence = "medium";
                method = "smb_service_port";
            } else if (dnsPort) {
                type = "DNS server / resolver";
                confidence = "medium";
                method = "dns_service_port";
            } else if (isNetworkVendor(vendor)) {
                type = "Network equipment";
                confidence = "medium";
                method = "oui_vendor";
            } else if (upnpPort) {
                type = "UPnP-enabled device";
                confidence = "low";
                method = "upnp_service_port";
            } else if (sshPort) {
                type = "SSH-accessible host";
                confidence = "low";
                method = "ssh_service_port";
            } else if (!vendor.isEmpty()) {
                type = "Device registered to " + vendor;
                confidence = "low";
                method = "oui_vendor_only";
            } else {
                type = "Unknown network device";
                confidence = "low";
                method = "service_evidence_only";
            }

            if (!serverProduct.isEmpty()) evidence.put("Service banner identifies as " + serverProduct + ".");
            device.put("hostname", effectiveHostname.isEmpty() ? JSONObject.NULL : effectiveHostname);
            device.put("hostname_source", hostname != null && !hostname.isEmpty()
                    ? (hostnameSource == null || hostnameSource.isEmpty() ? "reverse DNS" : hostnameSource)
                    : ("agent".equals(discoverySource) ? "LANLink" : JSONObject.NULL));
            device.put("device_vendor", vendor.isEmpty() ? JSONObject.NULL : vendor);
            device.put("device_manufacturer", upnpManufacturer.isEmpty() ? JSONObject.NULL : upnpManufacturer);
            device.put("device_model", upnpModel.isEmpty() ? JSONObject.NULL : upnpModel);
            device.put("device_friendly_name", friendlyName.isEmpty() ? JSONObject.NULL : friendlyName);
            device.put("device_type", type);
            device.put("identity_confidence", confidence);
            device.put("identity_method", method);
            device.put("mac_address_type", macType);
            device.put("identity_evidence", evidence);
            device.put("service_fingerprints", fingerprints);
            device.put("vendor_lookup_source", vendorResult.source);
        } catch (Exception ignored) {
            // Identity enrichment must never fail the underlying LAN scan.
        }
    }

    private static final class VendorResult {
        final String vendor;
        final String source;
        VendorResult(String vendor, String source) {
            this.vendor = vendor;
            this.source = source;
        }
    }

    private static VendorResult lookupVendor(Context context, String mac) {
        String normalized = normalizeMac(mac);
        if (normalized.length() != 12) return new VendorResult("", "mac_unavailable");
        if (isLocallyAdministered(normalized)) {
            return new VendorResult("", "locally_administered_mac");
        }

        android.content.SharedPreferences cache =
                context.getSharedPreferences("lanlink_vendor_cache", Context.MODE_PRIVATE);
        String cacheKey = "mac_" + normalized;
        String cachedVendor = cache.getString(cacheKey, null);
        long cachedAt = cache.getLong(cacheKey + "_at", 0L);
        if (cachedVendor != null && System.currentTimeMillis() - cachedAt < CACHE_AGE_MS) {
            return new VendorResult(cachedVendor, "third_party_cached");
        }

        // Anonymous IEEE OUI lookup. Only the MAC address is sent; IP address,
        // hostname, scan results and service banners stay on the TV.
        try {
            URL url = new URL("https://api.macadress.com/v1/vendor/" + normalized);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1800);
            connection.setReadTimeout(1800);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "text/plain");
            connection.setRequestProperty("User-Agent", "LANLink-TV-Agent/0.3.4");
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = stream == null ? "" : readSmall(stream, 256).trim();
            connection.disconnect();

            if (status == 200 && !response.isEmpty()
                    && response.length() <= 180
                    && !response.toLowerCase(Locale.ROOT).contains("not registered")
                    && !response.toLowerCase(Locale.ROOT).contains("locally administered")) {
                cache.edit().putString(cacheKey, response)
                        .putLong(cacheKey + "_at", System.currentTimeMillis()).apply();
                return new VendorResult(response, "third_party_macadress_api");
            }
            if (status == 404) {
                cache.edit().putString(cacheKey, "")
                        .putLong(cacheKey + "_at", System.currentTimeMillis()).apply();
                return new VendorResult("", response.isEmpty() ? "third_party_no_registration" : response.toLowerCase(Locale.ROOT).replace(' ', '_'));
            }
            return new VendorResult(fallbackVendor(context, normalized), "bundled_fallback");
        } catch (Exception ignored) {
            String fallback = fallbackVendor(context, normalized);
            return new VendorResult(fallback, fallback.isEmpty() ? "lookup_unavailable" : "bundled_fallback");
        }
    }

    private static String readSmall(InputStream input, int maxBytes) throws Exception {
        try (InputStream in = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[128];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > maxBytes) break;
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }

    private static String fallbackVendor(Context context, String normalized) {
        Map<String, String> table = loadVendors(context);
        for (int length : new int[]{9, 7, 6}) {
            String vendor = table.get(normalized.substring(0, length));
            if (vendor != null) return vendor;
        }
        return "";
    }

    static String macAddressType(String mac) {
        String normalized = normalizeMac(mac);
        if (normalized.length() != 12) return "unavailable";
        try {
            int firstOctet = Integer.parseInt(normalized.substring(0, 2), 16);
            if ((firstOctet & 1) != 0) return "multicast";
            return (firstOctet & 2) != 0 ? "locally_administered" : "universally_administered";
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private static boolean isLocallyAdministered(String normalized) {
        try {
            int firstOctet = Integer.parseInt(normalized.substring(0, 2), 16);
            return (firstOctet & 2) != 0 || (firstOctet & 1) != 0;
        } catch (Exception ignored) {
            return true;
        }
    }

    private static String normalizeMac(String mac) {
        if (mac == null) return "";
        return mac.replace(":", "").replace("-", "").replace(".", "")
                .replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> loadVendors(Context context) {
        Map<String, String> cached = vendors;
        if (cached != null) return cached;
        synchronized (LOCK) {
            if (vendors != null) return vendors;
            Map<String, String> loaded = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("oui-vendors.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.trim();
                    if (value.isEmpty() || value.startsWith("#")) continue;
                    int separator = value.indexOf('|');
                    if (separator <= 0 || separator == value.length() - 1) continue;
                    String prefix = normalizeMac(value.substring(0, separator));
                    String vendor = value.substring(separator + 1).trim();
                    if ((prefix.length() == 6 || prefix.length() == 7 || prefix.length() == 9)
                            && prefix.matches("[0-9A-F]+") && !vendor.isEmpty()) {
                        loaded.put(prefix, vendor);
                    }
                }
            } catch (Exception ignored) {}
            vendors = loaded;
            return loaded;
        }
    }

    private static boolean hasPort(JSONArray services, int expected) {
        for (int i = 0; i < services.length(); i++) {
            JSONObject service = services.optJSONObject(i);
            if (service != null && service.optInt("port", -1) == expected) return true;
        }
        return false;
    }

    private static String firstServiceValue(JSONArray services, String key) {
        for (int i = 0; i < services.length(); i++) {
            JSONObject service = services.optJSONObject(i);
            if (service == null) continue;
            String value = service.optString(key, "").trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
    }

    private static boolean isNetworkVendor(String vendor) {
        String value = vendor == null ? "" : vendor.toUpperCase(Locale.ROOT);
        return value.contains("NETLINK") || value.contains("TP-LINK") || value.contains("D-LINK")
                || value.contains("CISCO") || value.contains("NETGEAR") || value.contains("ASUSTEK")
                || value.contains("MIKROTIK") || value.contains("UBIQUITI") || value.contains("HUAWEI")
                || value.contains("ARRIS");
    }
}
