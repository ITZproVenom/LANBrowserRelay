import Foundation
import SwiftUI
import Security

struct ControllerDevice: Identifiable, Hashable {
    let ip: String
    let hostname: String?
    let mac: String?
    let services: [String]
    let discoverySource: String
    let tcpConfirmed: Bool
    let deviceType: String
    let vendor: String?
    let manufacturer: String?
    let model: String?
    let friendlyName: String?
    let identityConfidence: String
    let identityMethod: String
    let hostnameSource: String?
    let macAddressType: String
    let vendorLookupSource: String
    let identityEvidence: [String]
    let serviceFingerprints: [String]
    var id: String { ip }
}

struct ActivityEntry: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let date: Date
    let failed: Bool
}

struct AgentLogEntry: Identifiable {
    let id: String
    let timestamp: Date
    let level: String
    let event: String
    let message: String
    let details: String
}

private final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@MainActor
final class AgentService: ObservableObject {
    static let defaultPort = "43821"
    private static let candidatePorts: [UInt16] = [43822, 43821]

    @Published private(set) var isConnected = false
    @Published private(set) var isRunning = false
    @Published private(set) var isScanning = false
    @Published private(set) var isRelayConfigured = false
    @Published private(set) var isProvisioningRelay = false
    @Published private(set) var hasPendingRelayCredential = false
    @Published private(set) var androidHost = ""
    @Published private(set) var portText = defaultPort
    @Published private(set) var bridgeAddress = ""
    @Published private(set) var subnet = ""
    @Published private(set) var devices: [ControllerDevice] = []
    @Published private(set) var activity: [ActivityEntry] = []
    @Published private(set) var logs: [AgentLogEntry] = []
    @Published private(set) var scanSummary = ""
    @Published private(set) var lastRelayContact: Date?
    @Published private(set) var discoveredName = ""
    @Published var statusMessage = "Searching for LANLink on your local network…"

    private let session: URLSession
    private var discoveryTask: Task<Void, Never>?
    private let controllerToken: String
    private static let relayEnrollmentURL = URL(string: "https://odihwrrcgchtqohrgtdb.supabase.co/functions/v1/lanlink-mcp/agent/enroll")!

    init() {
        hasPendingRelayCredential = SecureStore.read(account: "relay-agent-token-pending") != nil
        let savedToken = SecureStore.read(account: "android-controller-token-v2")
        if let savedToken, savedToken.count >= 40 {
            controllerToken = savedToken
        } else {
            controllerToken = Self.makeControllerToken()
            try? SecureStore.save(controllerToken, account: "android-controller-token-v2")
        }

        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 35
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        session = URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
    }

    func startDiscovery() {
        stopDiscovery()
        isConnected = false
        statusMessage = "Searching this Wi-Fi network for the LANLink TV agent…"

        discoveryTask = Task { [weak self] in
            guard let self else { return }
            await self.runDirectDiscovery()
        }
    }

    func stopDiscovery() {
        discoveryTask?.cancel()
        discoveryTask = nil
    }

    /// Bonjour is intentionally not used here. LiveContainer runs guest apps
    /// under its own process identity, so guest NSBonjourServices declarations
    /// are not merged into LiveContainer's allowlist. Plain TCP is supported.
    private func runDirectDiscovery() async {
        guard let interface = LocalNetworkAgent.interfaceInfo(),
              let localAddress = Self.ipv4HostOrder(interface.address) else {
            statusMessage = "Connect the iPhone to the same private Wi-Fi network as the Android TV, then search again."
            discoveryTask = nil
            return
        }

        let network = localAddress & interface.scanMask
        let broadcast = network | ~interface.scanMask
        guard broadcast > network + 1 else {
            statusMessage = "The current Wi-Fi network has no scannable host addresses."
            discoveryTask = nil
            return
        }

        let addresses = ((network + 1)..<broadcast)
            .filter { $0 != localAddress }
            .map(Self.ipv4String)

        let prefix = interface.scanMask.nonzeroBitCount
        subnet = "\(interface.address)/\(prefix)"
        statusMessage = "Checking the local subnet for the LANLink TV agent…"

        for batchStart in stride(from: 0, to: addresses.count, by: 32) {
            if Task.isCancelled { return }
            let batch = Array(addresses[batchStart..<min(batchStart + 32, addresses.count)])

            let candidate = await withTaskGroup(of: (String, UInt16)?.self) { group in
                for host in batch {
                    group.addTask { [weak self] in
                        guard let self,
                              let port = await self.isLANLinkAgent(at: host) else { return nil }
                        return (host, port)
                    }
                }

                var match: (String, UInt16)?
                for await result in group {
                    if match == nil, let result {
                        match = result
                        group.cancelAll()
                    }
                }
                return match
            }

            if Task.isCancelled { return }
            if let candidate {
                discoveredName = "LANLink TV Agent"
                discoveryTask = nil
                await pairAndConnect(
                    host: candidate.0,
                    port: candidate.1,
                    displayName: discoveredName
                )
                return
            }
        }

        discoveryTask = nil
        statusMessage = "No LANLink TV agent found. Make sure the TV agent is running and both devices are on the same Wi-Fi network."
    }

