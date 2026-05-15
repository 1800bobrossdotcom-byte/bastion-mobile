# bastion DNS server (Option B redesign)

Self-hosted DNS-over-TLS resolver that powers the bastion-mobile app's
"Private DNS" mode. Users set Android → Settings → Private DNS → `dns.bastion.cam`
and every DNS query their phone makes is filtered against the bastion blocklists
before being forwarded upstream.

## Why this exists

The previous bastion-mobile architecture intercepted packets via Android's
`VpnService`, rewrote DNS responses, and synthesized NXDOMAIN packets. That
approach repeatedly broke users' internet (carrier middleboxes, DoH-by-default
browsers, captive-portal probes, OS connectivity heuristics). Option B moves
the blocking off-device entirely — the OS does DNS, we just answer the queries.

See `/memories/bastion-mobile-project.md` for the full history.

## Stack

- **blocky** (`spx01/blocky`) — Go DNS resolver with native blocklist support,
  DoT, DoH, prometheus metrics. ~30 MB RAM.
- **Docker Compose**, host networking on the existing Hetzner VPS
  (`62.238.40.253`, same box as faraday-coturn / faraday-sfu).
- **Let's Encrypt** cert for `dns.bastion.cam`, HTTP-01 via certbot standalone.

## Deploy (first time)

Prereq: A record `dns.bastion.cam → 62.238.40.253` at Namecheap.

```sh
# 1. Get the cert (will briefly bind port 80 — make sure nothing else is on it).
ssh root@62.238.40.253
certbot certonly --standalone -d dns.bastion.cam --agree-tos -m you@example.com -n

# 2. Push config and start blocky.
mkdir -p /root/bastion-dns/data
# scp docker-compose.yml + config.yml from this directory to /root/bastion-dns/
cd /root/bastion-dns
docker compose pull
docker compose up -d
docker compose logs -f
```

## Sanity check

```sh
# From any machine:
kdig +tls @dns.bastion.cam example.com         # should return real A
kdig +tls @dns.bastion.cam doubleclick.net     # should return 0.0.0.0

# Or with openssl:
openssl s_client -connect dns.bastion.cam:853 -servername dns.bastion.cam < /dev/null
```

## Cert renewal

Certbot installs a systemd timer that auto-renews. After renewal, blocky needs
to be restarted to pick up the new cert. Add a deploy hook:

```sh
echo '#!/bin/sh
docker restart bastion-dns
' > /etc/letsencrypt/renewal-hooks/deploy/restart-bastion-dns.sh
chmod +x /etc/letsencrypt/renewal-hooks/deploy/restart-bastion-dns.sh
```

## Firewall

```sh
ufw allow 53/udp
ufw allow 53/tcp
ufw allow 853/tcp
ufw allow 80/tcp     # only for cert renewal (HTTP-01)
```

## Privacy

- Query log goes to stdout only, not persisted to disk.
- Client IPs are redacted from logs (`log.privacy: true`).
- Prometheus metrics on `127.0.0.1:4000`, not exposed publicly.
- We see DNS queries by IP (no client identifiers), but we choose not to record them.
