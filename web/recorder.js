/* Hands-free 16kHz PCM recorder + Screen Wake Lock + buffered chunk uploader */
window.StoryRecorder = (() => {
  "use strict";

  const TARGET_SAMPLE_RATE = 16000;
  const CHUNK_FLUSH_MS = 260;
  const DOUBLE_TAP_GUARD_MS = 850;

  function downsampleTo16kPcm(float32Buf, inputRate) {
    if (!float32Buf || float32Buf.length === 0) return new Int16Array(0);
    if (inputRate === TARGET_SAMPLE_RATE) {
      const out = new Int16Array(float32Buf.length);
      for (let i = 0; i < float32Buf.length; i++) {
        const s = Math.max(-1, Math.min(1, float32Buf[i]));
        out[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
      }
      return out;
    }
    const ratio = inputRate / TARGET_SAMPLE_RATE;
    const outLen = Math.floor(float32Buf.length / ratio);
    const out = new Int16Array(outLen);
    let offsetResult = 0;
    let offsetBuffer = 0;
    while (offsetResult < outLen) {
      const nextOffset = Math.min(float32Buf.length, Math.round((offsetResult + 1) * ratio));
      let accum = 0;
      let count = 0;
      for (let i = offsetBuffer; i < nextOffset; i++) {
        accum += float32Buf[i];
        count++;
      }
      const avg = count > 0 ? accum / count : 0;
      const clamped = Math.max(-1, Math.min(1, avg));
      out[offsetResult] = clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff;
      offsetResult++;
      offsetBuffer = nextOffset;
    }
    return out;
  }

  function concatUint8(chunks, totalBytes) {
    const merged = new Uint8Array(totalBytes);
    let offset = 0;
    for (const c of chunks) {
      merged.set(c, offset);
      offset += c.byteLength;
    }
    return merged;
  }

  class RecorderController {
    constructor(callbacks) {
      this.cb = callbacks || {};
      this.isRecording = false;
      this.isTransitioning = false;
      this.startedAtMs = 0;
      this.recId = null;
      this.chunkSeq = 0;
      this.pendingChunks = [];
      this.pendingBytes = 0;
      this.uploadInFlight = false;
      this.flushTimer = null;
      this.tickTimer = null;
      this.mediaStream = null;
      this.audioCtx = null;
      this.sourceNode = null;
      this.processorNode = null;
      this.wakeLock = null;
      this.currentLevel = 0;

      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible" && this.isRecording) {
          this._acquireWakeLock();
        }
      });
    }

    async _acquireWakeLock() {
      if (!("wakeLock" in navigator)) return;
      try {
        this.wakeLock = await navigator.wakeLock.request("screen");
      } catch (_e) {}
    }

    async _releaseWakeLock() {
      if (!this.wakeLock) return;
      try {
        await this.wakeLock.release();
      } catch (_e) {}
      this.wakeLock = null;
    }

    _teardownAudio() {
      if (this.flushTimer) clearInterval(this.flushTimer);
      if (this.tickTimer) clearInterval(this.tickTimer);
      this.flushTimer = null;
      this.tickTimer = null;

      if (this.processorNode) {
        try { this.processorNode.disconnect(); } catch (_e) {}
        this.processorNode.onaudioprocess = null;
        this.processorNode = null;
      }
      if (this.sourceNode) {
        try { this.sourceNode.disconnect(); } catch (_e) {}
        this.sourceNode = null;
      }
      if (this.mediaStream) {
        for (const t of this.mediaStream.getTracks()) t.stop();
        this.mediaStream = null;
      }
      if (this.audioCtx) {
        try { this.audioCtx.close(); } catch (_e) {}
        this.audioCtx = null;
      }
      this.currentLevel = 0;
      this._releaseWakeLock();
    }

    async _flushChunks() {
      if (!this.isRecording || !this.recId || this.uploadInFlight || this.pendingBytes === 0) return;
      this.uploadInFlight = true;
      const body = concatUint8(this.pendingChunks, this.pendingBytes);
      this.pendingChunks = [];
      this.pendingBytes = 0;
      const seq = ++this.chunkSeq;

      try {
        const resp = await fetch(`/api/rec/${encodeURIComponent(this.recId)}/chunk`, {
          method: "POST",
          headers: {
            "Content-Type": "application/octet-stream",
            "X-Chunk-Seq": String(seq),
          },
          body,
        });
        if (resp.ok) {
          const snap = await resp.json();
          if (this.isRecording && this.cb.onTranscript) {
            this.cb.onTranscript(snap.full_text || "");
          }
        } else {
          this.pendingChunks = [body, ...this.pendingChunks];
          this.pendingBytes += body.byteLength;
        }
      } catch (_err) {
        this.pendingChunks = [body, ...this.pendingChunks];
        this.pendingBytes += body.byteLength;
      } finally {
        this.uploadInFlight = false;
      }
    }

    async start() {
      if (this.isTransitioning || this.isRecording) return false;
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error("当前连接无法调用麦克风，请通过 HTTPS 地址打开页面并授权麦克风。");
      }
      this.isTransitioning = true;
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        });
        const resp = await fetch("/api/rec/start", { method: "POST" });
        const data = await resp.json();
        if (!resp.ok || !data.rec_id) {
          for (const t of stream.getTracks()) t.stop();
          throw new Error(data.error || "无法创建录音会话");
        }

        this.mediaStream = stream;
        this.recId = data.rec_id;
        this.chunkSeq = 0;
        this.pendingChunks = [];
        this.pendingBytes = 0;
        this.startedAtMs = Date.now();
        this.isRecording = true;

        const Ctor = window.AudioContext || window.webkitAudioContext;
        this.audioCtx = new Ctor();
        if (this.audioCtx.state === "suspended") await this.audioCtx.resume();
        const inRate = this.audioCtx.sampleRate;
        this.sourceNode = this.audioCtx.createMediaStreamSource(stream);
        this.processorNode = this.audioCtx.createScriptProcessor(4096, 1, 1);

        this.processorNode.onaudioprocess = (ev) => {
          if (!this.isRecording) return;
          const input = ev.inputBuffer.getChannelData(0);
          let sumSq = 0;
          for (let i = 0; i < input.length; i += 4) sumSq += input[i] * input[i];
          this.currentLevel = Math.sqrt(sumSq / Math.max(1, input.length / 4));

          const pcm16 = downsampleTo16kPcm(input, inRate);
          if (pcm16.length > 0) {
            const bytes = new Uint8Array(pcm16.buffer, pcm16.byteOffset, pcm16.byteLength);
            this.pendingChunks.push(bytes);
            this.pendingBytes += bytes.byteLength;
          }
        };

        const muteGain = this.audioCtx.createGain();
        muteGain.gain.value = 0;
        this.sourceNode.connect(this.processorNode);
        this.processorNode.connect(muteGain);
        muteGain.connect(this.audioCtx.destination);

        await this._acquireWakeLock();
        this.flushTimer = setInterval(() => this._flushChunks(), CHUNK_FLUSH_MS);
        this.tickTimer = setInterval(() => {
          if (this.isRecording && this.cb.onTick) {
            this.cb.onTick((Date.now() - this.startedAtMs) / 1000, this.currentLevel);
          }
        }, 90);
        return true;
      } catch (err) {
        this._teardownAudio();
        this.isRecording = false;
        this.recId = null;
        throw err;
      } finally {
        this.isTransitioning = false;
      }
    }

    canFinishNow() {
      return this.isRecording && !this.isTransitioning && (Date.now() - this.startedAtMs >= DOUBLE_TAP_GUARD_MS);
    }

    async finish() {
      if (!this.canFinishNow()) return null;
      this.isTransitioning = true;
      this.isRecording = false;
      const finishingId = this.recId;
      this.recId = null;
      this._teardownAudio();

      const waitStart = Date.now();
      while (this.uploadInFlight && Date.now() - waitStart < 2500) {
        await new Promise((r) => setTimeout(r, 50));
      }
      const tailBody = this.pendingBytes > 0 ? concatUint8(this.pendingChunks, this.pendingBytes) : new Uint8Array(0);
      this.pendingChunks = [];
      this.pendingBytes = 0;

      try {
        const resp = await fetch(`/api/rec/${encodeURIComponent(finishingId)}/finish`, {
          method: "POST",
          headers: { "Content-Type": "application/octet-stream" },
          body: tailBody,
        });
        const data = await resp.json();
        if (!resp.ok || !data.note) throw new Error(data.error || "保存故事失败");
        return data.note;
      } finally {
        this.isTransitioning = false;
      }
    }

    async cancel() {
      if (!this.isRecording || this.isTransitioning) return;
      this.isTransitioning = true;
      this.isRecording = false;
      const cid = this.recId;
      this.recId = null;
      this.pendingChunks = [];
      this.pendingBytes = 0;
      this._teardownAudio();
      try {
        if (cid) await fetch(`/api/rec/${encodeURIComponent(cid)}/cancel`, { method: "POST" });
      } catch (_e) {
      } finally {
        this.isTransitioning = false;
      }
    }
  }

  return { RecorderController };
})();