    /// The LANLink TV API returns this exact unauthenticated response on its
    /// private control ports. Prefer the debug port so a new test build can
    /// coexist with a previously installed release agent.
    private func isLANLinkAgent(at host: String) async -> UInt16? {
        guard Self.isPrivateIPv4(host) else { return nil }

        for port in Self.candidatePorts {
            guard let url = URL(string: "http://\(host):\(port)/v1/status") else { continue }
            var probe = URLRequest(url: url)
            probe.httpMethod = "GET"
            probe.timeoutInterval = 1.2
            probe.cachePolicy = .reloadIgnoringLocalCacheData
            probe.setValue("application/json", forHTTPHeaderField: "Accept")

            do {
                let (data, response) = try await session.data(for: probe)
                guard let http = response as? HTTPURLResponse,
                      http.statusCode == 401,
                      let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      payload["error"] as? String == "Invalid controller token." else {
                    continue
                }
                return port
            } catch {
                continue
            }
        }
        return nil
    }

    private static func ipv4HostOrder(_ value: String) -> UInt32? {
        let parts = value.split(separator: ".")
        guard parts.count == 4 else { return nil }
        let octets = parts.compactMap { UInt8($0) }
        guard octets.count == 4 else { return nil }
        return (UInt32(octets[0]) << 24)
            | (UInt32(octets[1]) << 16)
            | (UInt32(octets[2]) << 8)
            | UInt32(octets[3])
    }

    private static func ipv4String(_ value: UInt32) -> String {
        "\((value >> 24) & 0xFF).\((value >> 16) & 0xFF).\((value >> 8) & 0xFF).\(value & 0xFF)"
    }

    private func pairAndConnect(host: String, port: UInt16, displayName: String) async {
        guard Self.isPrivateIPv4(host) else {
            statusMessage = "LANLink advertised an address outside the private LAN. Ignoring it."
            return
        }

        androidHost = host
        portText = String(port)
        UserDefaults.standard.set(host, forKey: "androidHost")
        UserDefaults.standard.set(portText, forKey: "androidPort")
        statusMessage = "Securely enrolling with \(displayName)…"

        do {
            // First contact creates a per-install controller key in Keychain.
            // The Android agent accepts it only while its controller slot is empty.
            _ = try await request("/v1/pair", method: "POST", body: [
                "controller_token": controllerToken,
                "controller": "LANLink iOS"
            ], authenticated: false)

            let status = try await request("/v1/status", method: "GET")
            isConnected = true
            isRunning = status["relay_running"] as? Bool ?? false
            isRelayConfigured = status["relay_configured"] as? Bool ?? isRunning
            bridgeAddress = status["bridge_ip"] as? String ?? host
            subnet = status["subnet"] as? String ?? ""
            let last = status["last_relay_contact"] as? Double ?? 0
            lastRelayContact = last > 0 ? Date(timeIntervalSince1970: last / 1000) : nil

            statusMessage = isRunning
                ? "Connected to \(displayName). ChatGPT relay is running."
                : (isRelayConfigured
                    ? "Connected to \(displayName). ChatGPT relay is paused."
                    : "Connected to \(displayName). Activate the ChatGPT relay once in Settings; local control already works.")
            stopDiscovery()
            await refreshPanels()
            addLocalActivity("Android connected", "Automatically discovered \(displayName) at \(host):\(port).", failed: false)
        } catch {
            isConnected = false
            statusMessage = "Found \(displayName), but connection failed: \(error.localizedDescription)"
            // The dashboard's Search button can retry discovery.
        }
    }

