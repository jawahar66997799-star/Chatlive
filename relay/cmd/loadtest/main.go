package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

type helloHost struct {
	Type         string `json:"type"`
	V            int    `json:"v"`
	RoomID       string `json:"room_id"`
	HostSecret   string `json:"host_secret"`
	Epoch        uint64 `json:"epoch"`
	Codec        string `json:"codec"`
	SampleRate   uint32 `json:"sample_rate"`
	Channels     uint8  `json:"channels"`
	Layer        uint8  `json:"layer"`
	FrameSamples uint16 `json:"frame_samples"`
}

type guestStat struct {
	frames atomic.Uint64
	bytes  atomic.Uint64
}

func frame(epoch, seq, sample uint64) []byte {
	payload := make([]byte, 200)
	for i := range payload { payload[i] = byte((int(seq)+i)&255) }
	b := make([]byte, 64+len(payload))
	copy(b[0:4], []byte("JLS1"))
	b[4] = 1
	b[5] = 1
	b[6] = 0
	b[7] = 64
	binary.BigEndian.PutUint64(b[8:16], epoch)
	binary.BigEndian.PutUint64(b[16:24], seq)
	binary.BigEndian.PutUint64(b[24:32], uint64(time.Now().UnixNano()))
	binary.BigEndian.PutUint64(b[32:40], sample)
	binary.BigEndian.PutUint64(b[40:48], 0)
	binary.BigEndian.PutUint32(b[48:52], 48000)
	binary.BigEndian.PutUint16(b[52:54], 960)
	b[54] = 2
	b[55] = 1
	b[56] = 0
	b[57] = 0
	binary.BigEndian.PutUint16(b[58:60], uint16(len(payload)))
	binary.BigEndian.PutUint32(b[60:64], 0)
	copy(b[64:], payload)
	return b
}

func main() {
	host := getenv("TARGET_HOST", "")
	if host == "" { log.Fatal("TARGET_HOST required") }
	room := getenv("JLS_ROOM_ID", "")
	hostSecret := getenv("JLS_HOST_SECRET", "")
	guestToken := getenv("JLS_GUEST_TOKEN", "")
	scheme := getenv("TARGET_WS_SCHEME", "ws")
	durationSec, _ := strconv.Atoi(getenv("PHASE_SECONDS", "12"))
	phaseRaw := getenv("PHASES", "1,10,50,100,1000")
	var phases []int
	for _, p := range splitComma(phaseRaw) {
		n, err := strconv.Atoi(p)
		if err == nil && n > 0 { phases = append(phases, n) }
	}
	if len(phases)==0 { log.Fatal("no phases") }

	epoch := uint64(time.Now().UnixNano())
	hostURL := fmt.Sprintf("%s://%s/v1/ws/host", scheme, host)
	hc, _, err := websocket.DefaultDialer.Dial(hostURL, nil)
	if err != nil { log.Fatalf("host dial: %v", err) }
	defer hc.Close()
	h := helloHost{Type:"hello_host",V:1,RoomID:room,HostSecret:hostSecret,Epoch:epoch,Codec:"opus",SampleRate:48000,Channels:2,Layer:0,FrameSamples:960}
	if err := hc.WriteJSON(h); err != nil { log.Fatalf("host hello: %v", err) }
	_, ack, err := hc.ReadMessage()
	if err != nil { log.Fatalf("host ack: %v", err) }
	log.Printf("host_ack=%s", string(ack))

	var seq atomic.Uint64
	stopHost := make(chan struct{})
	go func(){
		t := time.NewTicker(20*time.Millisecond)
		defer t.Stop()
		for {
			select {
			case <-stopHost: return
			case <-t.C:
				s := seq.Add(1)-1
				if err := hc.WriteMessage(websocket.BinaryMessage, frame(epoch,s,s*960)); err != nil { return }
			}
		}
	}()
	defer close(stopHost)

	for _, n := range phases {
		runPhase(scheme,host,guestToken,n,time.Duration(durationSec)*time.Second,seq.Load())
		time.Sleep(2*time.Second)
	}
}

func runPhase(scheme, host, token string, n int, dur time.Duration, seqStart uint64) {
	var connected atomic.Int64
	var failed atomic.Int64
	stats := make([]*guestStat,n)
	conns := make([]*websocket.Conn,n)
	var wg sync.WaitGroup
	start := time.Now()
	for i:=0;i<n;i++ {
		wg.Add(1)
		go func(i int){
			defer wg.Done()
			u := fmt.Sprintf("%s://%s/v1/ws/guest/%s", scheme,host,token)
			c,_,err := websocket.DefaultDialer.Dial(u,nil)
			if err != nil { failed.Add(1); return }
			conns[i]=c
			stats[i]=&guestStat{}
			connected.Add(1)
			_ = c.SetReadDeadline(time.Now().Add(dur+8*time.Second))
			for {
				mt,b,err := c.ReadMessage()
				if err != nil { return }
				if mt==websocket.BinaryMessage {
					stats[i].frames.Add(1)
					stats[i].bytes.Add(uint64(len(b)))
				}
			}
		}(i)
	}
	deadline := time.Now().Add(dur)
	for time.Now().Before(deadline) { time.Sleep(100*time.Millisecond) }
	for _,c := range conns { if c!=nil { _=c.Close() } }
	wg.Wait()
	var frames, bytes uint64
	for _,s := range stats { if s!=nil { frames+=s.frames.Load(); bytes+=s.bytes.Load() } }
	out := map[string]any{
		"type":"phase_result","requested_guests":n,"connected":connected.Load(),"connection_failures":failed.Load(),
		"duration_ms":time.Since(start).Milliseconds(),"host_seq_start":seqStart,"host_seq_end":seqStart+uint64(dur/(20*time.Millisecond)),
		"guest_frames_received_total":frames,"guest_bytes_received_total":bytes,
	}
	b,_:=json.Marshal(out)
	fmt.Println(string(b))
}

func getenv(k,d string) string { if v:=os.Getenv(k);v!="" { return v }; return d }
func splitComma(s string) []string {
	var out []string
	start:=0
	for i:=0;i<=len(s);i++ {
		if i==len(s) || s[i]==',' {
			if i>start { out=append(out,s[start:i]) }
			start=i+1
		}
	}
	return out
}
