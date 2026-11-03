package com.itzprovenom.lanlinkagent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class DeviceIdentity {
    private static final Object LOCK = new Object();
    private static volatile Map<String, String> vendors;

    private DeviceIdentity() {}

    static void enrich(Context context, JSONObject device, String gatewayIp,
                       String hostname, String hostnameSource) {
        try {
            String ip = device.optString("ip", "");
            String mac = device.isNull("mac") ? "" : device.optString("mac", "");
            String vendor = vendorForMac(context, mac);
            String macType = macAddressType(mac);
            JSONArray services = device.optJSONArray("services");
            if (services == null) services = new JSONArray();

            String upnpManufacturer = firstServiceValue(services, "upnp_manufacturer");
            String upnpModel = firstServiceValue(services, "upnp_model");
            String friendlyName = firstServiceValue(services, "upnp_friendly_name");
            String serverProduct = firstServiceValue(services, "server");
            JSONArray evidence = new JSONArray();
            JSONArray fingerprints = new JSONArray();

            boolean isGateway = !gatewayIp.isEmpty() && gatewayIp.equals(ip);
            if (isGateway) evidence.put("Default gateway route points to this address.");
            if (!vendor.isEmpty()) evidence.put("MAC prefix registered to " + vendor + ".");
            if ("locally_administered".equals(macType)) evidence.put("MAC is locally administered; vendor lookup is not reliable.");
            if (hostname != null && !hostname.isEmpty()) {
                evidence.put("Hostname resolved through " + (hostnameSource == null ? "local name lookup" : hostnameSource) + ".");
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

            if (!upnpModel.isEmpty() || !upnpManufacturer.isEmpty()) {
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

            if (!serverProduct.isEmpty()) evidence.put("HTTP/RTSP server identifies as " + serverProduct + ".");
            device.put("hostname", hostname == null || hostname.isEmpty() ? JSONObject.NULL : hostname);
            device.put("hostname_source", hostnameSource == null || hostnameSource.isEmpty() ? JSONObject.NULL : hostnameSource);
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
            device.put("vendor_lookup_source", vendor.isEmpty() ? "not_found_in_bundled_oui_subset" : "bundled_oui_reference");
        } catch (Exception ignored) {
            // Identity enrichment must never fail the underlying LAN scan.
        }
    }

    static String vendorForMac(Context context, String mac) {
        String normalized = normalizeMac(mac);
        if (normalized.length() != 12) return "";
        if (isLocallyAdministered(normalized)) return "";

        Map<String, String> table = loadVendors(context);
        // IEEE MA-S/MA-M records are longer than the traditional 24-bit OUI.
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