    func connect() async {
        if isConnected {
            await refresh()
        } else {
            startDiscovery()
        }
    }

    func disconnect() {
        isConnected = false
        isRunning = false
        statusMessage = "Controller disconnected. Searching can be started again from Overview."
        startDiscovery()
    }

    func refresh() async {
        guard isConnected else {
            startDiscovery()
            return
        }
        do {
            let status = try await request("/v1/status", method: "GET")
            isConnected = true
            isRunning = status["relay_running"] as? Bool ?? false
            isRelayConfigured = status["relay_configured"] as? Bool ?? isRunning
            bridgeAddress = status["bridge_ip"] as? String ?? androidHost
            subnet = status["subnet"] as? String ?? subnet
            let last = status["last_relay_contact"] as? Double ?? 0
            lastRelayContact = last > 0 ? Date(timeIntervalSince1970: last / 1000) : nil
            statusMessage = isRunning
                ? "Android agent and ChatGPT relay are running."
                : (isRelayConfigured
                    ? "Android connected. ChatGPT relay is paused."
                    : "Android connected. Activate the ChatGPT relay once in Settings; local device control works.")
            await refreshPanels()
        } catch {
            isConnected = false
            statusMessage = "Android connection lost: \(error.localizedDescription)"
            startDiscovery()
        }
    }

    func start() {
        guard isRelayConfigured else {
            statusMessage = "ChatGPT relay needs one-time activation. Open Settings to provision this TV."
            return
        }
        Task {
            do {
                let result = try await request("/v1/bridge/start", method: "POST", body: [:])
                isRunning = result["relay_running"] as? Bool ?? false
                if isRunning {
                    statusMessage = "ChatGPT relay started on Android."
                    addLocalActivity("Relay started", "Android agent is polling the LANLink relay.", failed: false)
                } else {
                    statusMessage = result["message"] as? String ?? "Android could not start ChatGPT relay."
                    addLocalActivity("Relay start failed", statusMessage, failed: true)
                }
                await refresh()
            } catch {
                statusMessage = "Could not start Android relay: \(error.localizedDescription)"
            }
        }
    }

    func provisionRelay(code: String) async {
        guard isConnected else {
            statusMessage = "Connect to the Android TV before activating the ChatGPT relay."
            return
        }
        guard !isProvisioningRelay else { return }
        isProvisioningRelay = true
        defer { isProvisioningRelay = false }

        do {
            var relayToken = SecureStore.read(account: "relay-agent-token-pending")
            if relayToken == nil {
                let activationCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
                guard activationCode.count == 64 && activationCode.allSatisfy({ $0.isHexDigit }) else {
                    throw ControllerError.server("Enter the 64-character one-time activation code.")
                }

                var enrollment = URLRequest(url: Self.relayEnrollmentURL)
                enrollment.httpMethod = "POST"
                enrollment.timeoutInterval = 20
                enrollment.setValue("application/json", forHTTPHeaderField: "Accept")
                enrollment.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
                enrollment.httpBody = try JSONSerialization.data(withJSONObject: ["code": activationCode])

                let (data, response) = try await session.data(for: enrollment)
                guard let http = response as? HTTPURLResponse else {
                    throw ControllerError.invalidResponse
                }
                guard (200..<300).contains(http.statusCode) else {
                    let detail = String(data: Data(data.prefix(500)), encoding: .utf8)
                        ?? "Relay enrollment failed (HTTP \(http.statusCode))."
                    throw ControllerError.server(detail)
                }
                let payload = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
                guard let issued = payload["agent_token"] as? String, issued.count >= 40 else {
                    throw ControllerError.server("Relay enrollment returned no valid agent credential.")
                }
                try SecureStore.save(issued, account: "relay-agent-token-pending")
                hasPendingRelayCredential = true
                relayToken = issued
            }

            guard let relayToken else {
                throw ControllerError.server("No relay activation credential is available.")
            }
            statusMessage = "Installing the relay identity on Android TV…"
            let installed = try await request("/v1/relay/credential", method: "POST", body: [
                "agent_token": relayToken
            ])
            isRelayConfigured = installed["relay_configured"] as? Bool ?? true
            isRunning = installed["relay_running"] as? Bool ?? false
            guard isRelayConfigured else {
                throw ControllerError.server(installed["message"] as? String ?? "Android did not save the relay credential.")
            }

            SecureStore.delete(account: "relay-agent-token-pending")
            hasPendingRelayCredential = false
            statusMessage = isRunning
                ? "ChatGPT relay identity installed. Waiting for its first secure heartbeat…"
                : "Relay identity saved. Tap Start ChatGPT relay to begin polling."
            addLocalActivity("ChatGPT relay provisioned", "The TV received a one-time enrolled relay identity.", failed: false)
            await refresh()
        } catch {
            statusMessage = "Relay activation failed: \(error.localizedDescription)"
            addLocalActivity("Relay activation failed", error.localizedDescription, failed: true)
        }
    }

