# App Store listing — BASTION

## Name (max 30)
BASTION DNS Sensor

## Subtitle (max 30)
Honest local DNS filter

## Promotional text (max 170)
A defensive DNS sensor — not a shield. Blocks phishing + malware hostnames using public feeds. Nothing leaves your phone. Open source. No accounts.

## Description (max 4000)
BASTION is a defensive sensor for your iPhone. It runs as a local Network Extension VPN that filters DNS lookups against a public blocklist of known-malicious hostnames — URLhaus, OpenPhish, and MalwareBazaar, refreshed every 12 hours.

Everything happens on-device. There are no accounts, no analytics, no ad SDKs, no cloud.

WHAT IT BLOCKS
• Phishing domains (OpenPhish)
• Active malware command-and-control hosts (URLhaus)
• Hosts associated with known malware (MalwareBazaar)

WHAT IT WILL NOT DO
• It will not detect Pegasus or other targeted spyware. Anything claiming that on iOS is lying.
• It will not see what other apps do inside their sandbox. iOS forbids that.
• It will not catch traffic using DNS-over-HTTPS until v0.2.

PRIVACY
• Zero data collection. The Apple privacy nutrition label reads "Data Not Collected".
• Your DNS queries are forwarded to your chosen upstream resolver (default 1.1.1.1).
• Open source: github.com/1800bobrossdotcom-byte/bastion-mobile

Free. Source-available. All rights reserved.

## Keywords (max 100, comma-separated)
dns,vpn,privacy,malware,phishing,blocker,sensor,security,filter,opensource

## Primary category
Utilities

## Secondary category
Productivity

## Age rating
4+

## App privacy / nutrition label
Data Not Collected.

## App Review notes (private to Apple)
BASTION uses Network Extension (Packet Tunnel Provider) to implement a local DNS sinkhole. Outbound network: HTTPS GET to https://1800bobrossdotcom-byte.github.io/bastion-mobile/blocklist.txt every 12 hours, no other servers contacted. Source code public at https://github.com/1800bobrossdotcom-byte/bastion-mobile. The VPN handles only DNS; non-DNS traffic is forwarded transparently.

## Screenshots needed (6.7" + 6.1" + 5.5")
1. Hero with sensor OFFLINE state
2. Sensor ACTIVE with stats
3. Audit log scroll
4. Honesty disclaimer
5. Settings / blocklist info
6. About / verify-the-source

## Support URL
https://github.com/1800bobrossdotcom-byte/bastion-mobile

## Marketing URL
https://lovebeing.shop/apps/bastion

## Privacy policy URL
https://1800bobrossdotcom-byte.github.io/bastion-mobile/PRIVACY.html
