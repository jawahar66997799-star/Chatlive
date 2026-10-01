package main

import (
	"encoding/json"
	"errors"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  16 * 1024,
	WriteBufferSize: 16 * 1024,
	CheckOrigin:     func(*http.Request) bool { return true },
}

func remoteIP(r *http.Request) string {
	// Railway supplies X-Forwarded-For. Trust only the left-most value from the platform proxy path.
	if x := r.Header.Get("X-Forwarded-For"); x != "" {
		return strings.TrimSpace(strings.Split(x, ",")[0])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}

func (s *Server) acquireWS(w http.ResponseWriter, r *http.Request) (string, bool) {
	ip := remoteIP(r)
	if !s.ip.acquire(ip) {
		http.Error(w, "connection quota exceeded", http.StatusTooManyRequests)
		return ip, false
	}
	return ip, true
}

func (s *Server) hostWS(w http.ResponseWriter, r *http.Request) {
	ip, ok := s.acquireWS(w, r)
	if !ok {
		return
	}
	defer s.ip.release(ip)

	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer c.Close()
	c.SetReadLimit(s.cfg.MaxMessage)
	_ = c.SetReadDeadline(time.Now().Add(8 * time.Second))

	mt, data, err := c.ReadMessage()
	if err != nil || mt != websocket.TextMessage {
		s.metrics.malformedMessages.Add(1)
		writeClose(c, 1002, "hello_host required")
		return
	}
	var hello HostHello
	if json.Unmarshal(data, &hello) != nil || validateHostHello(&hello) != nil {
		s.metrics.malformedMessages.Add(1)
		_ = c.WriteMessage(websocket.TextMessage, protocolError("BAD_HELLO", "invalid hello_host", false))
		writeClose(c, 1002, "bad hello")
		return
	}
	if !s.room.authHost(hello.RoomID, hello.HostSecret) {
		s.metrics.authFailures.Add(1)
		_ = c.WriteMessage(websocket.TextMessage, protocolError("AUTH_FAILED", "host authentication failed", false))
		writeClose(c, 1008, "auth failed")
		return
	}

	gen, resumeAfter, epochChanged, err := s.room.beginHost(hello)
	if err != nil {
		_ = c.WriteMessage(websocket.TextMessage, protocolError("HOST_BUSY", err.Error(), true))
		writeClose(c, 1013, "host already connected")
		return
	}
	s.metrics.hostConnections.Add(1)
	defer s.room.endHost(gen)

	_ = c.SetReadDeadline(time.Now().Add(45 * time.Second))
	c.SetPongHandler(func(string) error { return c.SetReadDeadline(time.Now().Add(45 * time.Second)) })
	_ = c.WriteJSON(map[string]any{
		"type": "welcome_host", "v": protocolVersion, "server_now_ns": serverNS(),
		"epoch": hello.Epoch, "resume_after_seq": resumeAfter, "epoch_changed": epochChanged,
		"max_payload_bytes": s.cfg.MaxPayload, "ring_ms": s.cfg.RingDuration.Milliseconds(),
	})
	s.room.broadcastState("host_online")
	log.Printf("host online epoch=%d resume_after=%d ip=%s", hello.Epoch, resumeAfter, ip)

	for {
		mt, data, err = c.ReadMessage()
		if err != nil {
			return
		}
		switch mt {
		case websocket.BinaryMessage:
			f, parseErr := parseAudioFrame(data, s.cfg.MaxPayload)
			if parseErr != nil {
				s.metrics.malformedMessages.Add(1)
				_ = c.WriteMessage(websocket.TextMessage, protocolError("BAD_AUDIO", parseErr.Error(), false))
				continue
			}
			if err := s.room.acceptFrame(f, serverNS()); err != nil {
				_ = c.WriteMessage(websocket.TextMessage, protocolError("FRAME_REJECTED", err.Error(), false))
			}
		case websocket.TextMessage:
			var ctl HostControl
			if json.Unmarshal(data, &ctl) != nil || ctl.V != protocolVersion {
				s.metrics.malformedMessages.Add(1)
				continue
			}
			if ctl.Type == "ping" {
				_ = c.WriteJSON(map[string]any{"type": "pong", "v": protocolVersion, "id": ctl.ID, "t0_ns": ctl.T0NS, "server_ns": serverNS()})
			}
		default:
			s.metrics.malformedMessages.Add(1)
		}
	}
}

func (s *Server) legacyGuestWS(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("room")
	s.handleGuestWS(token, w, r)
}

func (s *Server) guestWS(w http.ResponseWriter, r *http.Request) {
	token := strings.TrimPrefix(r.URL.Path, "/v1/ws/guest/")
	s.handleGuestWS(token, w, r)
}

func (s *Server) handleGuestWS(token string, w http.ResponseWriter, r *http.Request) {
	if !s.room.authGuest(token) {
		s.metrics.authFailures.Add(1)
		http.Error(w, "guest token invalid", http.StatusUnauthorized)
		return
	}
	ip, ok := s.acquireWS(w, r)
	if !ok {
		return
	}
	defer s.ip.release(ip)

	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	c.SetReadLimit(16 * 1024)
	_ = c.SetReadDeadline(time.Now().Add(60 * time.Second))
	c.SetPongHandler(func(string) error { return c.SetReadDeadline(time.Now().Add(60 * time.Second)) })

	g := &guestConn{id: s.guestID.Add(1), conn: c, send: make(chan outbound, 512)}
	defer func() {
		g.closed.Store(true)
		s.room.removeGuest(g)
		_ = c.Close()
	}()

	var resumeEpoch, resumeSeq uint64
	hasResume := false
	if qe, qs := r.URL.Query().Get("resume_epoch"), r.URL.Query().Get("resume_seq"); qe != "" && qs != "" {
		e, eErr := strconv.ParseUint(qe, 10, 64)
		q, qErr := strconv.ParseUint(qs, 10, 64)
		if eErr == nil && qErr == nil {
			resumeEpoch, resumeSeq, hasResume = e, q, true
		}
	}
	if err := s.room.addGuest(g, serverNS(), resumeEpoch, resumeSeq, hasResume); err != nil {
		_ = c.WriteMessage(websocket.TextMessage, protocolError("GUEST_REJECTED", err.Error(), true))
		writeClose(c, 1013, "guest rejected")
		return
	}

	writerDone := make(chan struct{})
	go s.guestWriter(g, writerDone)

	for {
		mt, data, err := c.ReadMessage()
		if err != nil {
			return
		}
		if mt != websocket.TextMessage {
			s.metrics.malformedMessages.Add(1)
			continue
		}
		var ctl GuestControl
		if json.Unmarshal(data, &ctl) != nil || ctl.V != protocolVersion {
			// Legacy page ping omits v; accept only its narrow ping shape.
			var legacy map[string]any
			if json.Unmarshal(data, &legacy) == nil && legacy["type"] == "ping" {
				enqueue(g, outbound{kind: outboundText, data: encodeJSON(map[string]any{"type": "pong", "id": legacy["id"], "serverNs": serverNS()})})
				continue
			}
			s.metrics.malformedMessages.Add(1)
			continue
		}
		switch ctl.Type {
		case "clock_req":
			t1 := serverNS()
			s.metrics.clockRequests.Add(1)
			if !enqueue(g, outbound{kind: outboundClock, clockID: ctl.ID, t0GuestNS: ctl.T0GuestNS, t1ServerNS: t1}) {
				return
			}
		case "resume":
			// A reconnect should normally put resume_epoch/last_sequence in its first control message.
			// We send a fresh state immediately; audio already queued remains bounded and epoch-validated.
			s.room.mu.Lock()
			state, _ := s.room.stateLocked(serverNS(), ctl.Epoch, ctl.LastSequence, true)
			s.room.mu.Unlock()
			enqueue(g, outbound{kind: outboundText, data: encodeJSON(state)})
		case "stats":
			// Telemetry is intentionally accepted but not persisted in v1.
		case "ping":
			enqueue(g, outbound{kind: outboundText, data: encodeJSON(map[string]any{"type": "pong", "v": protocolVersion, "id": ctl.ID, "server_ns": serverNS()})})
		}
	}
}

func (s *Server) guestWriter(g *guestConn, done chan struct{}) {
	defer close(done)
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case o := <-g.send:
			if g.closed.Load() {
				return
			}
			_ = g.conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
			var err error
			switch o.kind {
			case outboundText:
				err = g.conn.WriteMessage(websocket.TextMessage, o.data)
			case outboundBinary:
				err = g.conn.WriteMessage(websocket.BinaryMessage, o.data)
			case outboundClock:
				t2 := serverNS()
				err = g.conn.WriteJSON(map[string]any{
					"type": "clock_resp", "v": protocolVersion, "id": o.clockID,
					"t0_guest_ns": o.t0GuestNS, "t1_server_ns": o.t1ServerNS, "t2_server_ns": t2,
				})
			}
			if err != nil {
				g.closed.Store(true)
				_ = g.conn.Close()
				return
			}
		case <-ticker.C:
			if g.closed.Load() {
				return
			}
			if err := g.conn.WriteControl(websocket.PingMessage, []byte("jls"), time.Now().Add(2*time.Second)); err != nil {
				g.closed.Store(true)
				_ = g.conn.Close()
				return
			}
		}
	}
}

func writeClose(c *websocket.Conn, code int, reason string) {
	_ = c.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, reason), time.Now().Add(500*time.Millisecond))
}

var _ = errors.New