    func handleIncomingURL(_ url: URL) {
        guard url.scheme == "lanlink", url.host == "provision",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let code = components.queryItems?.first(where: { $0.name == "code" })?.value else {
            return
        }
        Task { await provisionRelay(code: code) }
    }

    func stop() {
        Task {
            do {
                _ = try await request("/v1/bridge/stop", method: "POST", body: [:])
                isRunning = false
                statusMessage = "ChatGPT relay paused. Android remains available for local control."
                addLocalActivity("Relay paused", "Android stays available for iPhone control.", failed: false)
                await refreshPanels()
            } catch {
                statusMessage = "Could not pause Android relay: \(error.localizedDescription)"
            }
        }
    }

    func scanNow() async {
        guard isConnected else {
            statusMessage = "Waiting for Android LANLink…"
            startDiscovery()
            return
        }
        guard !isScanning else { return }
        isScanning = true
        statusMessage = "Android is scanning its local subnet…"
        defer { isScanning = false }
        do {
            let result = try await request("/v1/scan", method: "POST", body: [:])
            devices = Self.parseDevices(result["devices"] as? [[String: Any]] ?? [])
            subnet = result["subnet"] as? String ?? subnet
            let total = result["device_count"] as? Int ?? devices.count
            let tcp = result["tcp_confirmed_count"] as? Int ?? 0
            let arp = result["arp_only_count"] as? Int ?? max(0, total - tcp)
            let preexisting = result["arp_preexisting_count"] as? Int ?? 0
            let learned = result["arp_new_count"] as? Int ?? 0
            let timedOut = result["hosts_timed_out"] as? Int ?? 0
            let duration = result["duration_ms"] as? Int ?? 0
            scanSummary = "\(total) observed · 1 TV agent · \(tcp) TCP-confirmed · \(arp) ARP-only (\(preexisting) pre-existing, \(learned) learned) · \(timedOut) timed out · \(duration) ms"
            statusMessage = "Android observed \(total) entries on \(subnet): \(tcp) TCP-confirmed and \(arp) ARP-only."
            addLocalActivity("LAN scan complete", scanSummary, failed: false)
            await refreshPanels()
        } catch {
            statusMessage = "Android scan failed: \(error.localizedDescription)"
            addLocalActivity("LAN scan failed", error.localizedDescription, failed: true)
        }
    }

    func probe(_ device: ControllerDevice) {
        runCommand("probe", payload: ["ip": device.ip])
    }

    func wake(_ device: ControllerDevice) {
        guard let mac = device.mac, !mac.isEmpty else {
            statusMessage = "This device has no MAC address in Android's ARP cache."
            return
        }
        runCommand("wol", payload: ["mac": mac])
    }

    func sendHTTP(ip: String, port: Int, scheme: String, method: String, path: String) {
        runCommand("http", payload: [
            "ip": ip, "port": port, "scheme": scheme, "method": method, "path": path
        ])
    }

