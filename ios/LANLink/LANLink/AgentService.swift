import Foundation
import SwiftUI

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

    @Published var androidHost: String
    @Published var portText: String
    @Published var controllerToken: String

    @Published private(set) var isConnected = false
    @Published private(set) var isRunning = false
    @Published private(set) var isScanning = false
    @Published private(set) var bridgeAddress = ""
    @Published private(set) var subnet = ""
    @Published private(set) var devices: [ControllerDevice] = []
    @Published private(set) var activity: [ActivityEntry] = []
    @Published private(set) var lastRelayContact: Date?
    @Published var statusMessage = "Connect to your Android LANLink agent."

    private let session: URLSession

    init() {
        androidHost = UserDefaults.standard.string(forKey: "androidHost") ?? ""
        portText = UserDefaults.standard.string(forKey: "androidPort") ?? Self.defaultPort
        controllerToken = SecureStore.read(account: "android-controller-token") ?? ""
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 35
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        session = URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
    }

    @discardableResult
    func saveSettings() -> Bool {
        let host = androidHost.trimmingCharacters(in: .whitespacesAndNewlines)
        let portValue = portText.trimmingCharacters(in: .whitespacesAndNewlines)
        let token = controllerToken.trimmingCharacters(in: .whitespacesAndNewlines)

        guard Self.isPrivateIPv4(host) else {
            statusMessage = "Enter the Android TV's private IPv4 address, such as 192.168.1.20."
            return false
        }
        guard let port = Int(portValue), (1024...65535).contains(port) else {
            statusMessage = "Enter a valid controller port between 1024 and 65535."
            return false
        }
        guard token.count >= 32 else {
            statusMessage = "Paste the controller pairing key displayed by the Android app."
            return false
        }

        do {
            try SecureStore.save(token, account: "android-controller-token")
            UserDefaults.standard.set(host, forKey: "androidHost")
            UserDefaults.standard.set(String(port), forKey: "androidPort")
            androidHost = host
            portText = String(port)
            controllerToken = token
            statusMessage = "Android connection details saved securely."
            return true
        } catch {
            statusMessage = error.localizedDescription
            return false
        }
    }

    func connect() async {
        guard saveSettings() else { return }
        statusMessage = "Connecting to Android LANLink…"
        do {
            let status = try await request("/v1/status", method: "GET")
            isConnected = true
            isRunning = status["relay_running"] as? Bool ?? false
            bridgeAddress = status["bridge_ip"] as? String ?? androidHost
            subnet = status["subnet"] as? String ?? ""
            let last = status["last_relay_contact"] as? Double ?? 0
            lastRelayContact = last > 0 ? Date(timeIntervalSince1970: last / 1000) : nil
            let relayError = status["last_error"] as? String ?? ""
            statusMessage = relayError.isEmpty
                ? "Connected to Android LANLink at \(bridgeAddress)."
                : "Android connected. Relay: \(relayError)"
            await refreshPanels()
            addLocalActivity("Android connected", "Authenticated connection to \(bridgeAddress):\(portText).", failed: false)
        } catch {
            isConnected = false
            isRunning = false
            statusMessage = "Cannot reach Android agent: \(error.localizedDescription)"
        }
    }

    func disconnect() {
        isConnected = false
        isRunning = false
        statusMessage = "Controller disconnected. Android agent keeps running."
    }

    func refresh() async {
        guard isConnected else {
            await connect()
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
            let relayError = status["last_error"] as? String ?? ""
            statusMessage = relayError.isEmpty
                ? (isRunning ? "Android agent and ChatGPT relay are running." : "Android connected. ChatGPT relay is paused.")
                : "Android connected. Relay: \(relayError)"
            await refreshPanels()
        } catch {
            isConnected = false
            statusMessage = "Android connection lost: \(error.localizedDescription)"
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
                statusMessage = "ChatGPT relay paused. The Android controller API remains online."
                addLocalActivity("Relay paused", "Android stays available for iPhone control.", failed: false)
                await refreshPanels()
            } catch {
                statusMessage = "Could not pause Android relay: \(error.localizedDescription)"
            }
        }
    }

    func scanNow() async {
        guard isConnected else {
            statusMessage = "Connect to Android first."
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
        Task { await refresh() }
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

    private func request(_ path: String, method: String, body: [String: Any]? = nil) async throws -> [String: Any] {
        guard let port = Int(portText),
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
        request.setValue("Bearer \(controllerToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ControllerError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let detail = String(data: Data(data.prefix(700)), encoding: .utf8) ?? "Android control API returned HTTP \(http.statusCode)."
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
            case .invalidAddress: return "Enter a valid private Android TV IP address and port."
            case .invalidResponse: return "Android returned an invalid response."
            case .server(let detail): return detail
            }
        }
    }
}
