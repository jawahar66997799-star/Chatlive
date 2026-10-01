package main

import (
  "context"
  "encoding/binary"
  "encoding/json"
  "flag"
  "fmt"
  "io"
  "net/http"
  "net/url"
  "os"
  "strconv"
  "strings"
  "sync"
  "sync/atomic"
  "time"

  "github.com/gorilla/websocket"
)

type suiteResult struct {
  GuestsTarget int `json:"guests_target"`
  GuestsConnected int64 `json:"guests_connected"`
  ConnectionFailures int64 `json:"connection_failures"`
  HostFrames int64 `json:"host_frames"`
  HostBytes int64 `json:"host_bytes"`
  GuestFrames int64 `json:"guest_frames"`
  GuestBytes int64 `json:"guest_bytes"`
  BackpressureDropsBefore uint64 `json:"backpressure_drops_before"`
  BackpressureDropsAfter uint64 `json:"backpressure_drops_after"`
  RelayFramesBefore uint64 `json:"relay_frames_before"`
  RelayFramesAfter uint64 `json:"relay_frames_after"`
  DurationMS int64 `json:"duration_ms"`
}

type counters struct { connected, failures, frames, bytes atomic.Int64 }

func main() {
  base := flag.String("base", os.Getenv("JLS_BASE_URL"), "https base")
  room := flag.String("room", os.Getenv("JLS_ROOM_ID"), "room")
  hostSecret := flag.String("host-secret", os.Getenv("JLS_HOST_SECRET"), "host secret")
  guestToken := flag.String("guest-token", os.Getenv("JLS_GUEST_TOKEN"), "guest token")
  suitesText := flag.String("suites", "1,10,50,100", "guest counts")
  secs := flag.Int("seconds", 8, "stream seconds")
  flag.Parse()
  if *base == "" || *room == "" || *hostSecret == "" || *guestToken == "" { panic("missing config") }
  counts := []int{}
  for _, s := range strings.Split(*suitesText, ",") { n, err := strconv.Atoi(strings.TrimSpace(s)); if err != nil { panic(err) }; counts = append(counts,n) }
  for i,n := range counts {
    prehost := i==0
    r, err := runSuite(*base,*room,*hostSecret,*guestToken,n,time.Duration(*secs)*time.Second,prehost)
    if err != nil { fmt.Printf("SUITE_ERROR guests=%d err=%q\n", n, err.Error()); continue }
    b,_:=json.Marshal(r); fmt.Printf("SUITE_RESULT %s\n",b)
    time.Sleep(2*time.Second)
  }
}

func runSuite(base, room, hostSecret, guestToken string, guests int, duration time.Duration, prehost bool) (suiteResult,error) {
  start := time.Now(); before := metrics(base)
  ctx,cancel := context.WithCancel(context.Background()); defer cancel()
  var cs counters
  var wg sync.WaitGroup
  guestURL := toWS(base)+"/v1/ws/guest/"+url.PathEscape(guestToken)
  startGuest := func(){
    wg.Add(1); go func(){ defer wg.Done(); c,_,err:=websocket.DefaultDialer.Dial(guestURL,nil); if err!=nil { cs.failures.Add(1); return }; cs.connected.Add(1); defer c.Close(); c.SetReadLimit(1<<20)
      for { select {case <-ctx.Done(): return; default:}; mt,p,err:=c.ReadMessage(); if err!=nil { return }; if mt==websocket.BinaryMessage { cs.frames.Add(1); cs.bytes.Add(int64(len(p))) } }
    }()
  }
  if prehost { for i:=0;i<guests;i++{ startGuest() }; time.Sleep(700*time.Millisecond) }
  epoch:=uint64(time.Now().UnixNano() & 0x7fffffffffffffff); if epoch==0 {epoch=1}
  hc,_,err:=websocket.DefaultDialer.Dial(toWS(base)+"/v1/ws/host",nil); if err!=nil { cancel(); wg.Wait(); return suiteResult{},err }; defer hc.Close()
  hello:=map[string]any{"type":"hello_host","v":1,"room_id":room,"host_secret":hostSecret,"epoch":epoch,"codec":"opus","sample_rate":48000,"channels":2,"layer":0,"frame_samples":960}
  if err:=hc.WriteJSON(hello);err!=nil{return suiteResult{},err}
  _,ack,err:=hc.ReadMessage();if err!=nil{return suiteResult{},err}; if !strings.Contains(string(ack),"hello_host_ack"){return suiteResult{},fmt.Errorf("unexpected host ack %s",string(ack))}
  if !prehost { for i:=0;i<guests;i++{ startGuest() } }
  deadline:=time.Now().Add(8*time.Second); for cs.connected.Load()+cs.failures.Load()<int64(guests) && time.Now().Before(deadline){time.Sleep(20*time.Millisecond)}
  time.Sleep(500*time.Millisecond)
  ticker:=time.NewTicker(20*time.Millisecond); defer ticker.Stop(); stop:=time.Now().Add(duration); seq:=uint64(0); sample:=uint64(0); var hostBytes int64
  payload:=make([]byte,360); for i:=range payload{payload[i]=byte(i)}
  for time.Now().Before(stop){ <-ticker.C; f:=frame(epoch,seq,sample,payload); if err:=hc.WriteMessage(websocket.BinaryMessage,f);err!=nil{break}; seq++; sample+=960; hostBytes+=int64(len(f)) }
  time.Sleep(1200*time.Millisecond); cancel(); _=hc.Close(); wg.Wait(); after:=metrics(base)
  return suiteResult{GuestsTarget:guests,GuestsConnected:cs.connected.Load(),ConnectionFailures:cs.failures.Load(),HostFrames:int64(seq),HostBytes:hostBytes,GuestFrames:cs.frames.Load(),GuestBytes:cs.bytes.Load(),BackpressureDropsBefore:before["jls_backpressure_drops_total"],BackpressureDropsAfter:after["jls_backpressure_drops_total"],RelayFramesBefore:before["jls_audio_frames_total"],RelayFramesAfter:after["jls_audio_frames_total"],DurationMS:time.Since(start).Milliseconds()},nil
}

func frame(epoch,seq,sample uint64,payload []byte)[]byte{ b:=make([]byte,64+len(payload)); copy(b[:4],[]byte("JLS1"));b[4]=1;b[5]=1;b[7]=64;binary.BigEndian.PutUint64(b[8:16],epoch);binary.BigEndian.PutUint64(b[16:24],seq);binary.BigEndian.PutUint64(b[24:32],uint64(time.Now().UnixNano()));binary.BigEndian.PutUint64(b[32:40],sample);binary.BigEndian.PutUint32(b[48:52],48000);binary.BigEndian.PutUint16(b[52:54],960);b[54]=2;b[55]=1;b[56]=0;binary.BigEndian.PutUint16(b[58:60],uint16(len(payload)));copy(b[64:],payload);return b }
func toWS(base string)string{ u,_:=url.Parse(base); if u.Scheme=="https"{u.Scheme="wss"}else{u.Scheme="ws"}; return strings.TrimRight(u.String(),"/") }
func metrics(base string)map[string]uint64{ out:=map[string]uint64{}; c:=http.Client{Timeout:5*time.Second}; r,err:=c.Get(strings.TrimRight(base,"/")+"/metrics"); if err!=nil{return out}; defer r.Body.Close(); b,_:=io.ReadAll(r.Body); for _,ln:=range strings.Split(string(b),"\n"){f:=strings.Fields(ln);if len(f)==2{v,_:=strconv.ParseUint(f[1],10,64);out[f[0]]=v}};return out }