    func handleScenePhase(_ phase: ScenePhase) {
        guard phase == .active else { return }
        Task {
            if isConnected { await refresh() }
            else { startDiscovery() }
        }
    }

    private func runCommand(_ kind: String, payload: [String: Any]) {
        Task {
            do {
                let result = try await request("/v1/command", method: "POST", body: [
                    "kind": kind,
                    "payload": payload
                ])
                statusMessage = "\(kind.uppercased()) completed on Android."
                addLocalActivity("\(kind.uppercased()) completed", resultSummary(result), failed: false)
                await refreshPanels()
            } catch {
                statusMessage = "\(kind.uppercased()) failed: \(error.localizedDescription)"
                addLocalActivity("\(kind.uppercased()) failed", error.localizedDescription, failed: true)
            }
        }
    }

    private func refreshPanels() async {
        async let devicesResult = request("/v1/devices", method: "GET")
        async let activityResult = request("/v1/activity", method: "GET")
        async let logsResult = request("/v1/logs", method: "GET")
        do {
            let (deviceObject, activityObject, logsObject) = try await (devicesResult, activityResult, logsResult)
            devices = Self.parseDevices(deviceObject["devices"] as? [[String: Any]] ?? [])
            let total = deviceObject["device_count"] as? Int ?? devices.count
            let tcp = deviceObject["tcp_confirmed_count"] as? Int ?? 0
            let arp = deviceObject["arp_only_count"] as? Int ?? max(0, total - tcp)
            if total > 0 {
                let preexisting = deviceObject["arp_preexisting_count"] as? Int ?? 0
                let learned = deviceObject["arp_new_count"] as? Int ?? 0
                let timedOut = deviceObject["hosts_timed_out"] as? Int ?? 0
                let duration = deviceObject["duration_ms"] as? Int ?? 0
                scanSummary = "\(total) observed · \(tcp) TCP-confirmed · \(arp) ARP-only (\(preexisting) pre-existing, \(learned) learned) · \(timedOut) timed out · \(duration) ms"
            }
            let entries = activityObject["events"] as? [[String: Any]] ?? []
            activity = entries.compactMap { entry in
                let title = entry["title"] as? String ?? "Android event"
                let detail = entry["detail"] as? String ?? ""
                let milliseconds = entry["timestamp"] as? Double ?? 0
                return ActivityEntry(
                    title: title,
                    detail: detail,
                    date: Date(timeIntervalSince1970: milliseconds / 1000),
                    failed: entry["failed"] as? Bool ?? false
                )
            }
            logs = Self.parseLogs(logsObject["logs"] as? [[String: Any]] ?? [])
        } catch {
            statusMessage = "Android connected, but a dashboard panel failed: \(error.localizedDescription)"
        }
    }

    func exportDiagnostics() -> String {
        let header = "LANLink diagnostics\nTV: \(androidHost):\(portText)\nSubnet: \(subnet)\nExported: \(Date().formatted())\n"
        let entries = logs.map { entry in
            let stamp = entry.timestamp.formatted(date: .numeric, time: .standard)
            return "[\(stamp)] [\(entry.level)] \(entry.event): \(entry.message)\n\(entry.details)"
        }
        return header + "\n" + entries.joined(separator: "\n\n")
    }

    private static func parseLogs(_ raw: [[String: Any]]) -> [AgentLogEntry] {
        raw.compactMap { item in
            guard let id = item["id"] as? String else { return nil }
            let milliseconds = item["timestamp"] as? Double ?? 0
            let detailsObject = item["details"] as? [String: Any] ?? [:]
            let detailsData = (try? JSONSerialization.data(withJSONObject: detailsObject, options: [.sortedKeys]))
            let details = detailsData.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
            return AgentLogEntry(
                id: id,
                timestamp: Date(timeIntervalSince1970: milliseconds / 1000),
                level: item["level"] as? String ?? "INFO",
                event: item["event"] as? String ?? "unknown",
                message: item["message"] as? String ?? "",
                details: details
            )
        }.suffix(200).reversed().map { $0 }
    }

