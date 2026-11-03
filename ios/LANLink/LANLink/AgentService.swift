import Foundation
import SwiftUI

struct ActivityEntry: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let date = Date()
    let failed: Bool
}

@MainActor
final class AgentService: ObservableObject {
    static let defaultRelay = "https://odihwrrcgchtqohrgtdb.supabase.co/functions/v1/lanlink-mcp"

    @Published var relayEndpoint: String
    @Published var agentToken: String
    @Published private(set) var isRunning = false
    @Published private(set) var isScanning = false
    @Published private(set) var localAddress: String
    @Published private(set) var devices: [LANDevice] = []
    @Published private(set) var activity: [ActivityEntry] = []
    @Published private(set) var lastContact: Date?
    @Published var statusMessage = "Set your agent token to connect LANLink."

    private var pollTask: Task<Void, Never>?

    init() {
        relayEndpoint = UserDefaults.standard.string(forKey: "relayEndpoint") ?? Self.defaultRelay
        agentToken = SecureStore.read(account: "agent-token") ?? ""
        localAddress = LocalNetworkAgent.interfaceInfo()?.address ?? "Not connected"
    }

    func saveSettings() {
        let endpoint = relayEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: endpoint), url.scheme == "https", url.host != nil,
              url.path.hasSuffix("/functions/v1/lanlink-mcp") else {
            statusMessage = "Use the HTTPS LANLink relay endpoint."
            return
        }
        do {
            try SecureStore.save(agentToken.trimmingCharacters(in: .whitespacesAndNewlines), account: "agent-token")
            UserDefaults.standard.set(endpoint, forKey: "relayEndpoint")
            relayEndpoint = endpoint
            statusMessage = "Settings saved securely."
            addActivity("Settings saved", "Relay endpoint and Keychain token updated.", failed: false)
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    func start() {
        guard !agentToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            statusMessage = "Add the iPhone agent token in Settings first."
            return
        }
        guard LocalNetworkAgent.interfaceInfo() != nil else {
            statusMessage = "Connect to your private Wi-Fi first."
            return
        }
        guard !isRunning else { return }

        isRunning = true
        statusMessage = "Bridge active while LANLink stays open."
        localAddress = LocalNetworkAgent.interfaceInfo()?.address ?? "Not connected"
        addActivity("Bridge started", "This iPhone is now polling the LANLink relay.", failed: false)
        pollTask = Task {
            while !Task.isCancelled {
                do {
                    try await heartbeat()
                    let commands = try await pollCommands()
                    lastContact = Date()
                    for command in commands {
                        if Task.isCancelled { break }
                        await process(command)
                    }
                } catch {
                    statusMessage = "Relay: \(error.localizedDescription)"
                    addActivity("Relay connection failed", error.localizedDescription, failed: true)
                }
                try? await Task.sleep(nanoseconds: 4_000_000_000)
            }
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
        if isRunning {
            addActivity("Bridge stopped", "Relay polling has stopped.", failed: false)
        }
        isRunning = false
        statusMessage = "Bridge stopped."
    }

    func handleScenePhase(_ phase: ScenePhase) {
        if phase != .active && isRunning {
            stop()
            statusMessage = "Paused because iOS moved LANLink to the background. Reopen the app to resume."
        }
    }

    func scanNow() async {
        guard !isScanning else { return }
        isScanning = true
        statusMessage = "Scanning the current private subnet…"
        defer { isScanning = false }
        do {
            let result = try await LocalNetworkAgent.discover()
            devices = result
            localAddress = LocalNetworkAgent.interfaceInfo()?.address ?? "Not connected"
            statusMessage = "Found \(result.count) device(s) with discoverable services."
            addActivity("LAN scan complete", "\(result.count) device(s) responded on common service ports.", failed: false)
        } catch {
            statusMessage = error.localizedDescription
            addActivity("LAN scan failed", error.localizedDescription, failed: true)
        }
    }

    private func heartbeat() async throws {
        _ = try await relayRequest("agent/heartbeat", method: "POST")
    }

    private func pollCommands() async throws -> [[String: Any]] {
        let data = try await relayRequest("agent/poll", method: "GET")
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return object?["commands"] as? [[String: Any]] ?? []
    }

    private func process(_ command: [String: Any]) async {
        guard let id = command["id"] as? String,
              let kind = command["kind"] as? String,
              let payload = command["payload"] as? [String: Any] else { return }
        do {
            let result = try await execute(kind: kind, payload: payload)
            try await report(commandID: id, result: result, error: nil, devices: kind == "discover" ? devices : nil)
            addActivity("Command completed", kind.uppercased(), failed: false)
        } catch {
            try? await report(commandID: id, result: nil, error: error.localizedDescription, devices: nil)
            addActivity("Command failed", "\(kind.uppercased()): \(error.localizedDescription)", failed: true)
        }
    }

    private func execute(kind: String, payload: [String: Any]) async throws -> [String: Any] {
        switch kind {
        case "discover":
            let result = try await LocalNetworkAgent.discover()
            devices = result
            return ["count": result.count, "devices": result.map { ["ip": $0.ip, "services": $0.services] }]
        case "probe":
            guard let ip = payload["ip"] as? String else { throw AgentError.invalidCommand }
            let services = try await LocalNetworkAgent.probe(ip)
            return ["ip": ip, "services": services]
        case "http":
            return try await performHTTP(payload)
        case "wol":
            guard let mac = payload["mac"] as? String else { throw AgentError.invalidCommand }
            try LocalNetworkAgent.sendWakePacket(mac: mac, broadcast: payload["broadcast"] as? String)
            return ["sent": true, "mac": mac]
        default:
            throw AgentError.unsupportedCommand(kind)
        }
    }

    private func performHTTP(_ payload: [String: Any]) async throws -> [String: Any] {
        guard let ip = payload["ip"] as? String,
              let port = payload["port"] as? Int,
              let scheme = payload["scheme"] as? String,
              let method = payload["method"] as? String,
              let path = payload["path"] as? String,
              LocalNetworkAgent.isPrivateIPv4(ip),
              (1...65535).contains(port),
              ["http", "https"].contains(scheme),
              ["GET", "HEAD", "POST", "PUT", "DELETE"].contains(method),
              path.hasPrefix("/"), !path.hasPrefix("//"), !path.contains("\\"),
              !path.split(separator: "/").contains(".."),
              !path.contains("\r"), !path.contains("\n") else {
            throw AgentError.invalidCommand
        }
        guard let local = LocalNetworkAgent.interfaceInfo(), isSameSubnet(ip, localAddress: local.address) else {
            throw LocalNetworkAgent.NetworkError.targetOutsideSubnet
        }

        let body = payload["body"] as? String ?? ""
        guard body.utf8.count <= 16_384 else { throw AgentError.bodyTooLarge }
        var components = URLComponents()
        components.scheme = scheme
        components.host = ip
        components.port = port
        components.percentEncodedPath = path
        guard let url = components.url else { throw AgentError.invalidCommand }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 8
        if !body.isEmpty {
            request.httpBody = Data(body.utf8)
            request.setValue(payload["content_type"] as? String ?? "application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as? HTTPURLResponse
        let preview = String(data: data.prefix(24_000), encoding: .utf8) ?? "<binary response>"
        return ["status": http?.statusCode ?? 0, "body": preview, "truncated": data.count > 24_000]
    }

    private func isSameSubnet(_ ip: String, localAddress: String) -> Bool {
        guard let target = ipv4HostOrder(ip), let local = ipv4HostOrder(localAddress),
              let info = LocalNetworkAgent.interfaceInfo() else { return false }
        return (target & info.mask) == (local & info.mask)
    }

    private func ipv4HostOrder(_ value: String) -> UInt32? {
        var address = in_addr()
        guard value.withCString({ inet_pton(AF_INET, $0, &address) }) == 1 else { return nil }
        return UInt32(bigEndian: address.s_addr)
    }

    private func report(commandID: String, result: [String: Any]?, error: String?, devices: [LANDevice]?) async throws {
        var body: [String: Any] = ["command_id": commandID]
        if let result { body["result"] = result }
        if let error { body["error"] = error }
        if let devices {
            body["devices"] = devices.map { device -> [String: Any] in
                ["ip": device.ip, "hostname": device.hostname as Any? ?? NSNull(), "services": device.services]
            }
        }
        _ = try await relayRequest("agent/report", method: "POST", body: body)
    }

    private func relayRequest(_ path: String, method: String, body: [String: Any]? = nil) async throws -> Data {
        guard let base = URL(string: relayEndpoint),
              var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            throw AgentError.invalidRelay
        }
        components.path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path
        guard let url = components.url else { throw AgentError.invalidRelay }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 12
        request.setValue("Bearer \(agentToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let message = String(data: data.prefix(1200), encoding: .utf8) ?? "Relay request failed."
            throw AgentError.relay(message)
        }
        return data
    }

    private func addActivity(_ title: String, _ detail: String, failed: Bool) {
        activity.insert(ActivityEntry(title: title, detail: detail, failed: failed), at: 0)
        if activity.count > 100 { activity.removeLast(activity.count - 100) }
    }

    enum AgentError: LocalizedError {
        case invalidRelay
        case invalidCommand
        case bodyTooLarge
        case relay(String)
        case unsupportedCommand(String)

        var errorDescription: String? {
            switch self {
            case .invalidRelay: return "Relay URL is invalid."
            case .invalidCommand: return "The relay sent an invalid or unsafe command."
            case .bodyTooLarge: return "The request body exceeds 16 KB."
            case .relay(let message): return "Relay error: \(message)"
            case .unsupportedCommand(let kind): return "Unsupported command type: \(kind)."
            }
        }
    }
}
