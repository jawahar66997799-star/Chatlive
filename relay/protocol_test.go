package main

import (
	"encoding/binary"
	"testing"
)

func testWireFrame(epoch, seq, sample uint64, payload []byte) []byte {
	b := make([]byte, audioHeaderLen+len(payload))
	copy(b[0:4], []byte("JLS1"))
	b[4] = protocolVersion
	b[5] = msgAudio
	b[6] = 0
	b[7] = audioHeaderLen
	binary.BigEndian.PutUint64(b[8:16], epoch)
	binary.BigEndian.PutUint64(b[16:24], seq)
	binary.BigEndian.PutUint64(b[24:32], 123456)
	binary.BigEndian.PutUint64(b[32:40], sample)
	binary.BigEndian.PutUint64(b[40:48], 0)
	binary.BigEndian.PutUint32(b[48:52], 48000)
	binary.BigEndian.PutUint16(b[52:54], 960)
	b[54] = 2
	b[55] = codecOpus
	b[56] = 0
	b[57] = 0
	binary.BigEndian.PutUint16(b[58:60], uint16(len(payload)))
	binary.BigEndian.PutUint32(b[60:64], 0)
	copy(b[64:], payload)
	return b
}

func TestParseAudioFrameV1(t *testing.T) {
	raw := testWireFrame(9, 42, 9600, []byte{1, 2, 3, 4})
	f, err := parseAudioFrame(raw, 1024)
	if err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if f.Epoch != 9 || f.Sequence != 42 || f.SamplePosition != 9600 {
		t.Fatalf("wrong identity fields: %+v", f)
	}
	if f.SampleRate != 48000 || f.FrameSamples != 960 || f.Channels != 2 || f.Codec != codecOpus {
		t.Fatalf("wrong audio metadata: %+v", f)
	}
}

func TestParseAudioFrameRejectsReservedBytes(t *testing.T) {
	raw := testWireFrame(1, 1, 0, []byte{1})
	raw[57] = 1
	if _, err := parseAudioFrame(raw, 1024); err != errBadReserved {
		t.Fatalf("expected errBadReserved, got %v", err)
	}
}

func TestParseAudioFrameRejectsOversizePayload(t *testing.T) {
	raw := testWireFrame(1, 1, 0, make([]byte, 100))
	if _, err := parseAudioFrame(raw, 99); err != errPayloadTooLarge {
		t.Fatalf("expected errPayloadTooLarge, got %v", err)
	}
}
