import Foundation
import Network
import Darwin

struct LANDevice: Identifiable, Hashable {
    let ip: String
    let services: [String]
    var hostname: String? = nil
    var id: String { ip }
}

struct NetworkInterfaceInfo {
    let address: String
    let mask: UInt32
    let scanMask: UInt32
    let broadcast: String
}

private final class CompletionLatch: @unchecked Sendable {
    private let lock = NSLock()
    private var completed = false

    func resolve(_ action: () -> Void) {
        lock.lock()
        guard !completed else {
            lock.unlock()
            return
        }
        completed = true
        lock.unlock()
        action()
    }
}

enum LocalNetworkAgent {
    static let ports: [(UInt16, String)] = [
        (22, "SSH"), (23, "Telnet"), (53, "DNS"), (80, "HTTP"),
        (443, "HTTPS"), (554, "RTSP"), (5555, "ADB"), (8008, "Cast"),
        (8080, "HTTP-alt"), (8443, "HTTPS-alt")
    ]

    static func isPrivateIPv4(_ value: String) -> Bool {
        let parts = value.split(separator: ".").compactMap { UInt8($0) }
        guard parts.count == 4 else { return false }
        return parts[0] == 10
            || (parts[0] == 172 && (16...31).contains(parts[1]))
            || (parts[0] == 192 && parts[1] == 168)
    }

    static func interfaceInfo() -> NetworkInterfaceInfo? {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0, let start = first else { return nil }
        defer { freeifaddrs(first) }

        var cursor: UnsafeMutablePointer<ifaddrs>? = start
        while let current = cursor {
            defer { cursor = current.pointee.ifa_next }
            guard String(cString: current.pointee.ifa_name) == "en0",
                  let addressPointer = current.pointee.ifa_addr,
                  let maskPointer = current.pointee.ifa_netmask,
                  addressPointer.pointee.sa_family == sa_family_t(AF_INET) else { continue }

            var address = unsafeBitCast(addressPointer.pointee, to: sockaddr_in.self).sin_addr
            var maskAddress = unsafeBitCast(maskPointer.pointee, to: sockaddr_in.self).sin_addr
            var addressBuffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            var maskBuffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard inet_ntop(AF_INET, &address, &addressBuffer, socklen_t(addressBuffer.count)) != nil,
                  inet_ntop(AF_INET, &maskAddress, &maskBuffer, socklen_t(maskBuffer.count)) != nil else { continue }

            let ip = String(cString: addressBuffer)
            guard isPrivateIPv4(ip),
                  let ipHost = ipv4HostOrder(ip),
                  let maskHost = ipv4HostOrder(String(cString: maskBuffer)) else { continue }

            let prefix = max(maskHost.nonzeroBitCount, 24)
            let scanMask = UInt32.max << (32 - prefix)
            let network = ipHost & scanMask
            let broadcastHost = network | ~scanMask
            guard let broadcast = ipv4String(broadcastHost) else { continue }
            return NetworkInterfaceInfo(address: ip, mask: maskHost, scanMask: scanMask, broadcast: broadcast)
        }
        return nil
    }

    static func discover() async throws -> [LANDevice] {
        guard let interface = interfaceInfo(),
              let ipHost = ipv4HostOrder(interface.address) else {
            throw NetworkError.noPrivateInterface
        }
        let network = ipHost & interface.scanMask
        let broadcast = network | ~interface.scanMask
        guard broadcast > network + 1 else { return [] }

        let addresses = ((network + 1)..<broadcast)
            .filter { $0 != ipHost }
            .compactMap(ipv4String)
        var found: [LANDevice] = []

        for batchStart in stride(from: 0, to: addresses.count, by: 16) {
            let batch = Array(addresses[batchStart..<min(batchStart + 16, addresses.count)])
            await withTaskGroup(of: LANDevice?.self) { group in
                for ip in batch {
                    group.addTask { await probeHost(ip) }
                }
                for await device in group {
                    if let device { found.append(device) }
                }
            }
        }
        return found.sorted { ipSortKey($0.ip) < ipSortKey($1.ip) }
    }

    static func probe(_ ip: String) async throws -> [String] {
        guard isPrivateIPv4(ip), let local = interfaceInfo(),
              sameSubnet(ip, local: local) else { throw NetworkError.targetOutsideSubnet }
        return await openServices(ip)
    }

