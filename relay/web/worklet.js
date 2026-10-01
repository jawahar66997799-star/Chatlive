class JawaharSyncProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.queue = [];
    this.active = null;
    this.activeOffset = 0;
    this.targetStartFrame = null;
    this.channels = 2;
    this.lateFrames = 0;
    this.port.onmessage = (e) => {
      const m = e.data;
      if (m.type === 'reset') {
        this.queue = [];
        this.active = null;
        this.activeOffset = 0;
        this.targetStartFrame = null;
        return;
      }
      if (m.type === 'pcm') {
        this.queue.push({
          targetFrame: m.targetFrame,
          left: m.left,
          right: m.right || m.left,
          offset: 0
        });
        if (this.queue.length > 512) this.queue.splice(0, this.queue.length - 512);
      }
    };
  }

  process(inputs, outputs) {
    const out = outputs[0];
    const L = out[0];
    const R = out[1] || out[0];
    L.fill(0);
    R.fill(0);

    let i = 0;
    while (i < L.length) {
      if (!this.active) {
        if (!this.queue.length) break;
        const next = this.queue[0];
        const frameNow = currentFrame + i;
        if (frameNow < next.targetFrame) {
          i += Math.min(L.length - i, next.targetFrame - frameNow);
          continue;
        }
        this.active = this.queue.shift();
        this.activeOffset = 0;
        if (frameNow > this.active.targetFrame + 256) {
          this.lateFrames++;
          if (this.lateFrames % 20 === 1) {
            this.port.postMessage({type:'late', count:this.lateFrames, byFrames:frameNow - this.active.targetFrame});
          }
        }
      }

      const a = this.active;
      const remain = a.left.length - this.activeOffset;
      const n = Math.min(remain, L.length - i);
      L.set(a.left.subarray(this.activeOffset, this.activeOffset + n), i);
      R.set(a.right.subarray(this.activeOffset, this.activeOffset + n), i);
      i += n;
      this.activeOffset += n;
      if (this.activeOffset >= a.left.length) {
        this.active = null;
        this.activeOffset = 0;
      }
    }
    return true;
  }
}
registerProcessor('jawahar-sync-processor', JawaharSyncProcessor);
