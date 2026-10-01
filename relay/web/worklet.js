const clamp=(v,lo,hi)=>Math.min(hi,Math.max(lo,v));

class PI {
  constructor(){ this.deadbandMs=1; this.kp=18; this.ki=.35; this.maxPpm=300; this.i=0; this.ppm=0; }
  reset(){this.i=0;this.ppm=0;}
  update(errorMs,dt){
    const e=Math.abs(errorMs)<=this.deadbandMs?0:errorMs-Math.sign(errorMs)*this.deadbandMs;
    this.i=clamp(this.i+e*clamp(dt,0,1),-500,500);
    this.ppm=clamp(this.kp*e+this.ki*this.i,-this.maxPpm,this.maxPpm);
    return 1+this.ppm/1e6;
  }
}

class JawaharSyncProcessor extends AudioWorkletProcessor {
  constructor(){
    super();
    this.queue=[]; this.active=null; this.pos=0; this.started=false;
    this.sourceRate=48000; this.hardResyncMs=100; this.controller=new PI();
    this.underruns=0; this.lateFrames=0; this.hardResyncs=0; this.overruns=0;
    this.lastMetricFrame=0; this.lastBoundaryFrame=0;
    this.sab=null; this.sabCtrl=null; this.sabCapacity=0;
    this.fadeFrames=Math.max(32,Math.round(sampleRate*.010));
    this.tailL=new Float32Array(this.fadeFrames); this.tailR=new Float32Array(this.fadeFrames); this.tailWrite=0;
    this.crossfade=null;
    this.port.onmessage=e=>this.onMessage(e.data||{});
  }

  onMessage(m){
    if(m.type==='config'){
      if(Number.isFinite(m.sourceRate))this.sourceRate=m.sourceRate;
      if(Number.isFinite(m.hardResyncMs))this.hardResyncMs=m.hardResyncMs;
      if(Number.isFinite(m.maxPpm))this.controller.maxPpm=m.maxPpm;
      if(Number.isFinite(m.deadbandMs))this.controller.deadbandMs=m.deadbandMs;
      return;
    }
    if(m.type==='sab-init'){
      this.sab=new Float32Array(m.pcmSab); this.sabCtrl=new Int32Array(m.ctrlSab); this.sabCapacity=m.capacityFrames|0; return;
    }
    if(m.type==='reset'){
      this.queue=[];this.active=null;this.pos=0;this.started=false;this.controller.reset();this.crossfade=null;
      if(this.sabCtrl){Atomics.store(this.sabCtrl,0,0);Atomics.store(this.sabCtrl,1,0);}
      return;
    }
    if(m.type==='pcm'){
      this.enqueue({targetFrame:m.targetFrame,startSample:m.startSample,frames:m.frames,pcm:m.pcm,sab:false}); return;
    }
    if(m.type==='sab-block'){
      this.enqueue({targetFrame:m.targetFrame,startSample:m.startSample,frames:m.frames,absStart:m.absStart,sab:true}); return;
    }
  }

  enqueue(b){
    if(!Number.isFinite(b.targetFrame)||!Number.isFinite(b.frames)||b.frames<=0)return;
    this.queue.push(b);
    if(this.queue.length>256){
      this.queue.splice(0,this.queue.length-128); this.overruns++; this.active=null; this.controller.reset();
      this.port.postMessage({type:'overrun',count:this.overruns});
    }
  }

  sampleAt(block, frame, channel){
    frame=clamp(frame,0,block.frames-1);
    if(block.sab){
      const f=(block.absStart+frame)%this.sabCapacity;
      return this.sab[f*2+channel]||0;
    }
    return block.pcm[frame*2+channel]||0;
  }

  finishActive(){
    if(this.active?.sab&&this.sabCtrl){
      Atomics.store(this.sabCtrl,1,(this.active.absStart+this.active.frames)|0);
    }
    this.active=null;this.pos=0;
  }

  captureTail(){
    const n=this.fadeFrames, l0=new Float32Array(n),r0=new Float32Array(n);
    for(let i=0;i<n;i++){
      const idx=(this.tailWrite+i)%n; l0[i]=this.tailL[idx];r0[i]=this.tailR[idx];
    }
    this.crossfade={l:l0,r:r0,pos:0};
  }

