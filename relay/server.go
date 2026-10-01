package main

import (
	"encoding/json"
	"errors"
	"log"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

var wsUpgrader = websocket.Upgrader{
	ReadBufferSize:  16 * 1024,
	WriteBufferSize: 16 * 1024,
	CheckOrigin: func(r *http.Request) bool {
		origin := r.Header.Get("Origin")
		if origin == "" {
			// Native Android host and non-browser test clients generally omit Origin.
			return true
		}
		u, err := url.Parse(origin)
		if err != nil {
			return false
		}
		return strings.EqualFold(u.Host, r.Host)
	},
}

func normalizeIP(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	if ip := net.ParseIP(raw); ip != nil {
		return ip.String()
	}
	if host, _, err := net.SplitHostPort(raw); err == nil {
		if ip := net.ParseIP(host); ip != nil {
			return ip.String()
		}
	}
	return ""
}

func clientIP(r *http.Request, trustProxyHeaders bool) string {
	if trustProxyHeaders {
		// Railway public ingress overwrites X-Real-IP with the connecting client
		// address. Trust forwarded identity only when explicitly enabled for a
		// known reverse-proxy deployment.
		if ip := normalizeIP(r.Header.Get("X-Real-IP")); ip != "" {
			return ip
		}
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			parts := strings.Split(xff, ",")
			if ip := normalizeIP(parts[0]); ip != "" {
				return ip
			}
		}
	}
	if ip := normalizeIP(r.RemoteAddr); ip != "" {
		return ip
	}
	return r.RemoteAddr
}

func (s *Server) acquireIP(w http.ResponseWriter, r *http.Request) (string, bool) {
	ip := clientIP(r, s.cfg.TrustProxyHeaders)
	if !s.ip.acquire(ip) {
		http.Error(w, "connection quota exceeded", http.StatusTooManyRequests)
		return "", false
	}
	return ip, true
}

func (s *Server) hostWS(w http.ResponseWriter, r *http.Request) {
	ip, ok := s.acquireIP(w, r)
	if !ok {
		return
	}
	defer s.ip.release(ip)

	c, err := wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer c.Close()

	c.SetReadLimit(s.cfg.MaxMessage)
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))

	mt, data, err := c.ReadMessage()
	if err != nil || mt != websocket.TextMessage {
		s.metrics.malformedMessages.Add(1)
		writeClose(c, 1008, "hello_host required")
		return
	}
	var hello HostHello
	if err := json.Unmarshal(data, &hello); err != nil || validateHostHello(&hello) != nil {
		s.metrics.malformedMessages.Add(1)
		_ = c.WriteMessage(websocket.TextMessage, protocolError("bad_hello", "invalid hello_host", false))
		writeClose(c, 1008, "invalid hello_host")
		return
	}
	if !s.room.authHost(hello.RoomID, hello.HostSecret) {
		s.metrics.authFailures.Add(1)
		_ = c.WriteMessage(websocket.TextMessage, protocolError("unauthorized", "host authentication failed", false))
		writeClose(c, 1008, "unauthorized")
		return
	}

	generation, resumeAfter, epochChanged, err := s.room.beginHost(hello)
	if err != nil {
		_ = c.WriteMessage(websocket.TextMessage, protocolError("host_conflict", err.Error(), true))
		writeClose(c, 1013, "host already connected")
		return
	}
	defer s.room.endHost(generation)
	s.metrics.hostConnections.Add(1)

	_ = c.SetReadDeadline(time.Time{})
	ack := map[string]any{
		"type":                  "hello_host_ack",
		"v":                     protocolVersion,
		"server_instance_id":    serverInstanceID,
		"room_id":               hello.RoomID,
		"epoch":                 hello.Epoch,
		"resume_after_sequence": resumeAfter,
		"epoch_changed":         epochChanged,
		"server_now_ns":         serverNS(),
		"recommended_delay_ns":  uint64(s.cfg.CommonDelay.Nanoseconds()),
		"max_payload_bytes":     s.cfg.MaxPayload,
	}
	if err := c.WriteMessage(websocket.TextMessage, encodeJSON(ack)); err != nil {
		return
	}
	s.room.broadcastState("host_online")
	log.Printf("host connected ip=%s epoch=%d resume_after=%d", ip, hello.Epoch, resumeAfter)

	rateWindow := time.Now()
	rateCount := 0
	for {
		mt, data, err := c.ReadMessage()
		t1 := serverNS()
		if err != nil {
			return
		}
		if time.Since(rateWindow) >= time.Second {
			rateWindow = time.Now()
			rateCount = 0
		}
		rateCount++
		if rateCount > 220 {
			writeClose(c, 1008, "host message rate exceeded")
			return
		}
		switch mt {
		case websocket.BinaryMessage:
			frame, err := parseAudioFrame(data, s.cfg.MaxPayload)
			if err != nil {
				s.metrics.malformedMessages.Add(1)
				_ = c.WriteMessage(websocket.TextMessage, protocolError("bad_audio_frame", err.Error(), false))
				continue
			}
			if err := s.room.acceptFrame(frame, t1); err != nil {
				_ = c.WriteMessage(websocket.TextMessage, protocolError("audio_rejected", err.Error(), true))
			}
		case websocket.TextMessage:
			var m HostControl
			if err := json.Unmarshal(data, &m); err != nil || m.V != protocolVersion {
				s.metrics.malformedMessages.Add(1)
				continue
			}
			switch m.Type {
			case "clock_req":
				t2 := serverNS()
				s.metrics.clockRequests.Add(1)
				resp := map[string]any{
					"type":         "clock_resp",
					"v":            protocolVersion,
					"server_instance_id": serverInstanceID,
					"id":           m.ID,
					"t0_guest_ns":  m.T0NS,
					"t1_server_ns": t1,
					"t2_server_ns": t2,
				}
				if err := c.WriteMessage(websocket.TextMessage, encodeJSON(resp)); err != nil {
					return
				}
			case "host_state":
				// State is telemetry only. Epoch/sequence/timeline are relay-authoritative.
			default:
				s.metrics.malformedMessages.Add(1)
			}
		default:
			s.metrics.malformedMessages.Add(1)
		}
	}
}

