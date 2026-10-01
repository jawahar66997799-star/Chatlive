package main

import (
	"embed"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

//go:embed web/*
var webFS embed.FS

var started = time.Now()

func serverNs() uint64 { return uint64(time.Since(started).Nanoseconds()) }

type outbound struct {
	kind int
	data []byte
}

type guest struct {
	conn   *websocket.Conn
	send   chan outbound
	ready  atomic.Bool
	closed atomic.Bool
}

type room struct {
	mu         sync.RWMutex
	host       *websocket.Conn
	guests     map[*guest]struct{}
	epoch      uint32
	seq        uint32
	sampleRate int
	channels   int
}

type hub struct {
	mu    sync.Mutex
	rooms map[string]*room
}

func newHub() *hub { return &hub{rooms: map[string]*room{}} }

func (h *hub) get(roomID string) *room {
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.rooms[roomID]
	if r == nil {
		r = &room{guests: map[*guest]struct{}{}, sampleRate: 48000, channels: 2}
		h.rooms[roomID] = r
	}
	return r
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  16 * 1024,
	WriteBufferSize: 16 * 1024,
	CheckOrigin:     func(*http.Request) bool { return true },
}

func safeRoom(s string) string {
	s = strings.TrimSpace(s)
	if len(s) < 3 || len(s) > 128 { return "" }
	for _, r := range s {
		if !(r == '-' || r == '_' || (r >= '0' && r <= '9') || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z')) {
			return ""
		}
	}
	return s
}

func main() {
	h := newHub()
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("content-type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
	mux.HandleFunc("/ws/host", h.hostWS)
	mux.HandleFunc("/ws/listen", h.listenWS)
	mux.HandleFunc("/r/", serveWeb)
	mux.HandleFunc("/", serveWeb)

	port := os.Getenv("PORT")
	if port == "" { port = "8080" }

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           securityHeaders(mux),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       75 * time.Second,
	}
	log.Printf("Jawahar Live Sync relay listening on :%s", port)
	log.Fatal(srv.ListenAndServe())
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cross-Origin-Opener-Policy", "same-origin")
		w.Header().Set("Cross-Origin-Embedder-Policy", "require-corp")
		w.Header().Set("Cross-Origin-Resource-Policy", "same-origin")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

func serveWeb(w http.ResponseWriter, r *http.Request) {
	sub, _ := fs.Sub(webFS, "web")
	if r.URL.Path == "/worklet.js" {
		b, err := fs.ReadFile(sub, "worklet.js")
		if err != nil { http.NotFound(w,r); return }
		w.Header().Set("content-type", "text/javascript; charset=utf-8")
		_, _ = w.Write(b)
		return
	}
	b, err := fs.ReadFile(sub, "index.html")
	if err != nil { http.Error(w, err.Error(), 500); return }
	w.Header().Set("content-type", "text/html; charset=utf-8")
	_, _ = w.Write(b)
}

func (h *hub) hostWS(w http.ResponseWriter, req *http.Request) {
	roomID := safeRoom(req.URL.Query().Get("room"))
	if roomID == "" { http.Error(w, "invalid room", 400); return }
	c, err := upgrader.Upgrade(w, req, nil)
	if err != nil { return }
	defer c.Close()

	r := h.get(roomID)
	r.mu.Lock()
	if r.host != nil { _ = r.host.Close() }
	r.host = c
	r.epoch++
	r.seq = 0
	epoch := r.epoch
	r.mu.Unlock()
	log.Printf("host connected room=%s epoch=%d", roomID, epoch)
	defer func() {
		r.mu.Lock()
		if r.host == c { r.host = nil }
		r.mu.Unlock()
		r.broadcastJSON(map[string]any{"type":"host-offline","epoch":epoch})
		log.Printf("host disconnected room=%s epoch=%d", roomID, epoch)
	}()

	c.SetReadLimit(256 * 1024)
	_ = c.SetReadDeadline(time.Now().Add(90 * time.Second))
	c.SetPongHandler(func(string) error {
		_ = c.SetReadDeadline(time.Now().Add(90 * time.Second))
		return nil
	})

	for {
		mt, data, err := c.ReadMessage()
		if err != nil { return }
		if mt == websocket.TextMessage {
			var m map[string]any
			if json.Unmarshal(data, &m) == nil {
				if m["type"] == "host-hello" {
					r.mu.Lock()
					if v, ok := m["sampleRate"].(float64); ok && v >= 8000 && v <= 192000 { r.sampleRate = int(v) }
					if v, ok := m["channels"].(float64); ok && (v == 1 || v == 2) { r.channels = int(v) }
					r.mu.Unlock()
					r.broadcastJSON(map[string]any{"type":"format","sampleRate":r.sampleRate,"channels":r.channels,"epoch":epoch})
				}
			}
			continue
		}
		if mt != websocket.BinaryMessage || len(data) == 0 { continue }

		r.mu.Lock()
		r.seq++
		seq := r.seq
		ch := r.channels
		r.mu.Unlock()
		frames := uint32(len(data) / (2 * max(ch,1)))

		// Wire frame: "JLS1" + epoch(u32) + seq(u32) + serverNs(u64) + frames(u32) + PCM16LE
		packet := make([]byte, 24+len(data))
		copy(packet[0:4], []byte("JLS1"))
		binary.BigEndian.PutUint32(packet[4:8], epoch)
		binary.BigEndian.PutUint32(packet[8:12], seq)
		binary.BigEndian.PutUint64(packet[12:20], serverNs())
		binary.BigEndian.PutUint32(packet[20:24], frames)
		copy(packet[24:], data)
		r.broadcastBinary(packet)
	}
}

func (h *hub) listenWS(w http.ResponseWriter, req *http.Request) {
	roomID := safeRoom(req.URL.Query().Get("room"))
	if roomID == "" { http.Error(w, "invalid room", 400); return }
	c, err := upgrader.Upgrade(w, req, nil)
	if err != nil { return }
	g := &guest{conn:c, send:make(chan outbound, 128)}
	r := h.get(roomID)
	r.mu.Lock()
	r.guests[g] = struct{}{}
	epoch, sr, ch, hostOnline := r.epoch, r.sampleRate, r.channels, r.host != nil
	r.mu.Unlock()

	defer func() {
		g.closed.Store(true)
		r.mu.Lock()
		delete(r.guests, g)
		r.mu.Unlock()
		_ = c.Close()
	}()
	go g.writer()

	g.enqueueJSON(map[string]any{
		"type":"hello","room":roomID,"epoch":epoch,"sampleRate":sr,"channels":ch,
		"targetDelayMs":400,"hostOnline":hostOnline,"serverNs":serverNs(),
	})

	c.SetReadLimit(64 * 1024)
	for {
		mt, data, err := c.ReadMessage()
		if err != nil { return }
		if mt != websocket.TextMessage { continue }
		var m map[string]any
		if json.Unmarshal(data, &m) != nil { continue }
		switch m["type"] {
		case "ready":
			g.ready.Store(true)
			g.enqueueJSON(map[string]any{"type":"ready-ack","serverNs":serverNs()})
		case "ping":
			g.enqueueJSON(map[string]any{"type":"pong","id":m["id"],"serverNs":serverNs()})
		}
	}
}

func (r *room) broadcastBinary(data []byte) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for g := range r.guests {
		if !g.ready.Load() || g.closed.Load() { continue }
		select {
		case g.send <- outbound{kind:websocket.BinaryMessage,data:data}:
		default:
			// Slow clients do not stall the room. Their socket is closed and browser reconnects.
			go g.conn.Close()
		}
	}
}

func (r *room) broadcastJSON(v any) {
	b, _ := json.Marshal(v)
	r.mu.RLock()
	defer r.mu.RUnlock()
	for g := range r.guests {
		if g.closed.Load() { continue }
		select {
		case g.send <- outbound{kind:websocket.TextMessage,data:b}:
		default:
			go g.conn.Close()
		}
	}
}

func (g *guest) enqueueJSON(v any) {
	b, _ := json.Marshal(v)
	select { case g.send <- outbound{kind:websocket.TextMessage,data:b}: default: _ = g.conn.Close() }
}

func (g *guest) writer() {
	t := time.NewTicker(25 * time.Second)
	defer t.Stop()
	for {
		select {
		case m, ok := <-g.send:
			if !ok { return }
			_ = g.conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
			if err := g.conn.WriteMessage(m.kind, m.data); err != nil { _ = g.conn.Close(); return }
		case <-t.C:
			_ = g.conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
			if err := g.conn.WriteControl(websocket.PingMessage, []byte("p"), time.Now().Add(3*time.Second)); err != nil { _ = g.conn.Close(); return }
		}
	}
}

func max(a,b int) int { if a>b { return a }; return b }

var _ = fmt.Sprintf
