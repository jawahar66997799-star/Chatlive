package main

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
)

const (
	protocolVersion = 1
	audioHeaderLen  = 64

	msgAudio = 0x01

	codecOpus = 0x01

	flagDiscontinuity = 1 << 0
	flagKeyBoundary   = 1 << 1
)

var (
	errShortFrame      = errors.New("audio frame shorter than 64-byte header")
	errBadMagic        = errors.New("bad audio frame magic")
	errBadVersion      = errors.New("unsupported protocol version")
	errBadType         = errors.New("unsupported binary message type")
	errBadHeaderLen    = errors.New("invalid audio header length")
	errBadPayloadLen   = errors.New("payload length mismatch")
	errPayloadTooLarge = errors.New("payload exceeds configured maximum")
	errBadReserved      = errors.New("reserved audio header bytes must be zero")
	errBadFlags         = errors.New("unsupported audio frame flags")
	errBadAudioMeta     = errors.New("invalid audio metadata")
)

type AudioFrame struct {
	Raw             []byte
	Flags           uint8
	Epoch           uint64
	Sequence        uint64
	CaptureNS       uint64
	SamplePosition  uint64
	RelayIngressNS  uint64
	SampleRate      uint32
	FrameSamples    uint16
	Channels        uint8
	Codec           uint8
	Layer           uint8
	PayloadLen      uint16
	NominalServerNS uint64
}

func parseAudioFrame(data []byte, maxPayload int) (*AudioFrame, error) {
	if len(data) < audioHeaderLen {
		return nil, errShortFrame
	}
	if string(data[0:4]) != "JLS1" {
		return nil, errBadMagic
	}
	if int(data[4]) != protocolVersion {
		return nil, errBadVersion
	}
	if data[5] != msgAudio {
		return nil, errBadType
	}
	if int(data[7]) != audioHeaderLen {
		return nil, errBadHeaderLen
	}
	if data[6] & ^uint8(flagDiscontinuity|flagKeyBoundary) != 0 {
		return nil, errBadFlags
	}
	if data[57] != 0 || binary.BigEndian.Uint32(data[60:64]) != 0 {
		return nil, errBadReserved
	}
	epoch := binary.BigEndian.Uint64(data[8:16])
	sampleRate := binary.BigEndian.Uint32(data[48:52])
	frameSamples := binary.BigEndian.Uint16(data[52:54])
	channels := data[54]
	codec := data[55]
	layer := data[56]
	if epoch == 0 || sampleRate != 48000 || (frameSamples != 480 && frameSamples != 960) || channels != 2 || codec != codecOpus || layer > 1 {
		return nil, errBadAudioMeta
	}
	payloadLen := int(binary.BigEndian.Uint16(data[58:60]))
	if payloadLen < 1 {
		return nil, errBadPayloadLen
	}
	if payloadLen > maxPayload {
		return nil, errPayloadTooLarge
	}
	if len(data) != audioHeaderLen+payloadLen {
		return nil, errBadPayloadLen
	}

	raw := make([]byte, len(data))
	copy(raw, data)

	return &AudioFrame{
		Raw:            raw,
		Flags:          data[6],
		Epoch:          epoch,
		Sequence:       binary.BigEndian.Uint64(data[16:24]),
		CaptureNS:      binary.BigEndian.Uint64(data[24:32]),
		SamplePosition: binary.BigEndian.Uint64(data[32:40]),
		RelayIngressNS: binary.BigEndian.Uint64(data[40:48]),
		SampleRate:     sampleRate,
		FrameSamples:   frameSamples,
		Channels:       channels,
		Codec:          codec,
		Layer:          layer,
		PayloadLen:     uint16(payloadLen),
	}, nil
}

func (f *AudioFrame) stampRelayIngress(serverNS uint64) {
	f.RelayIngressNS = serverNS
	binary.BigEndian.PutUint64(f.Raw[40:48], serverNS)
}

type HostHello struct {
	Type          string  `json:"type"`
	V             int     `json:"v"`
	RoomID        string  `json:"room_id"`
	HostSecret    string  `json:"host_secret"`
	Epoch         uint64  `json:"epoch"`
	Codec         string  `json:"codec"`
	SampleRate    uint32  `json:"sample_rate"`
	Channels      uint8   `json:"channels"`
	Layer         uint8   `json:"layer"`
	FrameSamples  uint16  `json:"frame_samples"`
	ResumeLastSeq *uint64 `json:"resume_last_seq,omitempty"`
}

type GuestControl struct {
	Type          string  `json:"type"`
	V             int     `json:"v"`
	ID            string  `json:"id,omitempty"`
	T0GuestNS     uint64  `json:"t0_guest_ns,omitempty"`
	Epoch         uint64  `json:"epoch,omitempty"`
	LastSequence  uint64  `json:"last_sequence,omitempty"`
	BufferDepthMS float64 `json:"buffer_depth_ms,omitempty"`
	Underruns     uint64  `json:"underruns,omitempty"`
	LateFrames    uint64  `json:"late_frames,omitempty"`
}

type HostControl struct {
	Type  string `json:"type"`
	V     int    `json:"v"`
	ID    string `json:"id,omitempty"`
	T0NS  uint64 `json:"t0_ns,omitempty"`
	State string `json:"state,omitempty"`
}

func encodeJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

func protocolError(code, message string, retryable bool) []byte {
	return encodeJSON(map[string]any{
		"type":      "error",
		"v":         protocolVersion,
		"code":      code,
		"message":   message,
		"retryable": retryable,
	})
}

func validateHostHello(h *HostHello) error {
	if h.Type != "hello_host" || h.V != protocolVersion {
		return fmt.Errorf("invalid hello_host version/type")
	}
	if h.RoomID == "" || h.HostSecret == "" {
		return fmt.Errorf("room_id and host_secret are required")
	}
	if h.Epoch == 0 {
		return fmt.Errorf("epoch must be non-zero")
	}
	if h.Codec != "opus" {
		return fmt.Errorf("codec must be opus")
	}
	if h.SampleRate != 48000 {
		return fmt.Errorf("sample_rate must be 48000")
	}
	if h.Channels != 2 {
		return fmt.Errorf("channels must be 2")
	}
	if h.FrameSamples != 480 && h.FrameSamples != 960 {
		return fmt.Errorf("frame_samples must be 480 (10 ms) or 960 (20 ms)")
	}
	if h.Layer > 1 {
		return fmt.Errorf("layer must be 0 or 1")
	}
	return nil
}
