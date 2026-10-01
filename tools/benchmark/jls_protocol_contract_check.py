#!/usr/bin/env python3
"""Static Android↔relay↔guest contract gate for current Jawahar Live Sync."""
from __future__ import annotations
import argparse, json, re
from pathlib import Path

def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace")

def add(out, name, ok, detail):
    out.append({"name": name, "ok": bool(ok), "severity": "PASS" if ok else "RELEASE BLOCKER", "detail": detail})

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--json-out", default="")
    args = ap.parse_args()
    root = Path(args.root)

    android_proto = read(root/"app/src/main/java/com/jawahar/livesync/JlsProtocol.kt")
    android_client = read(root/"app/src/main/java/com/jawahar/livesync/RelayClient.kt")
    android_cfg = read(root/"app/src/main/java/com/jawahar/livesync/RelayConfig.kt")
    relay_proto = read(root/"relay/protocol.go")
    relay_main = read(root/"relay/main.go")
    guest_worker = read(root/"relay/web/decoder-worker.js")
    guest_player = read(root/"relay/web/player.js")
    all_go = "\n".join(read(p) for p in (root/"relay").glob("*.go"))

    checks = []
    add(checks, "binary_magic",
        all(x in android_proto for x in ["'J'","'L'","'S'","'1'"]) and '"JLS1"' in relay_proto and "magic!=='JLS1'" in guest_worker,
        "Android, relay, and guest all use JLS1.")
    add(checks, "protocol_version",
        re.search(r"const val VERSION\s*=\s*1\b", android_proto) is not None and
        re.search(r"protocolVersion\s*=\s*1\b", relay_proto) is not None and
        "dv.getUint8(4)===1" in guest_worker,
        "All endpoints use protocol v1.")
    add(checks, "audio_header_64",
        re.search(r"HEADER_BYTES\s*=\s*64\b", android_proto) is not None and
        re.search(r"audioHeaderLen\s*=\s*64\b", relay_proto) is not None and
        "dv.byteLength>=64" in guest_worker,
        "All endpoints use a 64-byte audio header.")
    add(checks, "opus_codec_id",
        "CODEC_OPUS = 1" in android_proto and "codecOpus = 0x01" in relay_proto and "codecId!==1" in guest_worker,
        "Opus codec ID agrees across Android/relay/guest.")
    add(checks, "relay_ingress_field",
        "out.putLong(0L)" in android_proto and "data[40:48]" in relay_proto and "getBigUint64(40,false)" in guest_worker,
        "relay_ingress_ns occupies bytes 40..47.")
    add(checks, "payload_length_field",
        "putShort(frame.payload.size.toShort())" in android_proto and "data[58:60]" in relay_proto and "getUint16(58,false)" in guest_worker,
        "payload length occupies bytes 58..59.")
    add(checks, "host_path",
        '"/v1/ws/host"' in relay_main and '"/v1/ws/host"' in android_cfg,
        "Android dials relay /v1/ws/host.")
    required_android = ['"hello_host"','"v"','"room_id"','"host_secret"','"epoch"','"codec"','"sample_rate"','"channels"','"layer"','"frame_samples"']
    required_relay = ['json:"v"','json:"room_id"','json:"host_secret"','json:"epoch"','json:"codec"','json:"sample_rate"','json:"channels"','json:"layer"','json:"frame_samples"']
    add(checks, "host_hello_schema",
        all(k in android_client for k in required_android) and all(k in relay_proto for k in required_relay),
        "Android hello JSON keys match relay HostHello tags.")
    add(checks, "guest_clock_exchange",
        "clock_req" in guest_player and "t0_guest_ns" in guest_player and "clock_resp" in guest_player and "t1_server_ns" in guest_player and "t2_server_ns" in guest_player,
        "Guest implements repeated NTP-style clock exchange.")
    add(checks, "guest_adaptive_delay",
        "AdaptiveDelay" in guest_player and "floorMs:150" in guest_player and "ceilingMs:1000" in guest_player,
        "Guest has bounded adaptive delay.")
    add(checks, "guest_sync_metrics",
        all(k in guest_player for k in ["hardResyncs","resamplerPpm","underruns","lateFrames","clockDriftPpm"]),
        "Guest exposes synchronization metrics.")
    add(checks, "guest_output_timing",
        all(k in guest_player for k in ["getOutputTimestamp","outputLatency","baseLatency"]),
        "Guest instruments browser output timing.")
    for sym in ("hostWS","guestWS","legacyGuestWS"):
        add(checks, f"relay_handler:{sym}",
            re.search(rf"func\s+\(s\s+\*Server\)\s+{sym}\s*\(", all_go) is not None,
            f"Relay handler {sym} exists.")
    add(checks, "room_implementation",
        re.search(r"type\s+Room\s+struct", all_go) is not None and re.search(r"func\s+newRoom\s*\(", all_go) is not None,
        "Relay Room and newRoom exist.")

    failed = [c for c in checks if not c["ok"]]
    result = {
        "kind": "STATIC-CODE CONTRACT CHECK",
        "checks": checks,
        "failed_count": len(failed),
        "pass_count": len(checks)-len(failed),
        "release_blockers": [c["name"] for c in failed],
    }
    print(json.dumps(result, indent=2))
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(result, indent=2), encoding="utf-8")
    return 1 if failed else 0

if __name__ == "__main__":
    raise SystemExit(main())
