package main

import (
	"crypto/subtle"
	"errors"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

type outboundKind uint8

const (
	outboundText outboundKind = iota + 1
	outboundBinary
	outboundClock
)

type outbound struct {
	kind       outboundKind
	data       []byte
	clockID    string
	t0GuestNS  uint64
	t1ServerNS uint64
}

type guestConn struct {
	id      uint64
	conn    *websocket.Conn
	send    chan outbound
	closed  atomic.Bool
	dropped atomic.Uint64
}

type Room struct {
	mu sync.Mutex

	id         string
	hostSecret string
	guestToken string

	hostOnline bool
	hostGen    uint64
	offlineAt  time.Time

	epoch        uint64
	lastSeq      uint64
	lastSample   uint64
	haveFrame    bool
	sampleRate   uint32
	channels     uint8
	frameSamples uint16
	layer        uint8

	originServerNS uint64
	originSample   uint64

	ring      []*AudioFrame
	ringBytes int

	guests map[*guestConn]struct{}

	maxRingBytes  int
	ringDuration  time.Duration
	commonDelayNS uint64
	joinGuardNS   uint64
	maxGuests     int

	metrics *Metrics
}

func newRoom(cfg Config, metrics *Metrics) *Room {
	return &Room{
		id:            cfg.RoomID,
		hostSecret:    cfg.HostSecret,
		guestToken:    cfg.GuestToken,
		guests:        make(map[*guestConn]struct{}),
		maxRingBytes:  cfg.MaxRingBytes,
		ringDuration:  cfg.RingDuration,
		commonDelayNS: uint64(cfg.CommonDelay.Nanoseconds()),
		joinGuardNS:   uint64(cfg.JoinGuard.Nanoseconds()),
		maxGuests:     cfg.MaxGuests,
		sampleRate:    48000,
		channels:      2,
		frameSamples:  960,
		metrics:       metrics,
	}
}

func secureEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

func (r *Room) authHost(roomID, secret string) bool {
	return secureEqual(r.id, roomID) && secureEqual(r.hostSecret, secret)
}

func (r *Room) authGuest(token string) bool {
	return secureEqual(r.guestToken, token)
}

func (r *Room) beginHost(h HostHello) (generation uint64, resumeAfter uint64, epochChanged bool, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.hostOnline {
		return 0, 0, false, errors.New("host already connected")
	}

	r.hostGen++
	generation = r.hostGen
	r.hostOnline = true
	r.offlineAt = time.Time{}

	if r.epoch != h.Epoch {
		r.resetEpochLocked(h)
		epochChanged = true
	} else {
		r.sampleRate = h.SampleRate
		r.channels = h.Channels
		r.frameSamples = h.FrameSamples
		r.layer = h.Layer
	}

	resumeAfter = r.lastSeq
	return generation, resumeAfter, epochChanged, nil
}

func (r *Room) resetEpochLocked(h HostHello) {
	r.epoch = h.Epoch
	r.lastSeq = 0
	r.lastSample = 0
	r.haveFrame = false
	r.sampleRate = h.SampleRate
	r.channels = h.Channels
	r.frameSamples = h.FrameSamples
	r.layer = h.Layer
	r.originServerNS = 0
	r.originSample = 0
	r.ring = nil
	r.ringBytes = 0
	r.metrics.epochChanges.Add(1)
}

func (r *Room) endHost(generation uint64) {
	r.mu.Lock()
	if generation == r.hostGen && r.hostOnline {
		r.hostOnline = false
		r.offlineAt = time.Now()
		r.broadcastStateLocked("host_offline")
	}
	r.mu.Unlock()
}

func (r *Room) acceptFrame(f *AudioFrame, serverNS uint64) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if !r.hostOnline {
		return errors.New("host is not online")
	}
	if f.Epoch != r.epoch {
		r.metrics.rejectedFrames.Add(1)
		return errors.New("epoch mismatch")
	}
	if f.SampleRate != r.sampleRate || f.Channels != r.channels || f.FrameSamples != r.frameSamples || f.Codec != codecOpus || f.Layer != r.layer {
		r.metrics.rejectedFrames.Add(1)
		return errors.New("audio metadata mismatch")
	}
	if r.haveFrame {
		if f.Sequence <= r.lastSeq {
			r.metrics.replayRejected.Add(1)
			return errors.New("non-increasing sequence")
		}
		if f.SamplePosition <= r.lastSample {
			r.metrics.replayRejected.Add(1)
			return errors.New("non-increasing sample position")
		}
	}

	firstFrame := !r.haveFrame
	f.stampRelayIngress(serverNS)
	if firstFrame {
		r.originServerNS = serverNS
		r.originSample = f.SamplePosition
	}
	if f.SamplePosition < r.originSample {
		r.metrics.rejectedFrames.Add(1)
		return errors.New("sample position precedes room origin")
	}

	sampleDelta := f.SamplePosition - r.originSample
	f.NominalServerNS = r.originServerNS + (sampleDelta*1_000_000_000)/uint64(r.sampleRate)

	r.lastSeq = f.Sequence
	r.lastSample = f.SamplePosition
	r.haveFrame = true
	r.ring = append(r.ring, f)
	r.ringBytes += len(f.Raw)
	r.evictLocked()

	r.metrics.audioFrames.Add(1)
	r.metrics.audioBytes.Add(uint64(len(f.Raw)))

	// Guests that connected before the first media frame initially know only
	// that the host is online. Publish the real sample/server anchor before
	// the first binary frame so every guest schedules against the same epoch
	// timeline instead of a zero/uninitialized origin.
	if firstFrame {
		r.broadcastStateLocked("timeline_started")
	}

	for g := range r.guests {
		if g.closed.Load() {
			continue
		}
		select {
		case g.send <- outbound{kind: outboundBinary, data: f.Raw}:
		default:
			g.dropped.Add(1)
			r.metrics.backpressureDrops.Add(1)
			g.closed.Store(true)
			_ = g.conn.Close()
		}
	}
	return nil
}

