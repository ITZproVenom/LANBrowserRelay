import Foundation
import SwiftUI
import AVFoundation
import CoreLocation
import Darwin
import UIKit

/// Keeps a silent playback session active only while LANLink's bridge is running.
/// This is an explicit, user-controllable background playback mode.
@MainActor
final class BackgroundAudioKeepAlive {
    static let shared = BackgroundAudioKeepAlive()

    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var healthTimer: Timer?

    private init() {}

    func start() {
        if engine?.isRunning == true, player?.isPlaying == true { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)

            let newEngine = AVAudioEngine()
            let newPlayer = AVAudioPlayerNode()
            let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!

            newEngine.attach(newPlayer)
            newEngine.connect(newPlayer, to: newEngine.mainMixerNode, format: format)

            let frameCount = AVAudioFrameCount(format.sampleRate)
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount),
                  let channels = buffer.floatChannelData else {
                throw KeepAliveError.cannotCreateSilentBuffer
            }
            buffer.frameLength = frameCount
            memset(channels[0], 0, Int(frameCount) * MemoryLayout<Float>.size)

            newPlayer.scheduleBuffer(buffer, at: nil, options: .loops)
            try newEngine.start()
            newPlayer.play()

            engine = newEngine
            player = newPlayer
            startHealthCheck()
        } catch {
            engine?.stop()
            engine = nil
            player = nil
            healthTimer?.invalidate()
            healthTimer = nil
        }
    }

    func stop() {
        healthTimer?.invalidate()
        healthTimer = nil
        player?.stop()
        engine?.stop()
        player = nil
        engine = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func startHealthCheck() {
        healthTimer?.invalidate()
        healthTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if self.engine?.isRunning != true || self.player?.isPlaying != true {
                    self.start()
                }
            }
        }
    }

    private enum KeepAliveError: Error {
        case cannotCreateSilentBuffer
    }
}

private final class DenyRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

struct ActivityEntry: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let date = Date()
    let failed: Bool
}

@MainActor
final class AgentService: NSObject, ObservableObject, CLLocationManagerDelegate {
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
    @Published private(set) var locationStatusMessage = "Location-assisted background mode is off."
    @Published private(set) var lastLocationUpdate: Date?
    @Published private(set) var isReceivingBackgroundLocation = false
    @Published var backgroundLocationEnabled: Bool
    @Published var backgroundAudioEnabled: Bool

    private let locationManager = CLLocationManager()
    private var pollTask: Task<Void, Never>?

    override init() {
        let storedEndpoint = UserDefaults.standard.string(forKey: "relayEndpoint") ?? Self.defaultRelay
        relayEndpoint = Self.normalizedRelayEndpoint(storedEndpoint) ?? Self.defaultRelay
        agentToken = SecureStore.read(account: "agent-token") ?? ""
        localAddress = LocalNetworkAgent.interfaceInfo()?.address ?? "Not connected"
        let defaults = UserDefaults.standard
        if defaults.object(forKey: "backgroundAudioEnabled") == nil {
            defaults.set(true, forKey: "backgroundAudioEnabled")
        }
        if defaults.object(forKey: "backgroundLocationEnabled") == nil {
            defaults.set(true, forKey: "backgroundLocationEnabled")
        }
        backgroundAudioEnabled = defaults.bool(forKey: "backgroundAudioEnabled")
        backgroundLocationEnabled = defaults.bool(forKey: "backgroundLocationEnabled")
        super.init()

        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        locationManager.distanceFilter = CLLocationDistanceMax
        locationManager.activityType = .other
        locationManager.pausesLocationUpdatesAutomatically = false
        if backgroundLocationEnabled {
            locationStatusMessage = "Enabled. Start the bridge to begin the location session."
        }
    }

    /// Accepts the Supabase Function URL with or without a trailing slash or the
    /// optional MCP transport suffix. Agent requests always target the function root.
    private static func normalizedRelayEndpoint(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: trimmed),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil else {
            return nil
        }

