package main

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

type goldenVectorFile struct {
	Vectors []struct {
		Name      string `json:"name"`
		PacketHex string `json:"packet_hex"`
	} `json:"vectors"`
}

func sharedGoldenPacket(t *testing.T) []byte {
	t.Helper()
	path := filepath.Join("..", "protocol", "jls_v1_golden_vectors.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read shared golden vector: %v", err)
	}
	var doc goldenVectorFile
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatalf("decode shared golden vector: %v", err)
	}
	if len(doc.Vectors) != 1 {
		t.Fatalf("expected one golden vector, got %d", len(doc.Vectors))
	}
	raw, err := hex.DecodeString(doc.Vectors[0].PacketHex)
	if err != nil {
		t.Fatalf("decode packet_hex: %v", err)
	}
	return raw
}

func TestSharedGoldenVectorParsesExactly(t *testing.T) {
	raw := sharedGoldenPacket(t)
	f, err := parseAudioFrame(raw, 16384)
	if err != nil {
		t.Fatalf("parse shared golden vector: %v", err)
	}
	if f.Epoch != 0x0102030405060708 ||
		f.Sequence != 0x1112131415161718 ||
		f.CaptureNS != 0x2122232425262728 ||
		f.SamplePosition != 960 ||
		f.RelayIngressNS != 0 ||
		f.SampleRate != 48000 ||
		f.FrameSamples != 960 ||
		f.Channels != 2 ||
		f.Codec != codecOpus ||
		f.Layer != 0 ||
		f.Flags != flagDiscontinuity ||
		f.PayloadLen != 4 {
		t.Fatalf("unexpected parsed fields: %+v", f)
	}
	if got := hex.EncodeToString(f.Raw[64:]); got != "deadbeef" {
		t.Fatalf("payload=%s", got)
	}
}

func TestCanonicalParserRejectsMalformedFrames(t *testing.T) {
	base := sharedGoldenPacket(t)
	tests := []struct {
		name string
		edit func([]byte) []byte
		want error
	}{
		{"truncated", func(b []byte) []byte { return b[:63] }, errShortFrame},
		{"bad-magic", func(b []byte) []byte { b[0] = 'X'; return b }, errBadMagic},
		{"bad-version", func(b []byte) []byte { b[4] = 2; return b }, errBadVersion},
		{"bad-type", func(b []byte) []byte { b[5] = 2; return b }, errBadType},
		{"bad-header-length", func(b []byte) []byte { b[7] = 63; return b }, errBadHeaderLen},
		{"wrong-sample-rate", func(b []byte) []byte { b[48], b[49], b[50], b[51] = 0, 0, 0, 1; return b }, errBadAudioMeta},
		{"wrong-channels", func(b []byte) []byte { b[54] = 1; return b }, errBadAudioMeta},
		{"wrong-codec", func(b []byte) []byte { b[55] = 2; return b }, errBadAudioMeta},
		{"unsupported-flags", func(b []byte) []byte { b[6] = 0x80; return b }, errBadFlags},
		{"reserved-byte", func(b []byte) []byte { b[57] = 1; return b }, errBadReserved},
		{"payload-length", func(b []byte) []byte { b[59] = 5; return b }, errBadPayloadLen},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			b := append([]byte(nil), base...)
			b = tc.edit(b)
			_, err := parseAudioFrame(b, 16384)
			if !errors.Is(err, tc.want) {
				t.Fatalf("got %v want %v", err, tc.want)
			}
		})
	}
}
