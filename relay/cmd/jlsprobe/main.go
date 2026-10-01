package main

import (
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

func wsURL(base, path, room string) string {
	u, err := url.Parse(base)
	if err != nil { log.Fatal(err) }
	if u.Scheme == "https" { u.Scheme = "wss" } else if u.Scheme == "http" { u.Scheme = "ws" }
	u.Path = path
	q := u.Query()
	q.Set("room", room)
	u.RawQuery = q.Encode()
	return u.String()
}

func runHost(base, room string, seconds int) {
	c, _, err := websocket.DefaultDialer.Dial(wsURL(base, "/ws/host", room), nil)
	if err != nil { log.Fatal(err) }
	defer c.Close()
	hello := map[string]any{"type":"host-hello","sampleRate":48000,"channels":2}
	if err := c.WriteJSON(hello); err != nil { log.Fatal(err) }

	const sr = 48000
	const frames = 960
	const channels = 2
	buf := make([]byte, frames*channels*2)
	ticker := time.NewTicker(20*time.Millisecond)
	defer ticker.Stop()
	deadline := time.Now().Add(time.Duration(seconds)*time.Second)
	var phase float64
	step := 2*math.Pi*440.0/sr

	for time.Now().Before(deadline) {
		for i:=0;i<frames;i++ {
			s := int16(math.Sin(phase)*0.15*32767)
			phase += step
			for ch:=0; ch<channels; ch++ {
				off := (i*channels+ch)*2
				binary.LittleEndian.PutUint16(buf[off:off+2], uint16(s))
			}
		}
		if err := c.WriteMessage(websocket.BinaryMessage, buf); err != nil { log.Fatal(err) }
		<-ticker.C
	}
}

type guestStats struct {
	frames atomic.Uint64
	missing atomic.Uint64
	resets atomic.Uint64
	lastSeq atomic.Uint32
	lastEpoch atomic.Uint32
}

func runGuest(base, room string, seconds int, reconnect bool) {
	var stats guestStats
	end := time.Now().Add(time.Duration(seconds)*time.Second)
	for time.Now().Before(end) {
		c, _, err := websocket.DefaultDialer.Dial(wsURL(base, "/ws/listen", room), nil)
		if err != nil {
			if !reconnect { log.Fatal(err) }
			time.Sleep(500*time.Millisecond)
			continue
		}
		_ = c.WriteJSON(map[string]any{"type":"ready"})
		_ = c.SetReadDeadline(time.Now().Add(3*time.Second))
		for time.Now().Before(end) {
			mt, data, err := c.ReadMessage()
			if err != nil { _ = c.Close(); break }
			_ = c.SetReadDeadline(time.Now().Add(3*time.Second))
			if mt == websocket.TextMessage {
				var m map[string]any
				_ = json.Unmarshal(data,&m)
				continue
			}
			if mt != websocket.BinaryMessage || len(data)<24 || string(data[:4])!="JLS1" { continue }
			epoch := binary.BigEndian.Uint32(data[4:8])
			seq := binary.BigEndian.Uint32(data[8:12])
			prevEpoch := stats.lastEpoch.Load()
			prevSeq := stats.lastSeq.Load()
			if prevEpoch==epoch && prevSeq!=0 {
				if seq>prevSeq+1 { stats.missing.Add(uint64(seq-prevSeq-1)) }
				if seq<=prevSeq { stats.resets.Add(1) }
			}
			stats.lastEpoch.Store(epoch)
			stats.lastSeq.Store(seq)
			stats.frames.Add(1)
		}
		if !reconnect { break }
		time.Sleep(500*time.Millisecond)
	}
	fmt.Printf("[SIMULATED] synthetic relay probe frames=%d missing_sequence=%d resets_or_reorders=%d last_epoch=%d last_seq=%d\n",
		stats.frames.Load(),stats.missing.Load(),stats.resets.Load(),stats.lastEpoch.Load(),stats.lastSeq.Load())
}

func main() {
	role := flag.String("role","guest","host or guest")
	base := flag.String("base","http://127.0.0.1:8080","relay base URL")
	room := flag.String("room","benchroom","room")
	seconds := flag.Int("seconds",30,"run duration")
	reconnect := flag.Bool("reconnect",true,"guest reconnect on disconnect")
	flag.Parse()

	sig := make(chan os.Signal,1)
	signal.Notify(sig,os.Interrupt)
	go func(){ <-sig; os.Exit(130) }()

	switch strings.ToLower(*role) {
	case "host": runHost(*base,*room,*seconds)
	case "guest": runGuest(*base,*room,*seconds,*reconnect)
	default: log.Fatal("role must be host or guest")
	}
}