func (s *Server) guestWS(w http.ResponseWriter, r *http.Request) {
	token := strings.TrimPrefix(r.URL.Path, "/v1/ws/guest/")
	s.serveGuestWS(w, r, token)
}

func (s *Server) legacyGuestWS(w http.ResponseWriter, r *http.Request) {
	s.serveGuestWS(w, r, r.URL.Query().Get("room"))
}

func (s *Server) serveGuestWS(w http.ResponseWriter, r *http.Request, token string) {
	if !s.room.authGuest(token) {
		s.metrics.authFailures.Add(1)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	ip, ok := s.acquireIP(w, r)
	if !ok {
		return
	}
	defer s.ip.release(ip)

	c, err := wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	g := &guestConn{
		id:   s.guestID.Add(1),
		conn: c,
		send: make(chan outbound, 1024),
	}
	defer func() {
		g.closed.Store(true)
		s.room.removeGuest(g)
		_ = c.Close()
	}()

	c.SetReadLimit(64 * 1024)
	var resumeEpoch, resumeSeq uint64
	var hasResume bool
	if ev, err := strconv.ParseUint(r.URL.Query().Get("epoch"), 10, 64); err == nil && ev != 0 {
		if sv, err := strconv.ParseUint(r.URL.Query().Get("seq"), 10, 64); err == nil {
			resumeEpoch, resumeSeq, hasResume = ev, sv, true
		}
	}

	go s.guestWriter(g)
	if err := s.room.addGuest(g, serverNS(), resumeEpoch, resumeSeq, hasResume); err != nil {
		_ = c.WriteMessage(websocket.TextMessage, protocolError("guest_join_failed", err.Error(), true))
		writeClose(c, 1013, "guest join failed")
		return
	}


	rateWindow := time.Now()
	rateCount := 0
	for {
		mt, data, err := c.ReadMessage()
		t1 := serverNS()
		if err != nil {
			return
		}
		if time.Since(rateWindow) >= time.Second {
			rateWindow = time.Now()
			rateCount = 0
		}
		rateCount++
		if rateCount > 40 {
			writeClose(c, 1008, "guest control rate exceeded")
			return
		}
		if mt != websocket.TextMessage {
			s.metrics.malformedMessages.Add(1)
			continue
		}
		var m GuestControl
		if err := json.Unmarshal(data, &m); err != nil || m.V != protocolVersion {
			s.metrics.malformedMessages.Add(1)
			continue
		}
		switch m.Type {
		case "clock_req":
			s.metrics.clockRequests.Add(1)
			if !enqueue(g, outbound{
				kind:       outboundClock,
				clockID:    m.ID,
				t0GuestNS:  m.T0GuestNS,
				t1ServerNS: t1,
			}) {
				return
			}
		case "listener_stats":
			// Listener timing recommendations feed one bounded room-wide delay.
			// No guest is allowed to privately redefine the shared timeline.
			s.room.updateListenerStats(g.id, m, t1)
		case "resume":
			// Reconnect resume is negotiated in the URL before state/ring replay.
			// A mid-connection resume would risk replaying behind already queued audio, so reject it.
			_ = enqueue(g, outbound{kind: outboundText, data: protocolError("reconnect_required", "resume requires a fresh WebSocket", true)})
		default:
			s.metrics.malformedMessages.Add(1)
		}
	}
}

func (s *Server) guestWriter(g *guestConn) {
	ping := time.NewTicker(20 * time.Second)
	defer ping.Stop()
	for {
		select {
		case o := <-g.send:
			if g.closed.Load() {
				return
			}
			_ = g.conn.SetWriteDeadline(time.Now().Add(4 * time.Second))
			var err error
			switch o.kind {
			case outboundText:
				err = g.conn.WriteMessage(websocket.TextMessage, o.data)
			case outboundBinary:
				err = g.conn.WriteMessage(websocket.BinaryMessage, o.data)
			case outboundClock:
				// T2 is stamped as late as practical: immediately before the socket write.
				t2 := serverNS()
				err = g.conn.WriteMessage(websocket.TextMessage, encodeJSON(map[string]any{
					"type":         "clock_resp",
					"v":            protocolVersion,
					"server_instance_id": serverInstanceID,
					"id":           o.clockID,
					"t0_guest_ns":  o.t0GuestNS,
					"t1_server_ns": o.t1ServerNS,
					"t2_server_ns": t2,
				}))
			default:
				err = errors.New("unknown outbound type")
			}
			if err != nil {
				g.closed.Store(true)
				_ = g.conn.Close()
				return
			}
		case <-ping.C:
			if g.closed.Load() {
				return
			}
			_ = g.conn.SetWriteDeadline(time.Now().Add(4 * time.Second))
			if err := g.conn.WriteControl(websocket.PingMessage, []byte("jls"), time.Now().Add(4*time.Second)); err != nil {
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
