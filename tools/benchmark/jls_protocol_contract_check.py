#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, re
from pathlib import Path

def read(p): return Path(p).read_text(encoding="utf-8",errors="replace")
def chk(name,ok,detail,checks):
    checks.append({"name":name,"ok":bool(ok),"severity":"PASS" if ok else "RELEASE BLOCKER","detail":detail})

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--root",default="."); ap.add_argument("--json-out",default="")
    a=ap.parse_args(); root=Path(a.root)
    jp=read(root/"app/src/main/java/com/jawahar/livesync/JlsProtocol.kt")
    rc=read(root/"app/src/main/java/com/jawahar/livesync/RelayClient.kt")
    cfg=read(root/"app/src/main/java/com/jawahar/livesync/RelayConfig.kt")
    rp=read(root/"relay/protocol.go"); rm=read(root/"relay/main.go")
    guest=read(root/"relay/web/decoder-worker.js")
    checks=[]
    chk("protocol_version", "const val VERSION = 1" in jp and "protocolVersion = 1" in rp, "Android and relay must both use v1", checks)
    chk("magic_JLS1", all(x in jp for x in ["'J'","'L'","'S'","'1'"]) and '"JLS1"' in rp and "magic!=='JLS1'" in guest, "Android/relay/guest magic", checks)
    chk("header_64", "HEADER_BYTES = 64" in jp and "audioHeaderLen  = 64" in rp and "headerBytes!==64" in guest, "64-byte audio header", checks)
    chk("host_path", '"/v1/ws/host"' in rm and '"/v1/ws/host"' in cfg, "Android URL and relay route", checks)
    keys=["hello_host","\"v\"","\"room_id\"","\"host_secret\"","\"epoch\"","\"codec\"","\"sample_rate\"","\"channels\"","\"layer\"","\"frame_samples\""]
    chk("host_hello_keys", all(k in rc for k in keys), "Android host hello canonical keys", checks)
    tags=["json:\"v\"","json:\"room_id\"","json:\"host_secret\"","json:\"epoch\"","json:\"codec\"","json:\"sample_rate\"","json:\"channels\"","json:\"layer\"","json:\"frame_samples\""]
    chk("relay_hello_tags", all(k in rp for k in tags) and 'h.Type != "hello_host"' in rp, "Relay decoder canonical keys/type", checks)
    chk("guest_decoder_wire", all(s in guest for s in ["version!==1","messageType!==1","headerBytes!==64","sr!==48000","ch!==2","codecId!==1"]), "Guest decoder validates same wire metadata", checks)
    payload={"kind":"STATIC-CODE CONTRACT CHECK","checks":checks,"failed_count":sum(not x["ok"] for x in checks),"pass_count":sum(x["ok"] for x in checks)}
    print(json.dumps(payload,indent=2))
    if a.json_out: Path(a.json_out).write_text(json.dumps(payload,indent=2),encoding="utf-8")
    return 1 if payload["failed_count"] else 0
if __name__=="__main__": raise SystemExit(main())
