import Foundation
import SwiftUI
import Security

struct ControllerDevice: Identifiable, Hashable {
    let ip: String
    let hostname: String?
    let mac: String?
    let services: [String]
    var id: String { ip }
}

struct ActivityEntry: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let date: Date
    let failed: Bool
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

    @Published private(set) var isConnected = false
    @Published private(set) var isRunning = false
    @Published private(set) var isScanning = false
    @Published private(set) var androidHost = ""
    @Published private(set) var portText = defaultPort
    @Published private(set) var bridgeAddress = ""
    @Published private(set) var subnet = ""
    @Published private(set) var devices: [ControllerDevice] = []
    @Published private(set) var activity: [ActivityEntry] = []
    @Published private(set) var lastRelayContact: Date?
    @Published private(set) var discoveredName = ""
    @Published var statusMessage = "Searching for LANLink on your local network…"

    private let session: URLSession
    private var discoveryTask: Task<Void, Never>?
    private let controllerToken: String

    init() {
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

            let candidate = await withTaskGroup(of: String?.self) { group in
                for host in batch {
                    group.addTask { [weak self] in
                        guard let self,
                              await self.isLANLinkAgent(at: host) else { return nil }
                        return host
                    }
                }

                var match: String?
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
                    host: candidate,
                    port: UInt16(defaultPort) ?? 43821,
                    displayName: discoveredName
                )
                return
            }
        }

        discoveryTask = nil
        statusMessage = "No LANLink TV agent found. Make sure the TV agent is running and both devices are on the same Wi-Fi network."
    }

    /// The agent returns this exact unauthenticated response for /v1/status.
    /// Requiring it avoids pairing with an unrelated device that happens to
    /// have TCP port 43821 open.
    private func isLANLinkAgent(at host: String) async -> Bool {
        guard Self.isPrivateIPv4(host),
              let url = URL(string: "http://\(host):\(Self.defaultPort)/v1/status") else {
            return false
        }

        var probe = URLRequest(url: url)
        probe.httpMethod = "GET"
        probe.timeoutInterval = 1.2
        probe.cachePolicy = .reloadIgnoringLocalCacheData
        probe.setValue("application/json", forHTTPHeaderField: "Accept")

        do {
            let (data, response) = try await session.data(for: probe)
            guard let http = response as? HTTPURLResponse,
                  http.statusCode == 401,
                  let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return false
            }
            return payload["error"] as? String == "Invalid controller token."
        } catch {
            return false
        }
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
            bridgeAddress = status["bridge_ip"] as? String ?? host
            subnet = status["subnet"] as? String ?? ""
            let last = status["last_relay_contact"] as? Double ?? 0
            lastRelayContact = last > 0 ? Date(timeIntervalSince1970: last / 1000) : nil

            statusMessage = isRunning
                ? "Connected to \(displayName). Android relay is running."
                : "Connected to \(displayName). Android is ready; ChatGPT relay is not active."
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
            bridgeAddress = status["bridge_ip"] as? String ?? androidHost
            subnet = status["subnet"] as? String ?? subnet
            let last = status["last_relay_contact"] as? Double ?? 0
            lastRelayContact = last > 0 ? Date(timeIntervalSince1970: last / 1000) : nil
            statusMessage = isRunning
                ? "Android agent and ChatGPT relay are running."
                : "Android connected. ChatGPT relay is paused or not provisioned."
            await refreshPanels()
        } catch {
            isConnected = false
            statusMessage = "Android connection lost: \(error.localizedDescription)"
            startDiscovery()
        }
    }

    func start() {
        Task {
            do {
                let result = try await request("/v1/bridge/start", method: "POST", body: [:])
                isRunning = result["relay_running"] as? Bool ?? false
                if isRunning {
                    statusMessage = "ChatGPT relay started on Android."
                    addLocalActivity("Relay started", "Android agent is polling the LANLink relay.", failed: false)
                } else {
                    statusMessage = "Android is connected, but no ChatGPT relay credential is configured. Local device control still works."
                    addLocalActivity("Relay not configured", "The Android agent needs a relay identity before ChatGPT can send queued commands.", failed: true)
                }
                await refresh()
            } catch {
                statusMessage = "Could not start Android relay: \(error.localizedDescription)"
            }
        }
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
            statusMessage = "Android discovered \(devices.count) device(s) on \(subnet)."
            addLocalActivity("LAN scan complete", "\(devices.count) devices reported by Android.", failed: false)
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
        do {
            let (deviceObject, activityObject) = try await (devicesResult, activityResult)
            devices = Self.parseDevices(deviceObject["devices"] as? [[String: Any]] ?? [])
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
        } catch {
            statusMessage = "Android connected, but a dashboard panel failed: \(error.localizedDescription)"
        }
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
                services: services
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
