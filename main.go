package main

import (
	"crypto/rand"
	"embed"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

//go:embed web/*
var webFS embed.FS

var processStart = time.Now()
var serverInstanceID = mustServerInstanceID()

func serverNS() uint64 { return uint64(time.Since(processStart).Nanoseconds()) }

func mustServerInstanceID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic("unable to create server instance id: " + err.Error())
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

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
	CommonDelay         time.Duration
	CommonDelayMin      time.Duration
	CommonDelayMax      time.Duration
	DelayStatsTTL       time.Duration
	DelayUpdateInterval time.Duration
	DelayUpPerSec       time.Duration
	DelayDownPerSec     time.Duration
	JoinGuard           time.Duration
	IdleTTL             time.Duration
	MaxIPConns          int
	MetricsToken        string
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
		CommonDelay:         time.Duration(envInt("JLS_COMMON_DELAY_MS", 400)) * time.Millisecond,
		CommonDelayMin:      time.Duration(envInt("JLS_COMMON_DELAY_MIN_MS", 150)) * time.Millisecond,
		CommonDelayMax:      time.Duration(envInt("JLS_COMMON_DELAY_MAX_MS", 1000)) * time.Millisecond,
		DelayStatsTTL:       time.Duration(envInt("JLS_DELAY_STATS_TTL_MS", 10000)) * time.Millisecond,
		DelayUpdateInterval: time.Duration(envInt("JLS_DELAY_UPDATE_MS", 1000)) * time.Millisecond,
		DelayUpPerSec:       time.Duration(envInt("JLS_DELAY_UP_MS_PER_SEC", 60)) * time.Millisecond,
		DelayDownPerSec:     time.Duration(envInt("JLS_DELAY_DOWN_MS_PER_SEC", 10)) * time.Millisecond,
		JoinGuard:           time.Duration(envInt("JLS_JOIN_GUARD_MS", 150)) * time.Millisecond,
		IdleTTL:             time.Duration(envInt("JLS_IDLE_TTL_SEC", 120)) * time.Second,
		MaxIPConns:          envInt("JLS_MAX_IP_CONNECTIONS", 32),
		MetricsToken:        os.Getenv("JLS_METRICS_TOKEN"),
	}
	if len(cfg.RoomID) < 32 || len(cfg.HostSecret) < 32 || len(cfg.GuestToken) < 22 {
		return Config{}, fmt.Errorf("JLS_ROOM_ID, JLS_HOST_SECRET, and JLS_GUEST_TOKEN must be configured with >=128-bit unguessable values")
	}
	if cfg.MaxGuests < 1 || cfg.MaxGuests > 5000 {
		return Config{}, fmt.Errorf("JLS_MAX_GUESTS outside safe range")
	}
	if cfg.CommonDelayMin <= 0 || cfg.CommonDelayMax < cfg.CommonDelayMin ||
		cfg.CommonDelay < cfg.CommonDelayMin || cfg.CommonDelay > cfg.CommonDelayMax {
		return Config{}, fmt.Errorf("invalid common-delay bounds")
	}
	if cfg.DelayStatsTTL <= 0 || cfg.DelayUpdateInterval <= 0 || cfg.DelayUpPerSec <= 0 || cfg.DelayDownPerSec <= 0 {
		return Config{}, fmt.Errorf("invalid adaptive-delay controller settings")
	}
	if cfg.MetricsToken != "" && len(cfg.MetricsToken) < 32 {
		return Config{}, fmt.Errorf("JLS_METRICS_TOKEN must be empty or at least 32 characters")
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
	mux.HandleFunc("/player.js", serveWeb)
	mux.HandleFunc("/decoder-worker.js", serveWeb)
	mux.HandleFunc("/sync-core.mjs", serveWeb)
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
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "protocol": protocolVersion, "server_instance_id": serverInstanceID, "server_ns": serverNS()})
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cross-Origin-Opener-Policy", "same-origin")
		w.Header().Set("Cross-Origin-Embedder-Policy", "require-corp")
		w.Header().Set("Cross-Origin-Resource-Policy", "same-origin")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Strict-Transport-Security", "max-age=31536000")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; connect-src 'self' wss:; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self' blob:; font-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
		next.ServeHTTP(w, r)
	})
}

func serveWeb(w http.ResponseWriter, r *http.Request) {
	sub, _ := fs.Sub(webFS, "web")
	name := "index.html"
	contentType := "text/html; charset=utf-8"

	switch r.URL.Path {
	case "/worklet.js":
		name, contentType = "worklet.js", "text/javascript; charset=utf-8"
	case "/player.js":
		name, contentType = "player.js", "text/javascript; charset=utf-8"
	case "/decoder-worker.js":
		name, contentType = "decoder-worker.js", "text/javascript; charset=utf-8"
	case "/sync-core.mjs":
		name, contentType = "sync-core.mjs", "text/javascript; charset=utf-8"
	default:
		if strings.HasPrefix(r.URL.Path, "/vendor/libopus-wasm/") {
			rel := strings.TrimPrefix(r.URL.Path, "/")
			if !fs.ValidPath(rel) || !strings.HasPrefix(rel, "vendor/libopus-wasm/") {
				http.NotFound(w, r)
				return
			}
			name = rel
			switch path.Ext(name) {
			case ".js", ".mjs":
				contentType = "text/javascript; charset=utf-8"
			case ".wasm":
				contentType = "application/wasm"
			case ".json":
				contentType = "application/json; charset=utf-8"
			case ".txt":
				contentType = "text/plain; charset=utf-8"
			default:
				contentType = "application/octet-stream"
			}
		}
	}

	b, err := fs.ReadFile(sub, name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("content-type", contentType)
	_, _ = w.Write(b)
}

func (s *Server) metricsHandler(w http.ResponseWriter, r *http.Request) {
	if s.cfg.MetricsToken != "" {
		auth := strings.TrimSpace(r.Header.Get("Authorization"))
		const prefix = "Bearer "
		if !strings.HasPrefix(auth, prefix) || !secureEqual(strings.TrimSpace(strings.TrimPrefix(auth, prefix)), s.cfg.MetricsToken) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
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
	fmt.Fprintf(w, "jls_common_delay_ms %.3f\n", s.room.commonDelayMilliseconds())
}

func (s *Server) clockHTTP(w http.ResponseWriter, r *http.Request) {
	if !s.room.authGuest(r.URL.Query().Get("token")) {
		s.metrics.authFailures.Add(1)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	t1 := serverNS()
	s.metrics.clockRequests.Add(1)
	t0 := r.URL.Query().Get("t0")
	t2 := serverNS()
	w.Header().Set("content-type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"type": "clock_resp", "v": protocolVersion, "server_instance_id": serverInstanceID, "t0_guest_ns": t0, "t1_server_ns": t1, "t2_server_ns": t2})
}