    static func sendWakePacket(mac: String, broadcast: String?) throws {
        let normalized = mac.lowercased()
        let pieces = normalized.split(separator: ":")
        guard pieces.count == 6,
              pieces.allSatisfy({ $0.count == 2 && UInt8($0, radix: 16) != nil }) else {
            throw NetworkError.invalidMAC
        }
        guard let interface = interfaceInfo() else { throw NetworkError.noPrivateInterface }
        let target = broadcast ?? interface.broadcast
        guard isPrivateIPv4(target), target == interface.broadcast else {
            throw NetworkError.invalidBroadcast
        }

        let octets = pieces.compactMap { UInt8($0, radix: 16) }
        var packet = Data(repeating: 0xFF, count: 6)
        for _ in 0..<16 { packet.append(contentsOf: octets) }

        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw NetworkError.socketFailure }
        defer { close(fd) }
        var enabled: Int32 = 1
        guard setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &enabled, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
            throw NetworkError.socketFailure
        }

        var destination = sockaddr_in()
        destination.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        destination.sin_family = sa_family_t(AF_INET)
        destination.sin_port = in_port_t(9).bigEndian
        let addressStatus = target.withCString { inet_pton(AF_INET, $0, &destination.sin_addr) }
        guard addressStatus == 1 else { throw NetworkError.invalidBroadcast }

        let sent = packet.withUnsafeBytes { packetBytes -> Int in
            withUnsafePointer(to: &destination) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { addressPointer in
                    sendto(fd, packetBytes.baseAddress, packetBytes.count, 0, addressPointer, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        guard sent == packet.count else { throw NetworkError.socketFailure }
    }

    private static func probeHost(_ ip: String) async -> LANDevice? {
        let services = await openServices(ip)
        guard !services.isEmpty else { return nil }
        return LANDevice(ip: ip, services: services)
    }

    private static func openServices(_ ip: String) async -> [String] {
        await withTaskGroup(of: (String?).self) { group in
            for (port, label) in ports {
                group.addTask {
                    await portIsOpen(ip, port: port) ? label : nil
                }
            }
            var result: [String] = []
            for await value in group {
                if let value { result.append(value) }
            }
            return result.sorted()
        }
    }

    private static func portIsOpen(_ ip: String, port: UInt16) async -> Bool {
        await withCheckedContinuation { continuation in
            let connection = NWConnection(host: NWEndpoint.Host(ip), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
            let latch = CompletionLatch()
            let finish: (Bool) -> Void = { success in
                latch.resolve {
                    connection.cancel()
                    continuation.resume(returning: success)
                }
            }
            let queue = DispatchQueue(label: "com.itzprovenom.lanlink.probe.\(ip).\(port)")
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(true)
                case .failed, .cancelled: finish(false)
                default: break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + 0.75) { finish(false) }
        }
    }

    private static func sameSubnet(_ ip: String, local: NetworkInterfaceInfo) -> Bool {
        guard let target = ipv4HostOrder(ip),
              let own = ipv4HostOrder(local.address) else { return false }
        return (target & local.mask) == (own & local.mask)
    }

    private static func ipv4HostOrder(_ value: String) -> UInt32? {
        var address = in_addr()
        guard value.withCString({ inet_pton(AF_INET, $0, &address) }) == 1 else { return nil }
        return UInt32(bigEndian: address.s_addr)
    }

    private static func ipv4String(_ hostOrder: UInt32) -> String? {
        var address = in_addr(s_addr: hostOrder.bigEndian)
        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &address, &buffer, socklen_t(buffer.count)) != nil else { return nil }
        return String(cString: buffer)
    }

    private static func ipSortKey(_ ip: String) -> UInt32 {
        ipv4HostOrder(ip) ?? 0
    }

    enum NetworkError: LocalizedError {
        case noPrivateInterface
        case targetOutsideSubnet
        case invalidMAC
        case invalidBroadcast
        case socketFailure

        var errorDescription: String? {
            switch self {
            case .noPrivateInterface: return "Connect to a private Wi-Fi network before using LANLink."
            case .targetOutsideSubnet: return "That address is not on this iPhone's local subnet."
            case .invalidMAC: return "Enter a valid MAC address."
            case .invalidBroadcast: return "The broadcast address must match this local subnet."
            case .socketFailure: return "The network operation failed."
            }
        }
    }
}
