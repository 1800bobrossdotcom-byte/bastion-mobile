import SwiftUI
import NetworkExtension

private let PHOSPHOR = Color(red: 0.20, green: 1.0, blue: 0.40)
private let AMBER    = Color(red: 1.0,  green: 0.67, blue: 0.20)

struct ContentView: View {
    @State private var sensorActive = false
    @State private var status = "OFFLINE"
    @State private var manager: NETunnelProviderManager?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 16) {
                Text("BASTION")
                    .font(.system(size: 28, design: .monospaced))
                    .foregroundColor(PHOSPHOR)
                Text("v0.1.0 // dns sensor")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(.white.opacity(0.4))

                VStack(alignment: .leading, spacing: 8) {
                    Text("[ status ] sensor: \(status)")
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundColor(sensorActive ? PHOSPHOR : AMBER)
                    Text("Filters DNS against URLhaus + OpenPhish + MalwareBazaar.\nNothing leaves the phone. Audit log stored locally.")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundColor(.white.opacity(0.6))
                }
                .padding(16)
                .background(Color(white: 0.04))
                .cornerRadius(4)

                Button(action: toggle) {
                    Text(sensorActive ? "./sensor stop" : "./sensor start")
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundColor(PHOSPHOR)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(PHOSPHOR.opacity(0.15))
                        .cornerRadius(2)
                }

                Spacer().frame(height: 12)

                Text("[warn] honesty.disclaimer")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(AMBER)
                Text("BASTION is a sensor, not a shield. iOS sandboxing means we can only watch DNS lookups, not what other apps do internally. We cannot detect Pegasus or other targeted spyware.")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(.white.opacity(0.5))

                Spacer()
            }
            .padding(20)
        }
        .task { await loadManager() }
    }

    private func loadManager() async {
        do {
            let mgrs = try await NETunnelProviderManager.loadAllFromPreferences()
            manager = mgrs.first ?? NETunnelProviderManager()
            sensorActive = manager?.connection.status == .connected
            status = sensorActive ? "ACTIVE" : "OFFLINE"
        } catch { }
    }

    private func toggle() {
        Task {
            guard let m = manager else { return }
            if sensorActive {
                m.connection.stopVPNTunnel()
                sensorActive = false
                status = "OFFLINE"
            } else {
                let proto = NETunnelProviderProtocol()
                proto.providerBundleIdentifier = "cam.bastion.mobile.tunnel"
                proto.serverAddress = "BASTION"
                m.protocolConfiguration = proto
                m.localizedDescription = "BASTION sensor"
                m.isEnabled = true
                do {
                    try await m.saveToPreferences()
                    try await m.loadFromPreferences()
                    try m.connection.startVPNTunnel()
                    sensorActive = true
                    status = "ACTIVE"
                } catch {
                    status = "ERROR"
                }
            }
        }
    }
}
