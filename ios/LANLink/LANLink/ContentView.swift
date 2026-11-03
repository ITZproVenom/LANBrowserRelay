import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var agent: AgentService
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView {
            DashboardView()
                .tabItem { Label("Overview", systemImage: "square.grid.2x2.fill") }
            DevicesView()
                .tabItem { Label("Devices", systemImage: "point.3.connected.trianglepath.dotted") }
            ActivityView()
                .tabItem { Label("Activity", systemImage: "waveform.path") }
            ControllerSettingsView()
                .tabItem { Label("Settings", systemImage: "slider.horizontal.3") }
        }
        .tint(.cyan)
        .onChange(of: scenePhase) { phase in agent.handleScenePhase(phase) }
    }
}

private struct DashboardView: View {
    @EnvironmentObject private var agent: AgentService

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("LANLink").font(.largeTitle.bold())
                            Text("Android network controller").foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "tv.and.mediabox")
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(.cyan)
                            .padding(14)
                            .background(.cyan.opacity(0.12), in: RoundedRectangle(cornerRadius: 18))
                    }

                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            Circle().fill(agent.isConnected ? Color.green : Color.orange).frame(width: 9, height: 9)
                            Text(agent.isConnected ? "ANDROID CONNECTED" : "ANDROID DISCONNECTED")
                                .font(.caption.bold()).tracking(1.0)
                            Spacer()
                            Text(agent.bridgeAddress.isEmpty ? agent.androidHost : agent.bridgeAddress)
                                .font(.caption.monospaced()).foregroundStyle(.secondary)
                        }
                        Text(agent.statusMessage)
                            .font(.subheadline).foregroundStyle(.secondary)

                        if agent.isConnected {
                            Button {
                                agent.isRunning ? agent.stop() : agent.start()
                            } label: {
                                Label(agent.isRunning ? "Pause ChatGPT relay" : "Start ChatGPT relay",
                                      systemImage: agent.isRunning ? "pause.fill" : "play.fill")
                                    .font(.headline)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 7)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(agent.isRunning ? .orange : .cyan)
                        } else {
                            Button {
                                Task { await agent.connect() }
                            } label: {
                                Label("Search for Android TV", systemImage: "dot.radiowaves.left.and.right")
                                    .font(.headline)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 7)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(.cyan)
                        }
                    }
                    .padding(18)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))

                    HStack(spacing: 12) {
                        MetricCard(title: "Devices", value: "\(agent.devices.count)", icon: "network")
                        MetricCard(title: "Controller", value: agent.isConnected ? "Online" : "Offline", icon: "iphone")
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Android agent").font(.headline)
                        LabeledContent("TV address", value: agent.androidHost.isEmpty ? "Not paired" : agent.androidHost)
                        LabeledContent("Control port", value: agent.portText)
                        if !agent.subnet.isEmpty { LabeledContent("LAN subnet", value: agent.subnet) }
                        if let contact = agent.lastRelayContact {
                            Text("Last relay contact · \(contact.formatted(date: .omitted, time: .shortened))")
                                .font(.caption).foregroundStyle(.tertiary)
                        }
                    }
                    .padding(17)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20))

                    HStack(spacing: 12) {
                        Button {
                            Task { await agent.refresh() }
                        } label: {
                            Label("Refresh", systemImage: "arrow.clockwise")
                                .frame(maxWidth: .infinity).padding(12)
                        }
                        .buttonStyle(.bordered)
                        Button {
                            Task { await agent.scanNow() }
                        } label: {
                            Label(agent.isScanning ? "Scanning…" : "Scan LAN", systemImage: "dot.radiowaves.left.and.right")
                                .frame(maxWidth: .infinity).padding(12)
                        }
                        .buttonStyle(.bordered)
                        .disabled(!agent.isConnected || agent.isScanning)
                    }
                }
                .padding(18)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationBarHidden(true)
            .task {
                agent.startDiscovery()
            }
        }
    }
}

