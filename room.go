package main

import (
	"crypto/subtle"
	"errors"
	"math"
	"sort"
	"strconv"
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

const (
	streamConnectedNoHost      = "CONNECTED_NO_HOST"
	streamHostConnectedNoAudio = "HOST_CONNECTED_NO_AUDIO"
	streamAudioFlowing         = "AUDIO_FLOWING"
	streamHostStalled          = "HOST_STALLED"
)

type outbound struct {
	kind       outboundKind
	data       []byte
	clockID    string
	t0GuestNS  uint64
	t1ServerNS uint64
	deadlineNS uint64
}

type guestConn struct {
	id      uint64
	conn    *websocket.Conn
	send    chan outbound
	closed  atomic.Bool
	dropped atomic.Uint64
}

type listenerStat struct {
	recommendedDelayMS float64
	updatedNS          uint64
	underruns          uint64
	lateFrames         uint64
	hardResyncs        uint64
}

type Room struct {
	mu sync.Mutex

	id                    string
	hostSecret            string
	guestToken            string
	publicRoomCode        string
	defaultPublicRoomCode string

	hostOnline        bool
	hostGen           uint64
	offlineAt         time.Time
	streamState       string
	hostAdvisoryState string
	lastAudioNS       uint64
	hostStallAfterNS  uint64

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

	guests        map[*guestConn]struct{}
	listenerStats map[uint64]listenerStat

	maxRingBytes       int
	ringDuration       time.Duration
	commonDelayNS      uint64
	defaultDelayNS     uint64
	delayMinNS         uint64
	delayMaxNS         uint64
	delayStatsTTLNS    uint64
	delayUpdateNS      uint64
	delayUpNSPerSec    uint64
	delayDownNSPerSec  uint64
	lastDelayAdjustNS  uint64
	joinGuardNS        uint64
	maxGuests          int

	metrics *Metrics
}

func newRoom(cfg Config, metrics *Metrics) *Room {
	return &Room{
		id:                    cfg.RoomID,
		hostSecret:            cfg.HostSecret,
		guestToken:            cfg.GuestToken,
		publicRoomCode:        cfg.PublicRoomCode,
		defaultPublicRoomCode: cfg.PublicRoomCode,
		guests:           make(map[*guestConn]struct{}),
		listenerStats:    make(map[uint64]listenerStat),
		maxRingBytes:     cfg.MaxRingBytes,
		ringDuration:     cfg.RingDuration,
		commonDelayNS:    uint64(cfg.CommonDelay.Nanoseconds()),
		defaultDelayNS:   uint64(cfg.CommonDelay.Nanoseconds()),
		delayMinNS:       uint64(cfg.CommonDelayMin.Nanoseconds()),
		delayMaxNS:       uint64(cfg.CommonDelayMax.Nanoseconds()),
		delayStatsTTLNS:  uint64(cfg.DelayStatsTTL.Nanoseconds()),
		delayUpdateNS:    uint64(cfg.DelayUpdateInterval.Nanoseconds()),
		delayUpNSPerSec:  uint64(cfg.DelayUpPerSec.Nanoseconds()),
		delayDownNSPerSec:uint64(cfg.DelayDownPerSec.Nanoseconds()),
		joinGuardNS:      uint64(cfg.JoinGuard.Nanoseconds()),
		hostStallAfterNS: uint64(cfg.HostStallAfter.Nanoseconds()),
		streamState:      streamConnectedNoHost,
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

func (r *Room) publicRoomCodeOK(code string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.publicRoomCode != "" &&
		validPublicRoomCode(code) &&
		secureEqual(code, r.publicRoomCode)
}

func (r *Room) currentPublicRoomCode() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.publicRoomCode
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
	if h.PublicRoomCode != "" {
		r.publicRoomCode = h.PublicRoomCode
	} else {
		r.publicRoomCode = r.defaultPublicRoomCode
	}
	r.offlineAt = time.Time{}
	r.lastAudioNS = 0
	r.streamState = streamHostConnectedNoAudio

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
	r.lastAudioNS = 0
	r.ring = nil
	r.ringBytes = 0
	r.metrics.epochChanges.Add(1)
}

func (r *Room) endHost(generation uint64) {
	r.mu.Lock()
	if generation == r.hostGen && r.hostOnline {
		r.hostOnline = false
		r.offlineAt = time.Now()
		r.streamState = streamConnectedNoHost
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
	previousStreamState := r.streamState
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
	r.lastAudioNS = serverNS
	r.haveFrame = true
	r.streamState = streamAudioFlowing
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
	} else if previousStreamState != streamAudioFlowing {
		r.broadcastStateLocked("audio_resumed")
	}

	deadlineNS := f.NominalServerNS + r.commonDelayNS
	for g := range r.guests {
		if g.closed.Load() {
			continue
		}
		select {
		case g.send <- outbound{kind: outboundBinary, data: f.Raw, deadlineNS: deadlineNS}:
		default:
			g.dropped.Add(1)
			r.metrics.backpressureDrops.Add(1)
			r.metrics.slowGuestDisconnects.Add(1)
			go closeGuestTooSlow(g)
		}
	}
	return nil
}

func (r *Room) evictLocked() {
	if len(r.ring) == 0 {
		return
	}
	minSample := uint64(0)
	ringSamples := (uint64(r.sampleRate) * uint64(r.ringDuration)) / uint64(time.Second)
	if r.lastSample > ringSamples {
		minSample = r.lastSample - ringSamples
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
			if !enqueue(g, outbound{kind: outboundBinary, data: f.Raw, deadlineNS: f.NominalServerNS + r.commonDelayNS}) {
				delete(r.guests, g)
				r.metrics.guestsCurrent.Add(-1)
				return errors.New("guest queue full during ring replay")
			}
		}
	}
	return nil
}

func closeGuestTooSlow(g *guestConn) {
	if g.closed.Swap(true) {
		return
	}
	_ = g.conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(1013, streamGuestTooSlow), time.Now().Add(250*time.Millisecond))
	_ = g.conn.Close()
}

