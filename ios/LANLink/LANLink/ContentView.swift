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
            SettingsView()
                .tabItem { Label("Settings", systemImage: "slider.horizontal.3") }
        }
        .tint(.cyan)
        .onChange(of: scenePhase) { phase in
            agent.handleScenePhase(phase)
        }
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
                            Text("Your network, within reach.")
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "dot.radiowaves.left.and.right")
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(.cyan)
                            .padding(14)
                            .background(.cyan.opacity(0.12), in: RoundedRectangle(cornerRadius: 18))
                    }

                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            Circle()
                                .fill(agent.isRunning ? Color.green : Color.orange)
                                .frame(width: 9, height: 9)
                            Text(agent.isRunning ? "BRIDGE ACTIVE" : "BRIDGE PAUSED")
                                .font(.caption.bold())
                                .tracking(1.1)
                            Spacer()
                            Text(agent.localAddress)
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                        }
                        Text(agent.isRunning
                             ? "ChatGPT can send supported LAN commands while this app stays open."
                             : "Start the bridge to let ChatGPT reach devices on this iPhone's local network.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Button {
                            agent.isRunning ? agent.stop() : agent.start()
                        } label: {
                            Label(agent.isRunning ? "Stop bridge" : "Start bridge",
                                  systemImage: agent.isRunning ? "stop.fill" : "bolt.fill")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 7)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(agent.isRunning ? .orange : .cyan)
                    }
                    .padding(18)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))

                    HStack(spacing: 12) {
                        MetricCard(title: "Devices", value: "\(agent.devices.count)", icon: "network")
                        MetricCard(title: "Commands", value: "\(agent.activity.count)", icon: "terminal")
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Connection").font(.headline)
                        Text(agent.statusMessage)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        if let last = agent.lastContact {
                            Text("Last relay contact · \(last.formatted(date: .omitted, time: .shortened))")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .padding(17)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20))

                    Text("Quick actions").font(.headline)
                    HStack {
                        Button {
                            Task { await agent.scanNow() }
                        } label: {
                            QuickAction(icon: "dot.radiowaves.left.and.right", title: agent.isScanning ? "Scanning…" : "Scan LAN")
                        }
                        .buttonStyle(.plain)
                        .disabled(agent.isScanning)
                        Button {
                            agent.start()
                        } label: {
                            QuickAction(icon: "antenna.radiowaves.left.and.right", title: "Reconnect")
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(18)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationBarHidden(true)
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

private struct QuickAction: View {
    let icon: String
    let title: String
    var body: some View {
        Label(title, systemImage: icon)
            .font(.subheadline.weight(.semibold))
            .frame(maxWidth: .infinity)
            .padding(14)
            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 15))
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
                                           description: Text("Scan your current private Wi-Fi network to find devices with common open services."))
                } else {
                    List(agent.devices) { device in
                        VStack(alignment: .leading, spacing: 7) {
                            HStack {
                                Text(device.hostname ?? device.ip).font(.headline)
                                Spacer()
                                Text(device.ip).font(.caption.monospaced()).foregroundStyle(.secondary)
                            }
                            Text(device.services.joined(separator: "  ·  "))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
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
                    .disabled(agent.isScanning)
                    .accessibilityLabel("Scan local network")
                }
            }
        }
    }
}

private struct ActivityView: View {
    @EnvironmentObject private var agent: AgentService

    var body: some View {
        NavigationStack {
            Group {
                if agent.activity.isEmpty {
                    ContentUnavailableView("Nothing yet", systemImage: "waveform.path",
                                           description: Text("Bridge events and command results will appear here."))
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
        }
    }
}

private struct SettingsView: View {
    @EnvironmentObject private var agent: AgentService
    @State private var saved = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Relay") {
                    TextField("HTTPS relay endpoint", text: $agent.relayEndpoint)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                    SecureField("iPhone agent token", text: $agent.agentToken)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Save settings") {
                        saved = agent.saveSettings()
                    }
                    .fontWeight(.semibold)
                }

                Section("Local network") {
                    LabeledContent("iPhone address", value: agent.localAddress)
                    Text("LANLink only scans and sends commands to private IPv4 devices on the same subnet. iOS does not expose MAC address or ARP-table access to ordinary apps.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Background operation") {
                    Label("Bridge runs while LANLink is open", systemImage: "iphone.gen3")
                        .font(.subheadline.weight(.medium))
                    Text("iOS may suspend network activity after you leave the app. Reopen LANLink and start the bridge again to resume ChatGPT access.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Status") {
                    Text(agent.statusMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .alert("Settings saved", isPresented: $saved) {
                Button("Done", role: .cancel) { }
            } message: {
                Text("Your agent token is stored in iOS Keychain.")
            }
        }
    }
}
