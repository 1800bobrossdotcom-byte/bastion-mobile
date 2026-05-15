# Play Store listing — BASTION

## Title (max 30)
BASTION — DNS Privacy Sensor

## Short description (max 80)
Block malicious + phishing domains. Local DNS sinkhole. No tracking. Open source.

## Full description (max 4000)
BASTION is a defensive sensor for your phone — not a shield, not magic.

It runs as a local VPN that filters DNS lookups against a public blocklist of known-malicious hostnames (URLhaus + OpenPhish + MalwareBazaar, refreshed every 12 hours from a public GitHub repository). Everything happens on your device. Nothing is uploaded.

WHAT IT BLOCKS
• Phishing domains (live OpenPhish feed)
• Active malware command-and-control hosts (URLhaus)
• Hosts associated with known malware samples (MalwareBazaar)

WHAT IT DOES NOT DO (honest list)
• Does NOT block "spyware" or detect Pegasus-class targeted attacks
• Does NOT scan files inside other apps (Android sandboxing forbids this)
• Does NOT replace antivirus, system updates, or careful behaviour
• Does NOT see traffic that uses DNS-over-HTTPS (yet — v0.2 will detect + warn)

PRIVACY
• Zero accounts, zero analytics, zero ad SDKs, zero cloud
• Your DNS queries are forwarded to your chosen upstream resolver (default 1.1.1.1)
• Audit log of blocked lookups stored only on-device, wipeable any time
• Source code: github.com/1800bobrossdotcom-byte/bastion-mobile

WHO THIS IS FOR
• People who want a transparent, auditable DNS filter
• Hackers, sysadmins, journalists who want receipts
• Anyone tired of "AI-powered" antivirus apps making promises they can't keep

WHO THIS IS NOT FOR
• People who want a one-button "make my phone secure" promise — it doesn't exist
• People who need protection against state-actor spyware — buy a GrapheneOS Pixel instead

License: source-available, all rights reserved. Free.

## Category
Tools

## Content rating
Everyone

## Tags / keywords
dns, blocker, privacy, vpn, malware, phishing, sensor, security

## Data Safety form answers
- Data collected: None
- Data shared: None
- Data encrypted in transit: Yes (only outbound is the public blocklist fetch over HTTPS)
- Users can request data deletion: N/A (no data collected)
- Independent security review: No
- Complies with Families policy: N/A (Tools)

## Screenshots needed (Play Store: 2-8 per device class)
1. Hero: app open, sensor OFFLINE, big phosphor button "./sensor start"
2. Sensor ACTIVE state showing "loaded N hosts" + most recent block in audit log
3. Audit log scroll showing 5-10 timestamped block events
4. Honesty disclaimer screen
5. Settings: blocklist source, last refresh, wipe log button

## Feature graphic (1024 x 500)
Black background. Phosphor green ASCII "BASTION" wordmark. Subtitle "dns sensor // open source // no tracking".

## Privacy policy URL
https://1800bobrossdotcom-byte.github.io/bastion-mobile/PRIVACY.html
(or: https://lovebeing.shop/apps/bastion/privacy)
