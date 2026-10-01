package main

import (
	"testing"
	"time"
)

func testConfig() Config {
	return Config{
		RoomID:       "0123456789abcdef0123456789abcdef",
		HostSecret:   "abcdef0123456789abcdef0123456789",
		GuestToken:   "abcdefghijklmnopqrstuv",
		MaxGuests:    250,
		MaxPayload:   16384,
		MaxMessage:   32768,
		MaxRingBytes: 2 * 1024 * 1024,
		RingDuration: 5 * time.Second,
		CommonDelay:  400 * time.Millisecond,
		JoinGuard:    150 * time.Millisecond,
		IdleTTL:      2 * time.Minute,
		MaxIPConns:   32,
	}
}

func TestRoomRejectsReplay(t *testing.T) {
	r := newRoom(testConfig(), &Metrics{})
	h := HostHello{
		Type: "hello_host", V: protocolVersion, RoomID: r.id, HostSecret: r.hostSecret,
		Epoch: 7, Codec: "opus", SampleRate: 48000, Channels: 2, Layer: 0, FrameSamples: 960,
	}
	if _, _, _, err := r.beginHost(h); err != nil {
		t.Fatal(err)
	}
	f1 := &AudioFrame{
		Raw: make([]byte, 65), Epoch: 7, Sequence: 1, SamplePosition: 0,
		SampleRate: 48000, FrameSamples: 960, Channels: 2, Codec: codecOpus, Layer: 0,
	}
	if err := r.acceptFrame(f1, 1_000_000_000); err != nil {
		t.Fatal(err)
	}
	f2 := &AudioFrame{
		Raw: make([]byte, 65), Epoch: 7, Sequence: 1, SamplePosition: 960,
		SampleRate: 48000, FrameSamples: 960, Channels: 2, Codec: codecOpus, Layer: 0,
	}
	if err := r.acceptFrame(f2, 1_020_000_000); err == nil {
		t.Fatal("expected replay rejection")
	}
}

func TestResumeNeverReplaysPastCommonDeadline(t *testing.T) {
	r := newRoom(testConfig(), &Metrics{})
	h := HostHello{
		Type: "hello_host", V: protocolVersion, RoomID: r.id, HostSecret: r.hostSecret,
		Epoch: 11, Codec: "opus", SampleRate: 48000, Channels: 2, Layer: 0, FrameSamples: 960,
	}
	if _, _, _, err := r.beginHost(h); err != nil {
		t.Fatal(err)
	}
	for i := uint64(0); i < 100; i++ {
		f := &AudioFrame{
			Raw: make([]byte, 65), Epoch: 11, Sequence: i + 1, SamplePosition: i * 960,
			SampleRate: 48000, FrameSamples: 960, Channels: 2, Codec: codecOpus, Layer: 0,
		}
		if err := r.acceptFrame(f, 1_000_000_000+i*20_000_000); err != nil {
			t.Fatalf("frame %d: %v", i, err)
		}
	}

	now := uint64(2_200_000_000)
	r.mu.Lock()
	state, startSeq := r.stateLocked(now, 11, 1, true)
	r.mu.Unlock()

	if startSeq <= 1 {
		t.Fatalf("stale resume was not advanced: startSeq=%d", startSeq)
	}
	deadline, ok := state["start_playout_server_ns"].(uint64)
	if !ok {
		t.Fatalf("missing deadline type: %#v", state["start_playout_server_ns"])
	}
	if deadline != 0 && deadline < now+uint64(testConfig().JoinGuard.Nanoseconds()) {
		t.Fatalf("deadline is stale: got %d threshold %d", deadline, now+uint64(testConfig().JoinGuard.Nanoseconds()))
	}
}