        var path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if path.hasSuffix("/mcp") {
            path.removeLast(4)
        }
        guard path == "functions/v1/lanlink-mcp" else { return nil }
        components.path = "/" + path
        return components.url?.absoluteString
    }

    @discardableResult
    func saveSettings() -> Bool {
        guard let endpoint = Self.normalizedRelayEndpoint(relayEndpoint) else {
            statusMessage = "Relay URL must be the LANLink Supabase HTTPS Function URL. A trailing slash or /mcp suffix is okay."
            return false
        }
        do {
            let token = agentToken.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !token.isEmpty else { statusMessage = "Enter the iPhone agent token."; return false }
            try SecureStore.save(token, account: "agent-token")
            UserDefaults.standard.set(endpoint, forKey: "relayEndpoint")
            relayEndpoint = endpoint
            statusMessage = "Settings saved securely."
            addActivity("Settings saved", "Relay endpoint and Keychain token updated.", failed: false)
            return true
        } catch {
            statusMessage = error.localizedDescription
            return false
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
        statusMessage = "Bridge active."
        localAddress = LocalNetworkAgent.interfaceInfo()?.address ?? "Not connected"
        addActivity("Bridge started", "This iPhone is now polling the LANLink relay.", failed: false)
        if backgroundAudioEnabled {
            BackgroundAudioKeepAlive.shared.start()
        }
        if backgroundLocationEnabled {
            startLocationBackgroundSession()
        }
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
        locationManager.stopUpdatingLocation()
        locationManager.allowsBackgroundLocationUpdates = false
        isReceivingBackgroundLocation = false
        BackgroundAudioKeepAlive.shared.stop()
        if backgroundLocationEnabled {
            locationStatusMessage = "Bridge stopped. Location updates have stopped too."
        }
        statusMessage = "Bridge stopped."
    }

    /// Silent background playback and low-accuracy Core Location are independent,
    /// explicit options. Both are active only for the lifetime of the bridge session.
    func setBackgroundAudioMode(_ enabled: Bool) {
        backgroundAudioEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "backgroundAudioEnabled")
        if enabled && isRunning {
            BackgroundAudioKeepAlive.shared.start()
            statusMessage = "Silent audio keep-alive enabled for the active bridge."
        } else {
            BackgroundAudioKeepAlive.shared.stop()
            if enabled {
                statusMessage = "Silent audio keep-alive is enabled for the next bridge session."
            }
        }
    }

    /// Open LocalDevVPN's supported hand-off URL. The VPN is a loopback tunnel
    /// for on-device developer services; it is not the keep-alive mechanism.
    func connectLocalDevVPN() {
        let callbackScheme = ProcessInfo.processInfo.environment["LC_HOME_PATH"] != nil
            ? "livecontainer"
            : "lanlink"
        var components = URLComponents()
        components.scheme = "localdevvpn"
        components.host = "enable"
        components.queryItems = [URLQueryItem(name: "scheme", value: callbackScheme)]

        guard let url = components.url else {
            statusMessage = "Could not create the LocalDevVPN hand-off URL."
            return
        }
        UIApplication.shared.open(url, options: [:]) { [weak self] opened in
            Task { @MainActor in
                guard let self else { return }
                if opened {
                    self.statusMessage = "Opening LocalDevVPN. Connect the loopback tunnel there, then return to LANLink."
                } else if let storeURL = URL(string: "https://apps.apple.com/app/id6755608044") {
                    self.statusMessage = "LocalDevVPN is not installed. Opening its App Store page."
                    UIApplication.shared.open(storeURL)
                } else {
                    self.statusMessage = "LocalDevVPN could not be opened."
                }
            }
        }
    }

    func setBackgroundLocationMode(_ enabled: Bool) {
        backgroundLocationEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "backgroundLocationEnabled")

        guard enabled else {
            locationManager.stopUpdatingLocation()
            locationManager.allowsBackgroundLocationUpdates = false
            isReceivingBackgroundLocation = false
            locationStatusMessage = "Location-assisted background mode is off."
            addActivity("Background location disabled", "Location updates stopped.", failed: false)
            return
        }

        guard isRunning else {
            locationStatusMessage = "Enabled. Start the bridge to request location permission and begin updates."
            return
        }
        startLocationBackgroundSession()
    }

    private func startLocationBackgroundSession() {
        guard CLLocationManager.locationServicesEnabled() else {
            locationStatusMessage = "iPhone Location Services are turned off."
            return
        }

        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.showsBackgroundLocationIndicator = true

        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationStatusMessage = "Allow Always when iOS asks for location permission."
            locationManager.requestAlwaysAuthorization()
        case .authorizedWhenInUse:
            locationStatusMessage = "Upgrade LANLink to Always location access to use background location."
            locationManager.requestAlwaysAuthorization()
        case .authorizedAlways:
            if !isReceivingBackgroundLocation {
                locationManager.startUpdatingLocation()
                isReceivingBackgroundLocation = true
                addActivity("Background location session started", "Low-accuracy Core Location updates enabled for this bridge session.", failed: false)
            }
            locationStatusMessage = "Low-accuracy location updates active. Coordinates are not stored or sent to the relay."
        case .denied:
            isReceivingBackgroundLocation = false
            locationStatusMessage = "Location permission is denied. Enable it in iOS Settings to use this mode."
        case .restricted:
            isReceivingBackgroundLocation = false
            locationStatusMessage = "Location access is restricted on this iPhone."
        @unknown default:
            isReceivingBackgroundLocation = false
            locationStatusMessage = "Location authorization status is unavailable."
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor [weak self] in
            guard let self, self.backgroundLocationEnabled, self.isRunning else { return }
            self.startLocationBackgroundSession()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard !locations.isEmpty else { return }
        let updateTime = Date()
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.lastLocationUpdate = updateTime
            self.isReceivingBackgroundLocation = true
            self.locationStatusMessage = "Low-accuracy location heartbeat received. Coordinates are not stored or sent to the relay."
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let message = error.localizedDescription
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.isReceivingBackgroundLocation = false
            self.locationStatusMessage = "Location update error: \\(message)"
        }
    }

    func handleScenePhase(_ phase: ScenePhase) {
        guard isRunning else { return }
        switch phase {
        case .active:
            statusMessage = "Bridge active."
            if backgroundAudioEnabled {
                BackgroundAudioKeepAlive.shared.start()
            }
            if backgroundLocationEnabled {
                startLocationBackgroundSession()
            }
        case .inactive, .background:
            if backgroundAudioEnabled || (backgroundLocationEnabled && isReceivingBackgroundLocation) {
                statusMessage = "Bridge background keep-alive is enabled. iOS still controls background scheduling."
            } else {
                statusMessage = "Bridge active. Enable Silent Audio or Background Location for best-effort background execution."
            }
        @unknown default:
            break
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
        let contentType = payload["content_type"] as? String ?? "application/json"
        guard ["application/json", "text/plain", "application/x-www-form-urlencoded"].contains(contentType) else { throw AgentError.invalidCommand }
        var components = URLComponents()
        components.scheme = scheme
        components.host = ip
        components.port = port
        components.percentEncodedPath = path
        guard let url = components.url else { throw AgentError.invalidCommand }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 8
        request.httpShouldHandleCookies = false
        if !body.isEmpty {
            request.httpBody = Data(body.utf8)
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        let redirectBlocker = DenyRedirects()
        let session = URLSession(configuration: .ephemeral, delegate: redirectBlocker, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: request)
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
                ["ip": device.ip, "services": device.services]
            }
        }
        _ = try await relayRequest("agent/report", method: "POST", body: body)
    }

    private func relayRequest(_ path: String, method: String, body: [String: Any]? = nil) async throws -> Data {
        // These are the only internal routes the iPhone agent is allowed to call.
        let allowedRoutes: Set<String> = ["agent/heartbeat", "agent/poll", "agent/report"]
        let route = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard allowedRoutes.contains(route),
              let normalized = Self.normalizedRelayEndpoint(relayEndpoint),
              var components = URLComponents(string: normalized) else {
            throw AgentError.invalidRelay
        }

        // URLComponents.path must remain absolute. The previous code removed the
        // leading slash, causing URL construction to fail on some iOS URL parsers.
        let basePath = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components.path = "/" + basePath + "/" + route

        guard let url = components.url,
              url.scheme?.lowercased() == "https",
              url.host != nil,
              url.path.hasSuffix("/" + route) else {
            throw AgentError.invalidRelay
        }

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
