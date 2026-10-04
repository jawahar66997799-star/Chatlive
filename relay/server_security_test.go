package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestClientIPTrustBoundary(t *testing.T) {
	req := httptest.NewRequest("GET", "http://example.test/", nil)
	req.RemoteAddr = "10.0.0.8:43210"
	req.Header.Set("X-Real-IP", "203.0.113.7")
	req.Header.Set("X-Forwarded-For", "198.51.100.9, 192.0.2.4")

	if got := clientIP(req, false); got != "10.0.0.8" {
		t.Fatalf("untrusted forwarded headers affected client IP: %q", got)
	}
	if got := clientIP(req, true); got != "203.0.113.7" {
		t.Fatalf("trusted X-Real-IP not used: %q", got)
	}
}

func TestClientIPIgnoresGenericForwardedChain(t *testing.T) {
	req := httptest.NewRequest("GET", "http://example.test/", nil)
	req.RemoteAddr = "10.0.0.9:43210"
	req.Header.Set("X-Real-IP", "not-an-ip")
	req.Header.Set("X-Forwarded-For", "198.51.100.12, 192.0.2.5")

	if got := clientIP(req, true); got != "10.0.0.9" {
		t.Fatalf("generic forwarded chain should not affect client IP: %q", got)
	}
}


func TestMetricsBearerProtection(t *testing.T) {
	cfg := testConfig()
	cfg.MetricsToken = strings.Repeat("m", 32)
	metrics := &Metrics{}
	s := &Server{cfg: cfg, metrics: metrics, ip: newIPLimiter(cfg.MaxIPConns)}
	s.room = newRoom(cfg, metrics)

	unauthReq := httptest.NewRequest("GET", "http://example.test/metrics", nil)
	unauthRec := httptest.NewRecorder()
	s.metricsHandler(unauthRec, unauthReq)
	if unauthRec.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated metrics status=%d want=%d", unauthRec.Code, http.StatusUnauthorized)
	}

	authReq := httptest.NewRequest("GET", "http://example.test/metrics", nil)
	authReq.Header.Set("Authorization", "Bearer "+cfg.MetricsToken)
	authRec := httptest.NewRecorder()
	s.metricsHandler(authRec, authReq)
	if authRec.Code != http.StatusOK {
		t.Fatalf("authenticated metrics status=%d want=%d", authRec.Code, http.StatusOK)
	}
	if !strings.Contains(authRec.Body.String(), "jls_common_delay_ms") {
		t.Fatalf("authenticated metrics missing common delay gauge")
	}
}


func TestPublicRoomPageRejectsWrongCode(t *testing.T) {
	cfg := testConfig()
	metrics := &Metrics{}
	s := &Server{cfg: cfg, metrics: metrics, ip: newIPLimiter(cfg.MaxIPConns)}
	s.room = newRoom(cfg, metrics)

	req := httptest.NewRequest("GET", "http://example.test/room/111111", nil)
	rec := httptest.NewRecorder()
	s.publicRoomPage(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("wrong public room code status=%d want=%d", rec.Code, http.StatusNotFound)
	}
}

func TestPublicRoomPageAcceptsConfiguredCode(t *testing.T) {
	cfg := testConfig()
	metrics := &Metrics{}
	s := &Server{cfg: cfg, metrics: metrics, ip: newIPLimiter(cfg.MaxIPConns)}
	s.room = newRoom(cfg, metrics)

	req := httptest.NewRequest("GET", "http://example.test/room/"+cfg.PublicRoomCode, nil)
	rec := httptest.NewRecorder()
	s.publicRoomPage(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("configured public room code status=%d want=%d", rec.Code, http.StatusOK)
	}
	if !strings.Contains(rec.Body.String(), "Jawahar Live Sync") {
		t.Fatal("guest player page was not served")
	}
}


func TestPublicRoomPageLoadsBeforeHost(t *testing.T) {
	cfg := testConfig()
	m := &Metrics{}
	s := &Server{cfg: cfg, metrics: m, ip: newIPLimiter(cfg.MaxIPConns)}
	s.room = newRoom(cfg, m)

	req := httptest.NewRequest("GET", "http://example.test/room/123456", nil)
	rec := httptest.NewRecorder()
	s.publicRoomPage(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("valid pre-host room page status=%d want=%d", rec.Code, http.StatusOK)
	}
	if !strings.Contains(rec.Body.String(), "Jawahar Live Sync") {
		t.Fatalf("pre-host room page did not render guest UI")
	}

	badReq := httptest.NewRequest("GET", "http://example.test/room/not-a-code", nil)
	badRec := httptest.NewRecorder()
	s.publicRoomPage(badRec, badReq)
	if badRec.Code != http.StatusNotFound {
		t.Fatalf("invalid room code status=%d want=%d", badRec.Code, http.StatusNotFound)
	}
}