  beginNext(nowFrame){
    if(!this.queue.length)return false;
    const b=this.queue[0];
    if(nowFrame<b.targetFrame)return false;
    this.active=this.queue.shift(); this.pos=0; this.started=true;
    let errorFrames=nowFrame-this.active.targetFrame;
    let errorMs=errorFrames*1000/sampleRate;
    if(Math.abs(errorMs)>=this.hardResyncMs){
      this.hardResyncs++;
      this.captureTail();
      if(errorFrames>0){
        let skip=Math.floor(errorFrames*this.sourceRate/sampleRate);
        while(this.active&&skip>=this.active.frames){
          skip-=this.active.frames; this.finishActive();
          if(!this.queue.length)break;
          this.active=this.queue.shift();
        }
        if(this.active)this.pos=Math.min(skip,this.active.frames-1);
      }
      this.controller.reset();
      this.port.postMessage({type:'hard-resync',count:this.hardResyncs,errorMs});
      errorMs=0;
    }else if(errorMs>1){
      this.lateFrames++;
      if(this.lateFrames%10===1)this.port.postMessage({type:'late',count:this.lateFrames,errorMs});
    }
    const dt=this.lastBoundaryFrame?Math.max(1/100,(nowFrame-this.lastBoundaryFrame)/sampleRate):.02;
    this.lastBoundaryFrame=nowFrame;
    this.controller.update(errorMs,dt);
    return !!this.active;
  }

  process(_inputs,outputs){
    const out=outputs[0],L=out[0],R=out[1]||out[0]; L.fill(0);R.fill(0);
    let wroteAudio=false, underrunThisQuantum=false;
    for(let i=0;i<L.length;i++){
      const nowFrame=currentFrame+i;
      if(!this.active){
        if(!this.beginNext(nowFrame)){
          if(this.started&&this.queue.length===0&&!underrunThisQuantum) {
            underrunThisQuantum=true;
            this.underruns++;
            this.port.postMessage({type:'underrun',count:this.underruns});
          }
          this.pushTail(0,0); continue;
        }
      }
      if(!this.active){this.pushTail(0,0);continue;}
      const p0=Math.floor(this.pos), frac=this.pos-p0, p1=Math.min(this.active.frames-1,p0+1);
      let l=this.sampleAt(this.active,p0,0)*(1-frac)+this.sampleAt(this.active,p1,0)*frac;
      let r=this.sampleAt(this.active,p0,1)*(1-frac)+this.sampleAt(this.active,p1,1)*frac;
      if(this.crossfade&&this.crossfade.pos<this.fadeFrames){
        const x=this.crossfade.pos++, a=x/this.fadeFrames;
        l=this.crossfade.l[x]*(1-a)+l*a; r=this.crossfade.r[x]*(1-a)+r*a;
        if(this.crossfade.pos>=this.fadeFrames)this.crossfade=null;
      }
      L[i]=l;R[i]=r;wroteAudio=true;this.pushTail(l,r);
      const nominal=this.sourceRate/sampleRate;
      this.pos+=nominal*(1+this.controller.ppm/1e6);
      if(this.pos>=this.active.frames){ this.finishActive(); }
    }
    if(currentFrame-this.lastMetricFrame>=sampleRate/2){
      this.lastMetricFrame=currentFrame;
      let queued=this.active?Math.max(0,this.active.frames-this.pos):0;
      for(const b of this.queue)queued+=b.frames;
      this.port.postMessage({type:'metrics',bufferFrames:queued,bufferMs:queued*1000/this.sourceRate,ppm:this.controller.ppm,underruns:this.underruns,lateFrames:this.lateFrames,hardResyncs:this.hardResyncs,overruns:this.overruns,active:wroteAudio});
    }
    return true;
  }

  pushTail(l,r){this.tailL[this.tailWrite]=l;this.tailR[this.tailWrite]=r;this.tailWrite=(this.tailWrite+1)%this.fadeFrames;}
}
registerProcessor('jawahar-sync-processor',JawaharSyncProcessor);