private struct MetricCard: View {
    let title: String
    let value: String
    let icon: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: icon).foregroundStyle(.cyan)
            Text(value).font(.title.bold().monospacedDigit())
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct DevicesView: View {
    @EnvironmentObject private var agent: AgentService

    var body: some View {
        NavigationStack {
            Group {
                if agent.devices.isEmpty {
                    ContentUnavailableView("No devices yet",
                                           systemImage: "point.3.connected.trianglepath.dotted",
                                           description: Text("LANLink is automatically searching for your Android TV agent. Once connected, scan its local network here."))
                } else {
                    List(agent.devices) { device in
                        NavigationLink {
                            DeviceDetailView(device: device)
                        } label: {
                            VStack(alignment: .leading, spacing: 7) {
                                HStack {
                                    Text(device.hostname ?? device.ip).font(.headline)
                                    Spacer()
                                    Text(device.ip).font(.caption.monospaced()).foregroundStyle(.secondary)
                                }
                                Text(device.services.isEmpty ? "Host visible · no scanned services" : device.services.joined(separator: "  ·  "))
                                    .font(.caption).foregroundStyle(.secondary)
                                if let mac = device.mac {
                                    Text(mac.uppercased()).font(.caption2.monospaced()).foregroundStyle(.tertiary)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Devices")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await agent.scanNow() }
                    } label: {
                        if agent.isScanning { ProgressView() }
                        else { Image(systemName: "arrow.clockwise") }
                    }
                    .disabled(!agent.isConnected || agent.isScanning)
                    .accessibilityLabel("Scan local network using Android")
                }
            }
        }
    }
}

private struct DeviceDetailView: View {
    @EnvironmentObject private var agent: AgentService
    let device: ControllerDevice
    @State private var portText = "80"
    @State private var path = "/"

    var body: some View {
        Form {
            Section("Device") {
                LabeledContent("IP address", value: device.ip)
                if let host = device.hostname { LabeledContent("Name", value: host) }
                if let mac = device.mac { LabeledContent("MAC", value: mac.uppercased()) }
                LabeledContent("Services", value: device.services.isEmpty ? "None detected" : device.services.joined(separator: ", "))
            }
            Section("Actions") {
                Button {
                    agent.probe(device)
                } label: {
                    Label("Probe device", systemImage: "stethoscope")
                }
                Button {
                    agent.wake(device)
                } label: {
                    Label("Wake on LAN", systemImage: "power")
                }
                .disabled(device.mac == nil)
            }
            Section("HTTP request") {
                TextField("Port", text: $portText).keyboardType(.numberPad)
                TextField("Path", text: $path).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Send GET request") {
                    guard let port = Int(portText), (1...65535).contains(port) else { return }
                    agent.sendHTTP(ip: device.ip, port: port, scheme: port == 443 || port == 8443 ? "https" : "http", method: "GET", path: path)
                }
            }
        }
        .navigationTitle(device.hostname ?? device.ip)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ActivityView: View {
    @EnvironmentObject private var agent: AgentService

    var body: some View {
        NavigationStack {
            Group {
                if agent.activity.isEmpty {
                    ContentUnavailableView("Nothing yet", systemImage: "waveform.path",
                                           description: Text("Android agent events and command results appear here."))
                } else {
                    List(agent.activity) { entry in
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: entry.failed ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                                .foregroundStyle(entry.failed ? .orange : .green)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(entry.title).font(.subheadline.weight(.semibold))
                                Text(entry.detail).font(.caption).foregroundStyle(.secondary)
                                Text(entry.date.formatted(date: .omitted, time: .shortened))
                                    .font(.caption2).foregroundStyle(.tertiary)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Activity")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await agent.refresh() }
                    } label: { Image(systemName: "arrow.clockwise") }
                }
            }
        }
    }
}

private struct ControllerSettingsView: View {
    @EnvironmentObject private var agent: AgentService

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("AUTOMATIC CONNECTION")
                            .font(.caption.weight(.semibold)).tracking(1).foregroundStyle(.secondary)
                        HStack(spacing: 10) {
                            Image(systemName: agent.isConnected ? "checkmark.circle.fill" : "dot.radiowaves.left.and.right")
                                .font(.title2)
                                .foregroundStyle(agent.isConnected ? .green : .cyan)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(agent.isConnected ? "Android TV connected" : "Searching for Android TV")
                                    .font(.headline)
                                Text(agent.statusMessage)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }

                        if !agent.discoveredName.isEmpty {
                            LabeledContent("Discovered service", value: agent.discoveredName)
                        }
                        if !agent.bridgeAddress.isEmpty {
                            LabeledContent("TV address", value: agent.bridgeAddress)
                        }
                        if !agent.subnet.isEmpty {
                            LabeledContent("Subnet", value: agent.subnet)
                        }

                        Button {
                            agent.startDiscovery()
                        } label: {
                            Label("Search again", systemImage: "arrow.clockwise")
                                .frame(maxWidth: .infinity, minHeight: 46)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.cyan)

                        Text("LANLink automatically finds the Android TV agent on the same Wi-Fi or Ethernet network. No IP address, port, pairing code or manual token entry is needed. The first iPhone to claim an unpaired TV becomes its controller.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(16)
                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))

                    VStack(alignment: .leading, spacing: 8) {
                        Text("SECURITY")
                            .font(.caption.weight(.semibold)).tracking(1).foregroundStyle(.secondary)
                        Text("The iPhone creates a random controller key and keeps it in Keychain. The TV stores the enrolled key using Android Keystore. Automatic first-time pairing is limited to the same private LAN and one controller. Use a trusted home network; resetting the Android app's data clears its enrolled controller.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
                }
                .padding(16)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Settings")
        }
    }
}
