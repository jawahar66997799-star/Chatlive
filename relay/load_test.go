package main

import (
	"fmt"
	"runtime"
	"syscall"
	"testing"
	"time"
)

func benchCPUUS() int64 {
	var r syscall.Rusage
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &r); err != nil {
		return 0
	}
	return r.Utime.Sec*1_000_000 + r.Utime.Usec + r.Stime.Sec*1_000_000 + r.Stime.Usec
}

func TestSyntheticFanout(t *testing.T) {
	const frames = 200
	for _, guests := range []int{1, 10, 50, 100, 1000} {
		cfg := Config{
			RoomID:       "0123456789abcdef0123456789abcdef",
			HostSecret:   "abcdef0123456789abcdef0123456789",
			GuestToken:   "abcdefghijklmnopqrstuv",
			MaxGuests:    1000,
			MaxPayload:   16384,
			MaxMessage:   32768,
			MaxRingBytes: 2 * 1024 * 1024,
			RingDuration: 5 * time.Second,
			CommonDelay:  400 * time.Millisecond,
			JoinGuard:    150 * time.Millisecond,
			IdleTTL:      2 * time.Minute,
			MaxIPConns:   1200,
		}
		metrics := &Metrics{}
		r := newRoom(cfg, metrics)
		h := HostHello{
			Type: "hello_host", V: protocolVersion, RoomID: cfg.RoomID, HostSecret: cfg.HostSecret,
			Epoch: 1, Codec: "opus", SampleRate: 48000, Channels: 2, Layer: 0, FrameSamples: 960,
		}
		if _, _, _, err := r.beginHost(h); err != nil {
			t.Fatalf("guests=%d beginHost: %v", guests, err)
		}
		for i := 0; i < guests; i++ {
			g := &guestConn{id: uint64(i + 1), send: make(chan outbound, 256)}
			if err := r.addGuest(g, 1_000_000_000, 0, 0, false); err != nil {
				t.Fatalf("guests=%d addGuest[%d]: %v", guests, i, err)
			}
		}

		runtime.GC()
		var before, after runtime.MemStats
		runtime.ReadMemStats(&before)
		cpu0 := benchCPUUS()
		start := time.Now()

		for i := 0; i < frames; i++ {
			f := &AudioFrame{
				Raw:            make([]byte, 128),
				Epoch:          1,
				Sequence:       uint64(i + 1),
				SamplePosition: uint64(i * 960),
				SampleRate:     48000,
				FrameSamples:   960,
				Channels:       2,
				Codec:          codecOpus,
				Layer:          0,
			}
			if err := r.acceptFrame(f, 1_000_000_000+uint64(i)*20_000_000); err != nil {
				t.Fatalf("guests=%d frame=%d: %v", guests, i, err)
			}
		}

		elapsed := time.Since(start)
		cpuUS := benchCPUUS() - cpu0
		runtime.ReadMemStats(&after)
		ops := int64(guests * frames)
		simTX := int64(guests * frames * 128)
		heapDelta := int64(after.HeapAlloc) - int64(before.HeapAlloc)
		opsPerSec := float64(ops) / elapsed.Seconds()

		fmt.Printf("[SIMULATED] guests=%d frames=%d fanout_ops=%d elapsed_ms=%.3f cpu_ms=%.3f ops_per_sec=%.0f heap_delta_bytes=%d simulated_tx_bytes=%d backpressure_drops=%d\n",
			guests, frames, ops, float64(elapsed.Microseconds())/1000.0, float64(cpuUS)/1000.0,
			opsPerSec, heapDelta, simTX, metrics.backpressureDrops.Load())
	}
}
