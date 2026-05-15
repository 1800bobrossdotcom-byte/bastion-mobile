#!/usr/bin/env python3
"""Merge URLhaus + OpenPhish + MalwareBazaar host portion into one deduplicated blocklist.
Outputs to stdout. Each line is a single hostname, lowercase, no port, no scheme.
Lines starting with '#' are kept as comments at the top for provenance."""
import sys
import urllib.request
from datetime import datetime, timezone
from urllib.parse import urlparse

SOURCES = {
    "urlhaus_hosts": "https://urlhaus.abuse.ch/downloads/hostfile/",
    "openphish":    "https://openphish.com/feed.txt",
}

def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "bastion-mobile-blocklist/1.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", errors="replace")

def parse_hosts_file(text: str):
    out = set()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        # hosts file: "0.0.0.0 example.com" or "127.0.0.1 example.com"
        host = parts[-1].lower().rstrip(".")
        if "." in host and " " not in host:
            out.add(host)
    return out

def parse_openphish(text: str):
    out = set()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            host = urlparse(line).hostname
            if host:
                out.add(host.lower().rstrip("."))
        except Exception:
            pass
    return out

def main():
    merged = set()
    src_counts = {}
    for name, url in SOURCES.items():
        try:
            text = fetch(url)
            if name == "urlhaus_hosts":
                hosts = parse_hosts_file(text)
            else:
                hosts = parse_openphish(text)
            src_counts[name] = len(hosts)
            merged |= hosts
        except Exception as e:
            print(f"# WARN: {name} failed: {e}", file=sys.stderr)
            src_counts[name] = 0

    # Filter obviously-bad entries
    merged = {h for h in merged if "." in h and len(h) < 254 and not h.startswith(".")}

    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    print(f"# BASTION mobile blocklist")
    print(f"# generated: {now}")
    for k, v in src_counts.items():
        print(f"# source {k}: {v} hosts")
    print(f"# total unique: {len(merged)}")
    print(f"# verify: https://github.com/1800bobrossdotcom-byte/bastion-mobile/blob/master/blocklist/build.py")
    for h in sorted(merged):
        print(h)

if __name__ == "__main__":
    main()
