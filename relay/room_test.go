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
		CommonDelay:         400 * time.Millisecond,
		CommonDelayMin:      150 * time.Millisecond,
		CommonDelayMax:      1000 * time.Millisecond,
		DelayStatsTTL:       10 * time.Second,
		DelayUpdateInterval: 1 * time.Second,
		DelayUpPerSec:       60 * time.Millisecond,
		DelayDownPerSec:     10 * time.Millisecond,
		JoinGuard:           150 * time.Millisecond,
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


func TestAdaptiveCommonDelayUsesRoomP95AndSlew(t *testing.T) {
	r := newRoom(testConfig(), &Metrics{})
	g1 := &guestConn{id: 1, send: make(chan outbound, 8)}
	g2 := &guestConn{id: 2, send: make(chan outbound, 8)}
	g3 := &guestConn{id: 3, send: make(chan outbound, 8)}

	r.mu.Lock()
	r.guests[g1] = struct{}{}
	r.guests[g2] = struct{}{}
	r.guests[g3] = struct{}{}
	r.mu.Unlock()

	base := uint64(1_000_000_000)
	r.updateListenerStats(g1.id, GuestControl{RecommendedDelayMS: 300}, base)
	r.updateListenerStats(g2.id, GuestControl{RecommendedDelayMS: 450}, base)
	r.updateListenerStats(g3.id, GuestControl{RecommendedDelayMS: 700}, base)

	// First complete sample set initializes the controller clock only.
	if got := r.commonDelayMilliseconds(); got != 400 {
		t.Fatalf("initial common delay changed too early: got %.3f", got)
	}

	// One second later the nearest-rank p95 is 700 ms. Up-slew is 60 ms/s.
	r.updateListenerStats(g1.id, GuestControl{RecommendedDelayMS: 300}, base+uint64(time.Second))
	if got := r.commonDelayMilliseconds(); got < 459.9 || got > 460.1 {
		t.Fatalf("adaptive up-slew mismatch: got %.3f ms want 460 ms", got)
	}

	// Move every listener recommendation down. Down-slew is 10 ms/s.
	now := base + 2*uint64(time.Second)
	r.updateListenerStats(g1.id, GuestControl{RecommendedDelayMS: 200}, now)
	r.updateListenerStats(g2.id, GuestControl{RecommendedDelayMS: 210}, now)
	r.updateListenerStats(g3.id, GuestControl{RecommendedDelayMS: 220}, now)
	if got := r.commonDelayMilliseconds(); got < 449.9 || got > 450.1 {
		t.Fatalf("adaptive down-slew mismatch: got %.3f ms want 450 ms", got)
	}
}

func TestSubSecondRingDurationUsesPreciseSampleMath(t *testing.T) {
	cfg := testConfig()
	cfg.RingDuration = 1500 * time.Millisecond
	r := newRoom(cfg, &Metrics{})
	h := HostHello{
		Type: "hello_host", V: protocolVersion, RoomID: r.id, HostSecret: r.hostSecret,
		Epoch: 21, Codec: "opus", SampleRate: 48000, Channels: 2, Layer: 0, FrameSamples: 960,
	}
	if _, _, _, err := r.beginHost(h); err != nil {
		t.Fatal(err)
	}

	// 100 x 20 ms = 2 seconds. A 1.5 second ring should retain about 75 frames,
	// not truncate to a 1 second (50-frame) window.
	for i := uint64(0); i < 100; i++ {
		f := &AudioFrame{
			Raw: make([]byte, 65), Epoch: 21, Sequence: i + 1, SamplePosition: i * 960,
			SampleRate: 48000, FrameSamples: 960, Channels: 2, Codec: codecOpus, Layer: 0,
		}
		if err := r.acceptFrame(f, 1_000_000_000+i*20_000_000); err != nil {
			t.Fatalf("frame %d: %v", i, err)
		}
	}

	r.mu.Lock()
	n := len(r.ring)
	first := r.ring[0].SamplePosition
	r.mu.Unlock()

	if n < 74 || n > 76 {
		t.Fatalf("unexpected 1.5s ring length: got %d frames", n)
	}
	if first < 24*960 || first > 26*960 {
		t.Fatalf("unexpected first retained sample: got %d", first)
	}
}
