import Foundation
import Network
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
    static let serviceType = "_lanlink._tcp"
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
    private var browser: NWBrowser?
    private var resolveConnections: [NWConnection] = []
    private var attemptedServices = Set<String>()
    private var isConnecting = false
    private let controllerToken: String

    init() {
        let savedToken = SecureStore.read(account: "android-controller-token")
        if let savedToken, savedToken.count >= 40 {
            controllerToken = savedToken
        } else {
            controllerToken = Self.makeControllerToken()
            try? SecureStore.save(controllerToken, account: "android-controller-token")
        }

        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 35
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        session = URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
    }

    func startDiscovery() {
        browser?.cancel()
        browser = nil
        resolveConnections.forEach { $0.cancel() }
        resolveConnections.removeAll()
        attemptedServices.removeAll()
        isConnecting = false
        statusMessage = "Searching for LANLink TV Agent on this Wi-Fi…"

        let parameters = NWParameters.tcp
        let newBrowser = NWBrowser(
            for: .bonjour(type: Self.serviceType, domain: nil),
            using: parameters
        )

        newBrowser.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                switch state {
                case .ready:
                    if !self.isConnected {
                        self.statusMessage = "Looking for an Android LANLink agent…"
                    }
                case .failed(let error):
                    self.statusMessage = "Local discovery failed: \(error.localizedDescription)"
                    self.browser = nil
                case .cancelled:
                    self.browser = nil
                default:
                    break
                }
            }
        }

        newBrowser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            for result in results {
                guard case let .service(name, _, _, _) = result.endpoint else { continue }
                let key = result.endpoint.debugDescription
                Task { @MainActor in
                    guard !self.isConnected,
                          !self.isConnecting,
                          self.attemptedServices.insert(key).inserted else { return }
                    self.discoveredName = name
                    self.resolveAndConnect(result.endpoint, displayName: name)
                }
            }
        }

        browser = newBrowser
        newBrowser.start(queue: .global(qos: .userInitiated))
    }

    func stopDiscovery() {
        browser?.cancel()
        browser = nil
        resolveConnections.forEach { $0.cancel() }
        resolveConnections.removeAll()
    }

    private func resolveAndConnect(_ endpoint: NWEndpoint, displayName: String) {
        guard !isConnecting else { return }
        isConnecting = true
        statusMessage = "Found \(displayName). Connecting…"

        let connection = NWConnection(to: endpoint, using: .tcp)
        resolveConnections.append(connection)
        connection.stateUpdateHandler = { [weak self] state in
            guard case .ready = state else {
                if case .failed = state {
                    Task { @MainActor in self?.isConnecting = false }
                }
                return
            }

            let remote = connection.currentPath?.remoteEndpoint
            connection.cancel()
            guard case let .hostPort(host, port) = remote else {
                Task { @MainActor in
                    self?.isConnecting = false
                    self?.statusMessage = "Found LANLink but couldn't resolve its local address."
                }
                return
            }

            let address = String(describing: host)
            let portNumber = port.rawValue
            Task { @MainActor in
                guard let self else { return }
                await self.pairAndConnect(host: address, port: portNumber, displayName: displayName)
            }
        }
        connection.start(queue: .global(qos: .userInitiated))
    }

    private func pairAndConnect(host: String, port: UInt16, displayName: String) async {
        defer { isConnecting = false }
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
            // Keep discovery alive so a reachable agent can be retried.
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
                _ = try await request("/v1/bridge/start", method: "POST", body: [:])
                isRunning = true
                statusMessage = "ChatGPT relay started on Android."
                addLocalActivity("Relay started", "Android agent is polling the LANLink relay.", failed: false)
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
