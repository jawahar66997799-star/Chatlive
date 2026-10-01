package main

import (
	"net/http/httptest"
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