func (r *Room) evictLocked() {
	if len(r.ring) == 0 {
		return
	}
	minSample := uint64(0)
	if r.lastSample > uint64(r.sampleRate)*uint64(r.ringDuration/time.Second) {
		minSample = r.lastSample - uint64(r.sampleRate)*uint64(r.ringDuration/time.Second)
	}
	idx := 0
	for idx < len(r.ring) {
		if r.ringBytes <= r.maxRingBytes && r.ring[idx].SamplePosition >= minSample {
			break
		}
		r.ringBytes -= len(r.ring[idx].Raw)
		idx++
	}
	if idx > 0 {
		copy(r.ring, r.ring[idx:])
		r.ring = r.ring[:len(r.ring)-idx]
	}
}

func (r *Room) addGuest(g *guestConn, nowNS uint64, resumeEpoch, resumeSeq uint64, hasResume bool) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if len(r.guests) >= r.maxGuests {
		return errors.New("room guest limit reached")
	}
	r.guests[g] = struct{}{}
	r.metrics.guestsCurrent.Add(1)
	r.metrics.guestJoins.Add(1)

	state, startSeq := r.stateLocked(nowNS, resumeEpoch, resumeSeq, hasResume)
	if !enqueue(g, outbound{kind: outboundText, data: encodeJSON(state)}) {
		delete(r.guests, g)
		r.metrics.guestsCurrent.Add(-1)
		return errors.New("guest queue full during state")
	}

	if r.hostOnline && r.haveFrame {
		for _, f := range r.ring {
			if f.Sequence < startSeq {
				continue
			}
			if !enqueue(g, outbound{kind: outboundBinary, data: f.Raw}) {
				delete(r.guests, g)
				r.metrics.guestsCurrent.Add(-1)
				return errors.New("guest queue full during ring replay")
			}
		}
	}
	return nil
}

