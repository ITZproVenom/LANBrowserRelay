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
                                Label("Connect Android agent", systemImage: "link")
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
                if !agent.isConnected && !agent.androidHost.isEmpty && !agent.controllerToken.isEmpty {
                    await agent.connect()
                }
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
                                           description: Text("Connect to the Android agent and scan its local network."))
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
    let device: LANDevice
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
    @State private var saved = false
    @State private var showToken = false
    @FocusState private var focusedField: Field?

    private enum Field: Hashable { case host, port, token }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("ANDROID TV PAIRING")
                            .font(.caption.weight(.semibold)).tracking(1).foregroundStyle(.secondary)

                        TextField("Android TV IP address", text: $agent.androidHost)
                            .keyboardType(.decimalPad)
                            .textFieldStyle(.roundedBorder)
                            .focused($focusedField, equals: .host)

                        TextField("Controller port", text: $agent.portText)
                            .keyboardType(.numberPad)
                            .textFieldStyle(.roundedBorder)
                            .focused($focusedField, equals: .port)

                        HStack(spacing: 10) {
                            Group {
                                if showToken {
                                    TextField("Controller pairing key", text: $agent.controllerToken)
                                        .textFieldStyle(.roundedBorder)
                                        .focused($focusedField, equals: .token)
                                } else {
                                    SecureField("Controller pairing key", text: $agent.controllerToken)
                                        .textFieldStyle(.roundedBorder)
                                        .focused($focusedField, equals: .token)
                                }
                            }
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()

                            Button {
                                showToken.toggle()
                                focusedField = .token
                            } label: {
                                Image(systemName: showToken ? "eye.slash" : "eye")
                                    .frame(width: 40, height: 44)
                            }
                            .buttonStyle(.plain)
                        }

                        HStack(spacing: 12) {
                            Button("Save pairing") {
                                focusedField = nil
                                saved = agent.saveSettings()
                            }
                            .buttonStyle(.bordered)

                            Button("Save & connect") {
                                focusedField = nil
                                Task {
                                    if agent.saveSettings() { await agent.connect() }
                                }
                            }
                            .buttonStyle(.borderedProminent)
                        }
                        .tint(.cyan)
                        .frame(maxWidth: .infinity)

                        Text("On Android, open LANLink TV Agent. Enter the Android relay token and start the agent once. The TV then displays its IP address, port and a separate controller pairing key. Enter those details here.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    .padding(16)
                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))

                    VStack(alignment: .leading, spacing: 8) {
                        Text("CONNECTION STATUS")
                            .font(.caption.weight(.semibold)).tracking(1).foregroundStyle(.secondary)
                        Text(agent.statusMessage).font(.footnote).foregroundStyle(.secondary)
                        Button("Disconnect controller") { agent.disconnect() }
                            .disabled(!agent.isConnected)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
                }
                .padding(16)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Settings")
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focusedField = nil }
                }
            }
            .alert("Pairing saved", isPresented: $saved) {
                Button("Done", role: .cancel) { }
            } message: {
                Text("The controller key is stored in iOS Keychain.")
            }
        }
    }
}