    private func request(
        _ path: String,
        method: String,
        body: [String: Any]? = nil,
        authenticated: Bool = true
    ) async throws -> [String: Any] {
        guard !androidHost.isEmpty,
              let port = Int(portText),
              var components = URLComponents(string: "http://\(androidHost):\(port)"),
              components.scheme == "http",
              Self.isPrivateIPv4(androidHost) else {
            throw ControllerError.invalidAddress
        }
        components.path = path
        guard let url = components.url else { throw ControllerError.invalidAddress }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 32
        if authenticated {
            request.setValue("Bearer \(controllerToken)", forHTTPHeaderField: "Authorization")
        }
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ControllerError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let detail = String(data: Data(data.prefix(700)), encoding: .utf8)
                ?? "Android control API returned HTTP \(http.statusCode)."
            throw ControllerError.server(detail)
        }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
    }

    private func addLocalActivity(_ title: String, _ detail: String, failed: Bool) {
        activity.insert(ActivityEntry(title: title, detail: detail, date: Date(), failed: failed), at: 0)
        if activity.count > 100 { activity.removeLast(activity.count - 100) }
    }

    private func resultSummary(_ result: [String: Any]) -> String {
        if let status = result["status"] { return "HTTP status \(status)" }
        if let mac = result["mac"] as? String { return "MAC \(mac)" }
        if let ip = result["ip"] as? String { return "Device \(ip)" }
        return "Android returned a result."
    }

    private static func parseDevices(_ raw: [[String: Any]]) -> [ControllerDevice] {
        raw.compactMap { item in
            guard let ip = item["ip"] as? String else { return nil }
            let rawServices = item["services"] as? [Any] ?? []
            let services = rawServices.compactMap { value -> String? in
                if let string = value as? String { return string }
                if let object = value as? [String: Any] {
                    let name = object["service"] as? String ?? "service"
                    let port = object["port"] as? Int
                    return port.map { "\(name):\($0)" } ?? name
                }
                return nil
            }
            let hostnameValue = item["hostname"] as? String
            let macValue = item["mac"] as? String
            return ControllerDevice(
                ip: ip,
                hostname: hostnameValue == "null" ? nil : hostnameValue,
                mac: macValue == "null" ? nil : macValue,
                services: services,
                discoverySource: item["discovery_source"] as? String ?? "unknown",
                tcpConfirmed: item["tcp_confirmed"] as? Bool ?? !services.isEmpty,
                deviceType: item["device_type"] as? String ?? "Unknown network device",
                vendor: item["device_vendor"] as? String,
                manufacturer: item["device_manufacturer"] as? String,
                model: item["device_model"] as? String,
                friendlyName: item["device_friendly_name"] as? String,
                identityConfidence: item["identity_confidence"] as? String ?? "low",
                identityMethod: item["identity_method"] as? String ?? "service_evidence_only",
                hostnameSource: item["hostname_source"] as? String,
                macAddressType: item["mac_address_type"] as? String ?? "unavailable",
                vendorLookupSource: item["vendor_lookup_source"] as? String ?? "unknown",
                identityEvidence: item["identity_evidence"] as? [String] ?? [],
                serviceFingerprints: item["service_fingerprints"] as? [String] ?? []
            )
        }.sorted { $0.ip.localizedStandardCompare($1.ip) == .orderedAscending }
    }

    private static func makeControllerToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else {
            return UUID().uuidString.replacingOccurrences(of: "-", with: "")
                + UUID().uuidString.replacingOccurrences(of: "-", with: "")
        }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func isPrivateIPv4(_ value: String) -> Bool {
        let octets = value.split(separator: ".").compactMap { UInt8($0) }
        guard octets.count == 4 else { return false }
        return octets[0] == 10
            || (octets[0] == 172 && (16...31).contains(octets[1]))
            || (octets[0] == 192 && octets[1] == 168)
    }

    enum ControllerError: LocalizedError {
        case invalidAddress
        case invalidResponse
        case server(String)

        var errorDescription: String? {
            switch self {
            case .invalidAddress: return "No valid private Android TV address was discovered."
            case .invalidResponse: return "Android returned an invalid response."
            case .server(let detail): return detail
            }
        }
    }
}