func (r *Room) removeGuest(g *guestConn) {
	r.mu.Lock()
	if _, ok := r.guests[g]; ok {
		delete(r.guests, g)
		r.metrics.guestsCurrent.Add(-1)
	}
	r.mu.Unlock()
}

func enqueue(g *guestConn, o outbound) bool {
	if g.closed.Load() {
		return false
	}
	select {
	case g.send <- o:
		return true
	default:
		return false
	}
}

func (r *Room) stateLocked(nowNS uint64, resumeEpoch, resumeSeq uint64, hasResume bool) (map[string]any, uint64) {
	earliest := uint64(0)
	head := uint64(0)
	startSeq := uint64(0)
	startDeadline := uint64(0)

	if len(r.ring) > 0 {
		earliest = r.ring[0].Sequence
		head = r.ring[len(r.ring)-1].Sequence

		// The baseline start point is the first frame whose common playout
		// deadline is still safely in the future. This prevents both late join
		// and reconnect from replaying stale audio simply because it remains in
		// the bounded retransmission ring.
		threshold := nowNS + r.joinGuardNS
		futureSeq := head + 1
		futureDeadline := uint64(0)
		for _, f := range r.ring {
			deadline := f.NominalServerNS + r.commonDelayNS
			if deadline >= threshold {
				futureSeq = f.Sequence
				futureDeadline = deadline
				break
			}
		}
		startSeq = futureSeq
		startDeadline = futureDeadline

		if hasResume && resumeEpoch == r.epoch && resumeSeq < head {
			candidate := resumeSeq + 1
			if candidate < earliest {
				candidate = earliest
			}
			// Resume may advance beyond the future baseline, but never behind
			// it. Missed audio whose common deadline has passed is discarded.
			if candidate >= futureSeq && candidate <= head {
				startSeq = candidate
				for _, f := range r.ring {
					if f.Sequence == startSeq {
						startDeadline = f.NominalServerNS + r.commonDelayNS
						break
					}
				}
			}
		}
	}

	return map[string]any{
		"type":                    "state",
		"v":                       protocolVersion,
		"host_online":             r.hostOnline,
		"timeline_ready":          r.haveFrame,
		"room_id":                 r.id,
		"epoch":                   r.epoch,
		"earliest_seq":            earliest,
		"head_seq":                head,
		"start_seq":               startSeq,
		"start_playout_server_ns": startDeadline,
		"server_now_ns":           nowNS,
		"timeline": map[string]any{
			"origin_server_ns":       r.originServerNS,
			"origin_sample_position": r.originSample,
			"sample_rate":            r.sampleRate,
			"channels":               r.channels,
			"frame_samples":          r.frameSamples,
			"codec":                  "opus",
			"layer":                  r.layer,
			"recommended_delay_ns":   r.commonDelayNS,
		},
	}, startSeq
}

func (r *Room) broadcastStateLocked(reason string) {
	nowNS := serverNS()
	state, _ := r.stateLocked(nowNS, 0, 0, false)
	state["reason"] = reason
	b := encodeJSON(state)
	for g := range r.guests {
		if !enqueue(g, outbound{kind: outboundText, data: b}) {
			r.metrics.backpressureDrops.Add(1)
		}
	}
}

func (r *Room) broadcastState(reason string) {
	r.mu.Lock()
	r.broadcastStateLocked(reason)
	r.mu.Unlock()
}

func (r *Room) cleanupIfIdle(ttl time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.hostOnline || len(r.guests) > 0 || r.offlineAt.IsZero() || time.Since(r.offlineAt) < ttl {
		return
	}
	r.ring = nil
	r.ringBytes = 0
	r.haveFrame = false
	r.lastSeq = 0
	r.lastSample = 0
	r.originServerNS = 0
	r.originSample = 0
}
