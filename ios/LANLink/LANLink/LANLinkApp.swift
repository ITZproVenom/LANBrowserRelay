import SwiftUI

@main
struct LANLinkApp: App {
    @StateObject private var agent = AgentService()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(agent)
        }
    }
}
