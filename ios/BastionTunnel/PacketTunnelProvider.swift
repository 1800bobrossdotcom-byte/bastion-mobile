import NetworkExtension
import os

/// BASTION DNS sinkhole packet tunnel.
///
/// Status:
///  - Tunnel established + DNS routed to in-extension handler: WORKING
///  - Blocklist fetch + cache: WORKING
///  - Per-query sinkhole + upstream forward: SCAFFOLDED in handlePackets()
///
/// Production hardening for v0.2:
///  - IPv6 support
///  - DoH detection / opt-out
///  - Connection-level metering (count bytes blocked per app)
class PacketTunnelProvider: NEPacketTunnelProvider {
    private let log = Logger(subsystem: "cam.bastion.mobile", category: "tunnel")
    private var hosts: Set<String> = []

    override func startTunnel(options: [String : NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "10.42.0.1")
        let ipv4 = NEIPv4Settings(addresses: ["10.42.0.2"], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4

        let dns = NEDNSSettings(servers: ["1.1.1.1", "1.0.0.1"])
        dns.matchDomains = [""]
        settings.dnsSettings = dns
        settings.mtu = 1500

        Task {
            await refreshBlocklist()
            setTunnelNetworkSettings(settings) { [weak self] err in
                if let err = err {
                    completionHandler(err); return
                }
                self?.handlePackets()
                completionHandler(nil)
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        completionHandler()
    }

    private func handlePackets() {
        packetFlow.readPackets { [weak self] packets, protocols in
            guard let self = self else { return }
            // TODO: parse DNS questions out of UDP/53, sinkhole or forward.
            // Mirror the Android Kotlin runDnsLoop logic in Swift.
            // For 0.1, pass everything through; sinkhole arrives in 0.2.
            self.packetFlow.writePackets(packets, withProtocols: protocols)
            self.handlePackets()
        }
    }

    private func refreshBlocklist() async {
        guard let url = URL(string: "https://1800bobrossdotcom-byte.github.io/bastion-mobile/blocklist.txt") else { return }
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            guard let text = String(data: data, encoding: .utf8) else { return }
            var set = Set<String>()
            for line in text.split(separator: "\n") {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                if trimmed.isEmpty || trimmed.hasPrefix("#") { continue }
                set.insert(trimmed.lowercased())
            }
            hosts = set
            log.info("loaded \(set.count) blocklist hosts")
        } catch {
            log.warning("blocklist fetch failed: \(error.localizedDescription)")
        }
    }
}