const streamGuestTooSlow = "GUEST_TOO_SLOW"

func (r *Room) removeGuest(g *guestConn) {
	r.mu.Lock()
	if _, ok := r.guests[g]; ok {
		delete(r.guests, g)
		delete(r.listenerStats, g.id)
		r.metrics.guestsCurrent.Add(-1)
	}
	r.mu.Unlock()
}

func (r *Room) updateHostState(state string) {
	if state == "" || len(state) > 64 {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if state == r.hostAdvisoryState {
		return
	}
	r.hostAdvisoryState = state
	r.broadcastStateLocked("host_state")
}

func (r *Room) refreshLiveness(nowNS uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.hostOnline || !r.haveFrame || r.lastAudioNS == 0 || nowNS <= r.lastAudioNS {
		return
	}
	if nowNS-r.lastAudioNS < r.hostStallAfterNS || r.streamState == streamHostStalled {
		return
	}
	r.streamState = streamHostStalled
	r.metrics.hostStalls.Add(1)
	r.broadcastStateLocked("host_stalled")
}

func (r *Room) updateListenerStats(guestID uint64, m GuestControl, nowNS uint64) {
	if math.IsNaN(m.RecommendedDelayMS) || math.IsInf(m.RecommendedDelayMS, 0) || m.RecommendedDelayMS <= 0 {
		return
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	// guestID originates from the live authenticated guest connection.
	// removeGuest deletes its stat on teardown, so no O(N) guest-map scan is needed here.
	minMS := float64(r.delayMinNS) / 1e6
	maxMS := float64(r.delayMaxNS) / 1e6
	rec := math.Max(minMS, math.Min(maxMS, m.RecommendedDelayMS))
	r.listenerStats[guestID] = listenerStat{
		recommendedDelayMS: rec,
		updatedNS:          nowNS,
		underruns:          m.Underruns,
		lateFrames:         m.LateFrames,
		hardResyncs:        m.HardResyncs,
	}

	if r.adjustCommonDelayLocked(nowNS) {
		r.broadcastStateLocked("delay_updated")
	}
}

func (r *Room) adjustCommonDelayLocked(nowNS uint64) bool {
	if r.delayStatsTTLNS == 0 || r.delayUpdateNS == 0 {
		return false
	}
	for id, st := range r.listenerStats {
		if nowNS > st.updatedNS && nowNS-st.updatedNS > r.delayStatsTTLNS {
			delete(r.listenerStats, id)
		}
	}
	if len(r.listenerStats) == 0 {
		return false
	}
	if r.lastDelayAdjustNS == 0 {
		r.lastDelayAdjustNS = nowNS
		return false
	}
	if nowNS <= r.lastDelayAdjustNS || nowNS-r.lastDelayAdjustNS < r.delayUpdateNS {
		return false
	}

	values := make([]float64, 0, len(r.listenerStats))
	for _, st := range r.listenerStats {
		values = append(values, st.recommendedDelayMS)
	}
	sort.Float64s(values)
	idx := int(math.Ceil(0.95*float64(len(values)))) - 1
	if idx < 0 {
		idx = 0
	}
	if idx >= len(values) {
		idx = len(values) - 1
	}
	targetNS := uint64(values[idx] * 1e6)
	if targetNS < r.delayMinNS {
		targetNS = r.delayMinNS
	}
	if targetNS > r.delayMaxNS {
		targetNS = r.delayMaxNS
	}

	current := r.commonDelayNS
	const hysteresisNS = uint64(10 * time.Millisecond)
	if targetNS > current && targetNS-current < hysteresisNS {
		r.lastDelayAdjustNS = nowNS
		return false
	}
	if current > targetNS && current-targetNS < hysteresisNS {
		r.lastDelayAdjustNS = nowNS
		return false
	}

	elapsedNS := nowNS - r.lastDelayAdjustNS
	r.lastDelayAdjustNS = nowNS
	elapsedSec := float64(elapsedNS) / float64(time.Second)
	if elapsedSec <= 0 {
		return false
	}

	var next uint64
	if targetNS > current {
		step := uint64(float64(r.delayUpNSPerSec) * elapsedSec)
		if step == 0 {
			return false
		}
		if current+step < targetNS {
			next = current + step
		} else {
			next = targetNS
		}
	} else {
		step := uint64(float64(r.delayDownNSPerSec) * elapsedSec)
		if step == 0 {
			return false
		}
		if targetNS+step < current {
			next = current - step
		} else {
			next = targetNS
		}
	}
	if next < r.delayMinNS {
		next = r.delayMinNS
	}
	if next > r.delayMaxNS {
		next = r.delayMaxNS
	}
	if next == current {
		return false
	}
	r.commonDelayNS = next
	return true
}

func (r *Room) commonDelayMilliseconds() float64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return float64(r.commonDelayNS) / 1e6
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

	audioAgeMS := uint64(0)
	if r.lastAudioNS > 0 && nowNS >= r.lastAudioNS {
		audioAgeMS = (nowNS - r.lastAudioNS) / uint64(time.Millisecond)
	}

	return map[string]any{
		"type":                    "state",
		"v":                       protocolVersion,
		"host_online":             r.hostOnline,
		"stream_state":            r.streamState,
		"host_capture_state":      r.hostAdvisoryState,
		"last_audio_server_ns":    r.lastAudioNS,
		"last_audio_age_ms":       audioAgeMS,
		"host_stall_after_ms":     r.hostStallAfterNS / uint64(time.Millisecond),
		"server_instance_id":      serverInstanceID,
		"timeline_ready":          r.haveFrame,
		"room_id":                 r.id,
		"epoch":                   strconv.FormatUint(r.epoch, 10),
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
	r.lastAudioNS = 0
	r.streamState = streamConnectedNoHost
	r.hostAdvisoryState = ""
	r.listenerStats = make(map[uint64]listenerStat)
	r.commonDelayNS = r.defaultDelayNS
	r.lastDelayAdjustNS = 0
}
