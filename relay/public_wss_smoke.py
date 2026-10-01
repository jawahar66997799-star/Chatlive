import asyncio
import json
import os
import struct
import time

import websockets

base = os.environ["PUBLIC_BASE"].rstrip("/")
wsbase = ("wss://" + base[len("https://"):]) if base.startswith("https://") else base
room = os.environ["ROOM"]
host_secret = os.environ["HOST_SECRET"]
guest_token = os.environ["GUEST_TOKEN"]
wire = ">4sBBBBQQQQQIHBBBBHI"

async def main():
    epoch = (time.monotonic_ns() & ((1 << 63) - 1)) or 1
    async with websockets.connect(wsbase + "/v1/ws/host", open_timeout=15, ping_interval=None) as host:
        await host.send(json.dumps({
            "type":"hello_host","v":1,"room_id":room,"host_secret":host_secret,
            "epoch":epoch,"codec":"opus","sample_rate":48000,"channels":2,
            "layer":0,"frame_samples":960
        }))
        host_ack = json.loads(await asyncio.wait_for(host.recv(), 10))
        async with websockets.connect(wsbase + "/v1/ws/guest/" + guest_token, open_timeout=15, ping_interval=None) as guest:
            state = json.loads(await asyncio.wait_for(guest.recv(), 10))
            t0 = time.monotonic_ns()
            await guest.send(json.dumps({"type":"clock_req","v":1,"id":"smoke","t0_guest_ns":t0}))
            clock = None
            binary = 0
            clock_deadline = time.monotonic() + 10
            while time.monotonic() < clock_deadline and clock is None:
                msg = await asyncio.wait_for(guest.recv(), 2)
                if isinstance(msg, bytes):
                    binary += 1
                else:
                    parsed = json.loads(msg)
                    if parsed.get("type") == "clock_resp":
                        clock = parsed
            if clock is None:
                raise RuntimeError("clock_resp not received")
            payload = bytes(120)
            for seq in range(12):
                hdr = struct.pack(wire,b"JLS1",1,1,0,64,epoch,seq,time.monotonic_ns(),seq*960,0,48000,960,2,1,0,0,len(payload),0)
                await host.send(hdr + payload)
                await asyncio.sleep(0.02)
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline and binary < 1:
                msg = await asyncio.wait_for(guest.recv(), 1)
                if isinstance(msg, bytes):
                    binary += 1
            print("PUBLIC_WSS_SMOKE", json.dumps({
                "host_ack": host_ack.get("type") == "hello_host_ack",
                "guest_state": state.get("type") == "state",
                "clock_resp": clock.get("type") == "clock_resp",
                "binary_frames": binary,
                "epoch": epoch
            }, sort_keys=True), flush=True)

asyncio.run(main())
