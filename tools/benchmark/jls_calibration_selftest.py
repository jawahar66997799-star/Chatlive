#!/usr/bin/env python3
"""Synthetic self-test for jls_calibration.py. All results are [SIMULATED]."""
from __future__ import annotations
import json, struct, subprocess, sys, tempfile, wave
from pathlib import Path


def read_pcm16(path: Path):
    with wave.open(str(path),"rb") as w:
        assert w.getnchannels()==1 and w.getsampwidth()==2
        sr=w.getframerate(); raw=w.readframes(w.getnframes())
    vals=list(struct.unpack("<"+"h"*(len(raw)//2),raw))
    return sr, vals


def write_mix(path: Path, sr: int, samples):
    with wave.open(str(path),"wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        clipped=[max(-32768,min(32767,int(x))) for x in samples]
        w.writeframes(struct.pack("<"+"h"*len(clipped),*clipped))


def main() -> int:
    tool=Path(__file__).with_name("jls_calibration.py")
    with tempfile.TemporaryDirectory() as td:
        root=Path(td); chirps=root/"chirps"
        subprocess.check_call([sys.executable,str(tool),"generate","--devices","3","--out-dir",str(chirps)])
        manifest=json.loads((chirps/"manifest.json").read_text())
        known={"device-00":0.0,"device-01":7.5,"device-02":-4.0}
        base_recording_offset_ms=350.0
        sr=48000
        mix=[0]*int(sr*6.0)
        for d in manifest["devices"]:
            csr, pcm=read_pcm16(chirps/d["chirp_file"]); assert csr==sr
            t=float(d["scheduled_server_ms"])+base_recording_offset_ms+known[d["device_id"]]
            start=round(t*sr/1000.0)
            for j,v in enumerate(pcm):
                if start+j<len(mix): mix[start+j]+=v
        rec=root/"recording.wav"; write_mix(rec,sr,mix)
        out=root/"profile.json"
        subprocess.check_call([sys.executable,str(tool),"analyze","--manifest",str(chirps/"manifest.json"),"--recording",str(rec),"--out",str(out)])
        profile=json.loads(out.read_text())
        worst=0.0
        for d in profile["devices"]:
            got=float(d["relative_speaker_arrival_offset_ms"])
            exp=known[d["device_id"]]-known["device-00"]
            err=abs(got-exp); worst=max(worst,err)
            print(f"[SIMULATED] {d['device_id']} expected={exp:.3f} ms recovered={got:.3f} ms error={err:.3f} ms")
        if worst>1.0:
            raise SystemExit(f"calibration self-test failed; worst error {worst:.3f} ms")
        print(f"[SIMULATED] calibration self-test PASS worst_error={worst:.3f} ms")
    return 0


if __name__=="__main__":
    raise SystemExit(main())
