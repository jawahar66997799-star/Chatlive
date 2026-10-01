#!/usr/bin/env python3
"""
Static integration contract checker for Jawahar Live Sync.

It compares Android uplink constants and handshake/endpoint strings against
relay protocol/server source and exits nonzero on structural incompatibility.
It does not claim runtime measurement.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Dict, List


def read(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(path)
    return path.read_text(encoding="utf-8", errors="replace")


def rx(text: str, pattern: str, cast=str):
    m = re.search(pattern, text, re.MULTILINE)
    if not m:
        return None
    try:
        return cast(m.group(1))
    except Exception:
        return None


def add(checks: List[Dict], name: str, left, right, severity="RELEASE BLOCKER"):
    ok = left == right
    checks.append({
        "name": name,
        "ok": ok,
        "severity": "PASS" if ok else severity,
        "android": left,
        "relay": right,
    })


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--json-out", default="")
    args = ap.parse_args()
    root = Path(args.root)

    android_proto = read(root / "app/src/main/java/com/jawahar/livesync/JlsProtocol.kt")
    relay_proto = read(root / "relay/protocol.go")
    relay_main = read(root / "relay/main.go")
    relay_cfg = read(root / "app/src/main/java/com/jawahar/livesync/RelayConfig.kt")
    relay_client = read(root / "app/src/main/java/com/jawahar/livesync/RelayClient.kt")
    relay_go = "\n".join(
        p.read_text(encoding="utf-8", errors="replace")
        for p in (root / "relay").glob("*.go")
    )

    av = rx(android_proto, r"const val VERSION\s*=\s*(\d+)", int)
    ah = rx(android_proto, r"const val HEADER_BYTES\s*=\s*(\d+)", int)
    rv = rx(relay_proto, r"protocolVersion\s*=\s*(\d+)", int)
    rh = rx(relay_proto, r"audioHeaderLen\s*=\s*(\d+)", int)

    amagic = "JLA2" if all(x in android_proto for x in ["'J'", "'L'", "'A'", "'2'"]) else "UNKNOWN"
    m = re.search(r'string\(data\[0:4\]\)\s*!=\s*"([^"]+)"', relay_proto)
    rmagic = m.group(1) if m else "UNKNOWN"

    android_host_path = "/ws/host" if "/ws/host" in relay_cfg else "UNKNOWN"
    relay_host_path = "/v1/ws/host" if '"/v1/ws/host"' in relay_main else ("/ws/host" if '"/ws/host"' in relay_main else "UNKNOWN")

    android_hello = "host-hello" if '"host-hello"' in relay_client else "UNKNOWN"
    relay_hello = "hello_host" if '"hello_host"' in relay_proto else "UNKNOWN"

    checks: List[Dict] = []
    add(checks, "protocol_version", av, rv)
    add(checks, "binary_magic", amagic, rmagic)
    add(checks, "audio_header_bytes", ah, rh)
    add(checks, "host_websocket_path", android_host_path, relay_host_path)
    add(checks, "host_hello_type", android_hello, relay_hello)

    android_keys = ["protocolVersion", "room", "epoch", "resumeSequence", "sampleRate", "channels", "frameMs"]
    relay_keys = ["v", "room_id", "epoch", "resume_last_seq", "sample_rate", "channels", "frame_samples"]
    for akey, rkey in zip(android_keys, relay_keys):
        a_present = f'"{akey}"' in relay_client
        r_present = f'json:"{rkey}' in relay_proto
        same_wire_name = akey == rkey
        checks.append({
            "name": f"hello_key:{akey}->{rkey}",
            "ok": bool(a_present and r_present and same_wire_name),
            "severity": "PASS" if (a_present and r_present and same_wire_name) else "RELEASE BLOCKER",
            "android_key": akey if a_present else None,
            "relay_key": rkey if r_present else None,
        })

    for symbol in ("hostWS", "guestWS", "legacyGuestWS"):
        defined = re.search(rf"func\s+\(s\s+\*Server\)\s+{symbol}\s*\(", relay_go) is not None
        checks.append({
            "name": f"relay_handler_defined:{symbol}",
            "ok": defined,
            "severity": "PASS" if defined else "RELEASE BLOCKER",
        })

    room_defined = re.search(r"type\s+Room\s+struct", relay_go) is not None
    new_room_defined = re.search(r"func\s+newRoom\s*\(", relay_go) is not None
    checks.append({"name": "relay_Room_type_defined", "ok": room_defined, "severity": "PASS" if room_defined else "RELEASE BLOCKER"})
    checks.append({"name": "relay_newRoom_defined", "ok": new_room_defined, "severity": "PASS" if new_room_defined else "RELEASE BLOCKER"})

    failed = [c for c in checks if not c["ok"]]
    payload = {
        "kind": "STATIC-CODE CONTRACT CHECK",
        "checks": checks,
        "failed_count": len(failed),
        "pass_count": len(checks) - len(failed),
        "release_blockers": [c["name"] for c in failed if c["severity"] == "RELEASE BLOCKER"],
    }
    print(json.dumps(payload, indent=2))
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
