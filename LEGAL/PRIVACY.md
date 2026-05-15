# BASTION mobile — Privacy Policy

_Last updated: 2026-05-14_

## Summary

BASTION mobile collects **no personal data**. Nothing is transmitted to any server we operate.
The app's only outbound network call is fetching a public, signed blocklist file from GitHub Pages.

## What the app does

1. Establishes a local **VPN service** on your device (Android `VpnService` /
   iOS `NEPacketTunnelProvider`). This VPN routes only DNS queries through an in-app filter.
2. Compares each requested hostname against a public blocklist (URLhaus + OpenPhish +
   MalwareBazaar host portion). Blocked lookups return `NXDOMAIN`.
3. Records a local, on-device audit log of blocked lookups (no remote send).

## What we collect

**Nothing.** The app has no accounts, no analytics, no crash reporters, no ad SDKs.

## What is sent over the network

| Outbound | Destination | Payload | Frequency |
|---|---|---|---|
| Blocklist refresh | `https://1800bobrossdotcom-byte.github.io/bastion-mobile/blocklist.txt` | none (HTTP GET) | every 12 hours |
| Forwarded DNS queries | Your configured upstream resolver (default `1.1.1.1`) | the hostname your apps are already querying | as your device queries them |

The forwarded DNS queries are **not stored or transmitted by us** — they are passed through to
the upstream resolver of your choice exactly as your device would have asked, *unless* the hostname
matches the blocklist (in which case the query is dropped).

## Data stored on-device

- Local audit log (SQLite) of blocked hostname lookups. You can wipe it from Settings.
- Cached blocklist file.

## Permissions

- **VPN** — required to filter DNS at the OS level. No other VPN traffic is intercepted.
- **Foreground service notification** (Android) — required by Android to keep the sensor alive.
- **Internet** — to fetch the public blocklist.

## Open source

Source code: <https://github.com/1800bobrossdotcom-byte/bastion-mobile>

## Contact

For privacy questions: open an issue on the GitHub repo above.
