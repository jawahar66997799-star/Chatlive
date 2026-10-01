package main

import (
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

//go:embed web/*
var webFS embed.FS

var processStart = time.Now()

func serverNS() uint64 { return uint64(time.Since(processStart).Nanoseconds()) }

type Config struct {
	Port         string
	RoomID       string
	HostSecret   string
	GuestToken   string
	MaxGuests    int
	MaxPayload   int
	MaxMessage   int64
	MaxRingBytes int
	RingDuration time.Duration
	CommonDelay  time.Duration
	JoinGuard    time.Duration
	IdleTTL      time.Duration
	MaxIPConns   int
}

func loadConfig() (Config, error) {
	cfg := Config{
		Port:         env("PORT", "8080"),
		RoomID:       os.Getenv("JLS_ROOM_ID"),
		HostSecret:   os.Getenv("JLS_HOST_SECRET"),
		GuestToken:   os.Getenv("JLS_GUEST_TOKEN"),
		MaxGuests:    envInt("JLS_MAX_GUESTS", 250),
		MaxPayload:   envInt("JLS_MAX_PAYLOAD_BYTES", 16384),
		MaxMessage:   int64(envInt("JLS_MAX_MESSAGE_BYTES", 32768)),
		MaxRingBytes: envInt("JLS_MAX_RING_BYTES", 2*1024*1024),
		RingDuration: time.Duration(envInt("JLS_RING_MS", 5000)) * time.Millisecond,
		CommonDelay:  time.Duration(envInt("JLS_COMMON_DELAY_MS", 400)) * time.Millisecond,
		JoinGuard:    time.Duration(envInt("JLS_JOIN_GUARD_MS", 150)) * time.Millisecond,
		IdleTTL:      time.Duration(envInt("JLS_IDLE_TTL_SEC", 120)) * time.Second,
		MaxIPConns:   envInt("JLS_MAX_IP_CONNECTIONS", 32),
	}
	if len(cfg.RoomID) < 32 || len(cfg.HostSecret) < 32 || len(cfg.GuestToken) < 22 {
		return Config{}, fmt.Errorf("JLS_ROOM_ID, JLS_HOST_SECRET, and JLS_GUEST_TOKEN must be configured with >=128-bit unguessable values")
	}
	if cfg.MaxGuests < 1 || cfg.MaxGuests > 5000 {
		return Config{}, fmt.Errorf("JLS_MAX_GUESTS outside safe range")
	}
	return cfg, nil
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func envInt(k string, d int) int {
	v := os.Getenv(k)
	if v == "" {
		return d
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return d
	}
	return n
}

type Metrics struct {
	hostConnections   atomic.Uint64
	guestJoins        atomic.Uint64
	guestsCurrent     atomic.Int64
	audioFrames       atomic.Uint64
	audioBytes        atomic.Uint64
	rejectedFrames    atomic.Uint64
	replayRejected    atomic.Uint64
	malformedMessages atomic.Uint64
	backpressureDrops atomic.Uint64
	clockRequests     atomic.Uint64
	epochChanges      atomic.Uint64
	authFailures      atomic.Uint64
}

type ipLimiter struct {
	mu     sync.Mutex
	max    int
	counts map[string]int
}

func newIPLimiter(max int) *ipLimiter { return &ipLimiter{max: max, counts: map[string]int{}} }
func (l *ipLimiter) acquire(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.counts[ip] >= l.max {
		return false
	}
	l.counts[ip]++
	return true
}
func (l *ipLimiter) release(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.counts[ip] <= 1 {
		delete(l.counts, ip)
	} else {
		l.counts[ip]--
	}
}

type Server struct {
	cfg     Config
	room    *Room
	metrics *Metrics
	ip      *ipLimiter
	guestID atomic.Uint64
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}
	metrics := &Metrics{}
	s := &Server{cfg: cfg, metrics: metrics, ip: newIPLimiter(cfg.MaxIPConns)}
	s.room = newRoom(cfg, metrics)

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.health)
	mux.HandleFunc("/readyz", s.health)
	mux.HandleFunc("/metrics", s.metricsHandler)
	mux.HandleFunc("/v1/clock", s.clockHTTP)
	mux.HandleFunc("/v1/ws/host", s.hostWS)
	mux.HandleFunc("/v1/ws/guest/", s.guestWS)
	// Compatibility alias for the existing guest page. room query is a guest token, never a friendly room name.
	mux.HandleFunc("/ws/listen", s.legacyGuestWS)
	mux.HandleFunc("/r/", serveWeb)
	mux.HandleFunc("/worklet.js", serveWeb)
	mux.HandleFunc("/", serveWeb)

	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for range t.C {
			s.room.cleanupIfIdle(cfg.IdleTTL)
		}
	}()

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           securityHeaders(mux),
		ReadHeaderTimeout: 8 * time.Second,
		ReadTimeout:       0,
		WriteTimeout:      0,
		IdleTimeout:       90 * time.Second,
		MaxHeaderBytes:    16 * 1024,
	}
	log.Printf("JLS relay v1 listening port=%s room=%s maxGuests=%d ring=%s", cfg.Port, cfg.RoomID, cfg.MaxGuests, cfg.RingDuration)
	log.Fatal(srv.ListenAndServe())
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("content-type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "protocol": protocolVersion, "server_ns": serverNS()})
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cross-Origin-Opener-Policy", "same-origin")
		w.Header().Set("Cross-Origin-Embedder-Policy", "require-corp")
		w.Header().Set("Cross-Origin-Resource-Policy", "same-origin")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

func serveWeb(w http.ResponseWriter, r *http.Request) {
	sub, _ := fs.Sub(webFS, "web")
	if r.URL.Path == "/worklet.js" {
		b, err := fs.ReadFile(sub, "worklet.js")
		if err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("content-type", "text/javascript; charset=utf-8")
		_, _ = w.Write(b)
		return
	}
	b, err := fs.ReadFile(sub, "index.html")
	if err != nil {
		http.Error(w, "guest player unavailable", 500)
		return
	}
	w.Header().Set("content-type", "text/html; charset=utf-8")
	_, _ = w.Write(b)
}

func (s *Server) metricsHandler(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("content-type", "text/plain; version=0.0.4")
	fmt.Fprintf(w, "jls_host_connections_total %d\n", s.metrics.hostConnections.Load())
	fmt.Fprintf(w, "jls_guest_joins_total %d\n", s.metrics.guestJoins.Load())
	fmt.Fprintf(w, "jls_guests_current %d\n", s.metrics.guestsCurrent.Load())
	fmt.Fprintf(w, "jls_audio_frames_total %d\n", s.metrics.audioFrames.Load())
	fmt.Fprintf(w, "jls_audio_bytes_total %d\n", s.metrics.audioBytes.Load())
	fmt.Fprintf(w, "jls_rejected_frames_total %d\n", s.metrics.rejectedFrames.Load())
	fmt.Fprintf(w, "jls_replay_rejected_total %d\n", s.metrics.replayRejected.Load())
	fmt.Fprintf(w, "jls_malformed_messages_total %d\n", s.metrics.malformedMessages.Load())
	fmt.Fprintf(w, "jls_backpressure_drops_total %d\n", s.metrics.backpressureDrops.Load())
	fmt.Fprintf(w, "jls_clock_requests_total %d\n", s.metrics.clockRequests.Load())
	fmt.Fprintf(w, "jls_epoch_changes_total %d\n", s.metrics.epochChanges.Load())
	fmt.Fprintf(w, "jls_auth_failures_total %d\n", s.metrics.authFailures.Load())
}

func (s *Server) clockHTTP(w http.ResponseWriter, r *http.Request) {
	t1 := serverNS()
	t0 := r.URL.Query().Get("t0")
	t2 := serverNS()
	w.Header().Set("content-type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"type": "clock_resp", "v": protocolVersion, "t0_guest_ns": t0, "t1_server_ns": t1, "t2_server_ns": t2})
}
