package main

import (
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func smokeServer(t *testing.T) (*httptest.Server, *Server, Config) {
	t.Helper()
	cfg := testConfig()
	cfg.CommonDelay = 2 * time.Second
	cfg.JoinGuard = 10 * time.Millisecond
	m := &Metrics{}
	s := &Server{cfg: cfg, metrics: m, ip: newIPLimiter(cfg.MaxIPConns)}
	s.room = newRoom(cfg, m)
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/ws/host", s.hostWS)
	mux.HandleFunc("/v1/ws/guest/", s.guestWS)
	return httptest.NewServer(mux), s, cfg
}

func wsFromHTTP(base, path string) string {
	u, _ := url.Parse(base)
	if u.Scheme == "https" { u.Scheme = "wss" } else { u.Scheme = "ws" }
	u.Path = path
	return u.String()
}

func dialWS(t *testing.T, raw string) *websocket.Conn {
	t.Helper()
	c, _, err := websocket.DefaultDialer.Dial(raw, nil)
	if err != nil { t.Fatalf("dial %s: %v", raw, err) }
	_ = c.SetReadDeadline(time.Now().Add(3*time.Second))
	return c
}

func sendHostHello(t *testing.T, c *websocket.Conn, cfg Config, epoch uint64, resume *uint64) map[string]any {
	t.Helper()
	h := HostHello{
		Type:"hello_host", V:protocolVersion, RoomID:cfg.RoomID, HostSecret:cfg.HostSecret,
		Epoch:epoch, Codec:"opus", SampleRate:48000, Channels:2, Layer:0, FrameSamples:960,
		ResumeLastSeq:resume,
	}
	if err:=c.WriteJSON(h); err!=nil { t.Fatal(err) }
	var ack map[string]any
	if err:=c.ReadJSON(&ack); err!=nil { t.Fatal(err) }
	if ack["type"]!="hello_host_ack" { t.Fatalf("unexpected host ack: %#v",ack) }
	return ack
}

func smokeFrame(epoch,seq,sample uint64,payload []byte) []byte {
	b:=make([]byte,audioHeaderLen+len(payload))
	copy(b[:4],[]byte("JLS1"))
	b[4]=protocolVersion; b[5]=msgAudio; b[7]=audioHeaderLen
	binary.BigEndian.PutUint64(b[8:16],epoch)
	binary.BigEndian.PutUint64(b[16:24],seq)
	binary.BigEndian.PutUint64(b[24:32],123456789+seq)
	binary.BigEndian.PutUint64(b[32:40],sample)
	binary.BigEndian.PutUint32(b[48:52],48000)
	binary.BigEndian.PutUint16(b[52:54],960)
	b[54]=2; b[55]=codecOpus; b[56]=0
	binary.BigEndian.PutUint16(b[58:60],uint16(len(payload)))
	copy(b[64:],payload)
	return b
}

func readState(t *testing.T,c *websocket.Conn) map[string]any {
	t.Helper()
	for i:=0;i<4;i++ {
		mt,b,err:=c.ReadMessage(); if err!=nil {t.Fatal(err)}
		if mt!=websocket.TextMessage {continue}
		var m map[string]any
		if json.Unmarshal(b,&m)==nil && m["type"]=="state" {return m}
	}
	t.Fatal("state not received"); return nil
}

func readBinary(t *testing.T,c *websocket.Conn) []byte {
	t.Helper()
	for i:=0;i<6;i++ {
		mt,b,err:=c.ReadMessage(); if err!=nil {t.Fatal(err)}
		if mt==websocket.BinaryMessage {return b}
	}
	t.Fatal("binary frame not received"); return nil
}

func TestSyntheticHostRelayGuestResumeSmoke(t *testing.T) {
	ts,_,cfg:=smokeServer(t); defer ts.Close()
	const epoch=uint64(77)

	host:=dialWS(t,wsFromHTTP(ts.URL,"/v1/ws/host"))
	ack:=sendHostHello(t,host,cfg,epoch,nil)
	if ack["v"].(float64)!=1 {t.Fatalf("host version ack=%#v",ack)}

	guest:=dialWS(t,wsFromHTTP(ts.URL,"/v1/ws/guest/"+cfg.GuestToken))
	state:=readState(t,guest)
	if state["host_online"]!=true {t.Fatalf("host not online in guest state: %#v",state)}

	if err:=guest.WriteJSON(map[string]any{"type":"clock_req","v":1,"id":"smoke","t0_guest_ns":uint64(123)});err!=nil{t.Fatal(err)}
	for {
		mt,b,err:=guest.ReadMessage(); if err!=nil{t.Fatal(err)}
		if mt!=websocket.TextMessage{continue}
		var m map[string]any; if json.Unmarshal(b,&m)!=nil{continue}
		if m["type"]=="clock_resp" {
			if m["id"]!="smoke" {t.Fatalf("clock id=%#v",m)}
			if m["t1_server_ns"].(float64)>m["t2_server_ns"].(float64){t.Fatalf("clock timestamps reversed: %#v",m)}
			break
		}
	}

	if err:=host.WriteMessage(websocket.BinaryMessage,smokeFrame(epoch,1,0,[]byte{1,2,3}));err!=nil{t.Fatal(err)}
	var got []byte
	seenTimelineAnchor:=false
	for i:=0;i<8;i++ {
		mt,b,err:=guest.ReadMessage();if err!=nil{t.Fatal(err)}
		if mt==websocket.TextMessage {
			var m map[string]any
			if json.Unmarshal(b,&m)==nil&&m["type"]=="state"&&m["reason"]=="timeline_started" {
				if m["timeline_ready"]!=true {t.Fatalf("timeline_started not ready: %#v",m)}
				tl,ok:=m["timeline"].(map[string]any);if !ok {t.Fatalf("missing timeline: %#v",m)}
				if origin,ok:=tl["origin_server_ns"].(float64);!ok||origin<=0 {t.Fatalf("invalid timeline origin: %#v",tl)}
				seenTimelineAnchor=true
			}
			continue
		}
		if mt==websocket.BinaryMessage {
			if !seenTimelineAnchor {t.Fatal("first binary audio arrived before canonical timeline anchor state")}
			got=b
			break
		}
	}
	if got==nil {t.Fatal("first binary frame not received")}
	f,err:=parseAudioFrame(got,16384);if err!=nil{t.Fatal(err)}
	if f.Epoch!=epoch||f.Sequence!=1||f.SamplePosition!=0||f.RelayIngressNS==0 {t.Fatalf("bad relayed frame: %+v",f)}

	_ = host.Close()
	time.Sleep(20*time.Millisecond)
	last:=uint64(1)
	host2:=dialWS(t,wsFromHTTP(ts.URL,"/v1/ws/host")); defer host2.Close()
	ack2:=sendHostHello(t,host2,cfg,epoch,&last)
	if got:=uint64(ack2["resume_after_sequence"].(float64));got!=1 {t.Fatalf("resume_after_sequence=%d",got)}
	if err:=host2.WriteMessage(websocket.BinaryMessage,smokeFrame(epoch,2,960,[]byte{4,5,6}));err!=nil{t.Fatal(err)}

	_ = guest.Close()
	resumeURL:=wsFromHTTP(ts.URL,"/v1/ws/guest/"+cfg.GuestToken)+"?epoch=77&seq=1"
	guest2:=dialWS(t,resumeURL); defer guest2.Close()
	state2:=readState(t,guest2)
	if state2["host_online"]!=true {t.Fatalf("reconnect state=%#v",state2)}
	got2:=readBinary(t,guest2)
	f2,err:=parseAudioFrame(got2,16384);if err!=nil{t.Fatal(err)}
	if f2.Sequence!=2||f2.SamplePosition!=960 {t.Fatalf("resume frame=%+v",f2)}
}

func TestSyntheticHostRejectsBadSecret(t *testing.T) {
	ts,_,cfg:=smokeServer(t); defer ts.Close()
	host:=dialWS(t,wsFromHTTP(ts.URL,"/v1/ws/host")); defer host.Close()
	h:=HostHello{Type:"hello_host",V:1,RoomID:cfg.RoomID,HostSecret:strings.Repeat("x",32),Epoch:9,Codec:"opus",SampleRate:48000,Channels:2,FrameSamples:960}
	if err:=host.WriteJSON(h);err!=nil{t.Fatal(err)}
	mt,b,err:=host.ReadMessage();if err!=nil{t.Fatal(err)}
	if mt!=websocket.TextMessage||!strings.Contains(string(b),"unauthorized"){t.Fatalf("expected unauthorized, got %s",b)}
}
