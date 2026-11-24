#!/usr/bin/env python3
"""Rolling Doubao ASR transcriber supporting unlimited recording duration.

Ablation findings against Doubao Frontier IME WS:
- Silence pauses (15s, 45s+) do NOT close the session; prior utterances finalize cleanly.
- Single `StartSession` has an upstream ceiling around ~200s of audio.
- `RollingDoubaoTranscriber` decouples raw audio capture from ASR sessions: incoming 16kHz
  S16_LE PCM is queued in memory and streamed in 20ms frames; once a session passes
  `soft_rollover_sec` (default 120s) and hits a natural breath/silence (`>=0.5s`), or reaches
  `hard_rollover_sec` (default 165s), it finishes the current session, commits its text, and
  opens a fresh session without dropping a single PCM frame.
"""

from __future__ import annotations

import collections
import json
import socket
import struct
import threading
import time
import urllib.parse
import uuid
import wave
from typing import Any, Deque, Dict, List, Optional, Tuple

from doubao_frontier import (
    ANDROID_AID,
    ANDROID_FRONTIER_WS_BASE_URL,
    ASRError,
    FrontierWS,
    build_packet,
    build_session_config,
    parse_response,
    resolve_doubao_auth,
)

SAMPLE_RATE = 16000
SAMPLE_WIDTH = 2
PCM_FRAME_BYTES = 640  # 20ms @ 16kHz mono S16_LE
SILENCE_PEAK_THRESHOLD = 380  # ~1.1% full-scale S16_LE amplitude
_PUNCT_CHARS = set("。，！？,.!?；：")


def join_transcript(left: str, right: str) -> str:
    a, b = left.strip(), right.strip()
    if not a:
        return b
    if not b:
        return a
    if a[-1].isascii() and a[-1].isalnum() and b[0].isascii() and b[0].isalnum():
        return f"{a} {b}"
    return a + b


def _has_speech_chars(text: str) -> bool:
    return any(ch.isalnum() for ch in text)


def frame_is_silent(frame: bytes, threshold: int = SILENCE_PEAK_THRESHOLD) -> bool:
    if len(frame) < 2:
        return True
    count = len(frame) // 2
    samples = struct.unpack(f"<{count}h", frame[: count * 2])
    peak = 0
    for s in samples:
        a = -s if s < 0 else s
        if a > peak:
            peak = a
            if peak >= threshold:
                return False
    return True


class SingleUtteranceSession:
    """Manages one Doubao Frontier StartSession (<180s window)."""

    def __init__(self) -> None:
        self.session_id = str(uuid.uuid4()).upper()
        self.ws: Optional[FrontierWS] = None
        self.app_key = ""
        self._cond = threading.Condition()
        self._ready = False
        self._done = False
        self._finished_ok = False
        self._error = ""
        self._segments: Dict[int, str] = {}
        self._finals: Dict[int, str] = {}
        self._unindexed_par = ""
        self._unindexed_fin = ""
        self._reader: Optional[threading.Thread] = None

    def connect(self, timeout: float = 8.0) -> None:
        last_err: Optional[Exception] = None
        for attempt in range(2):
            try:
                did, app_key = resolve_doubao_auth(force_refresh=(attempt > 0))
                url = f"{ANDROID_FRONTIER_WS_BASE_URL}?aid={ANDROID_AID}&device_id={urllib.parse.quote(did, safe='')}"
                ws = FrontierWS(url)
                ws.connect(timeout=min(6.0, timeout))
                self.ws = ws
                self.app_key = app_key
                self.session_id = str(uuid.uuid4()).upper()
                with self._cond:
                    self._ready = False
                    self._done = False
                    self._finished_ok = False
                    self._error = ""
                self._reader = threading.Thread(target=self._reader_loop, args=(ws,), daemon=True)
                self._reader.start()

                ws.send_bin(build_packet(app_key, "StartTask", "", b"", self.session_id))
                ws.send_bin(build_packet(app_key, "StartSession", build_session_config(SAMPLE_RATE), b"", self.session_id))

                deadline = time.monotonic() + timeout
                with self._cond:
                    while not self._ready and not self._done and not self._error:
                        rem = deadline - time.monotonic()
                        if rem <= 0:
                            raise ASRError("豆包语音会话启动超时")
                        self._cond.wait(timeout=rem)
                    if self._error or not self._ready:
                        raise ASRError(self._error or "豆包语音会话启动失败")
                return
            except Exception as exc:
                last_err = exc
                self.close()
                if attempt == 0:
                    time.sleep(0.25)
        raise ASRError(f"豆包连接失败: {last_err}")

    def _reader_loop(self, ws: FrontierWS) -> None:
        while True:
            try:
                op, data = ws.recv_frame()
            except socket.timeout:
                with self._cond:
                    if self._done:
                        return
                continue
            except Exception as exc:
                with self._cond:
                    if not self._done and not self._finished_ok and not self._error:
                        self._error = f"连接中断: {exc}"
                    self._done = True
                    self._cond.notify_all()
                return

            if op == 0x8:
                with self._cond:
                    if not self._finished_ok and not self._error:
                        self._error = "上游连接关闭"
                    self._done = True
                    self._cond.notify_all()
                return
            if op != 0x2:
                continue

            parsed = parse_response(data)
            if not parsed:
                continue
            ev, code, pjson = parsed
            with self._cond:
                if code != 20_000_000:
                    self._error = f"豆包识别错误 ({code})"
                    self._done = True
                    self._cond.notify_all()
                    return
                if ev == "SessionStarted":
                    self._ready = True
                elif ev in ("SessionFinished", "TaskFinished"):
                    self._finished_ok = True
                    self._done = True

                if isinstance(pjson, dict) and isinstance(pjson.get("results"), list):
                    for item in pjson["results"]:
                        if not isinstance(item, dict):
                            continue
                        raw_txt = item.get("text")
                        txt = raw_txt.strip() if isinstance(raw_txt, str) else ""
                        if not txt or not _has_speech_chars(txt):
                            continue
                        extra = item.get("extra") if isinstance(item.get("extra"), dict) else {}
                        is_term = bool(extra.get("nonstream_result")) or (
                            item.get("is_interim") is False and bool(item.get("is_vad_finished"))
                        )
                        idx = (
                            item.get("index")
                            if isinstance(item.get("index"), int) and not isinstance(item.get("index"), bool)
                            else None
                        )
                        if idx is None:
                            self._unindexed_par = txt
                            if is_term and sum(1 for c in txt if c in _PUNCT_CHARS) >= sum(
                                1 for c in self._unindexed_fin if c in _PUNCT_CHARS
                            ):
                                self._unindexed_fin = txt
                        else:
                            self._segments[idx] = txt
                            if is_term:
                                self._finals[idx] = txt

                self._cond.notify_all()
                if self._done:
                    return

    def send_frame(self, frame: bytes) -> bool:
        with self._cond:
            if self._done or self.ws is None:
                return False
            ws = self.ws
        meta = json.dumps({"timestamp_ms": int(time.time() * 1000)}, separators=(",", ":"))
        try:
            ws.send_bin(build_packet("", "TaskRequest", meta, frame, self.session_id))
            return True
        except Exception as exc:
            with self._cond:
                if not self._finished_ok and not self._error:
                    self._error = str(exc)
                self._done = True
                self._cond.notify_all()
            return False

    def compose_text(self, prefer_final: bool = False) -> str:
        with self._cond:
            return self._compose_unlocked(prefer_final)

    def _compose_unlocked(self, prefer_final: bool) -> str:
        joined = ""
        for k in sorted(set(self._segments) | set(self._finals)):
            seg = (
                (self._finals.get(k) or self._segments.get(k) or "")
                if prefer_final
                else (self._segments.get(k) or self._finals.get(k) or "")
            )
            if _has_speech_chars(seg):
                joined = join_transcript(joined, seg)
        tail = (
            (self._unindexed_fin or self._unindexed_par)
            if prefer_final
            else (self._unindexed_par or self._unindexed_fin)
        )
        if _has_speech_chars(tail):
            joined = join_transcript(joined, tail)
        return joined

    @property
    def is_done(self) -> bool:
        with self._cond:
            return self._done

    @property
    def error(self) -> str:
        with self._cond:
            return "" if self._finished_ok else self._error

    def finish(self, timeout: float = 5.0) -> str:
        ws = self.ws
        if ws is not None:
            with self._cond:
                should_send = not self._done
            if should_send:
                try:
                    ws.send_bin(build_packet(self.app_key, "FinishSession", "", b"", self.session_id))
                except Exception:
                    pass
            deadline = time.monotonic() + timeout
            with self._cond:
                while not self._done:
                    rem = deadline - time.monotonic()
                    if rem <= 0:
                        break
                    self._cond.wait(timeout=rem)
        txt = self.compose_text(prefer_final=True) or self.compose_text(prefer_final=False)
        self.close()
        return txt.strip()

    def close(self) -> None:
        with self._cond:
            self._done = True
            self._cond.notify_all()
        if self.ws is not None:
            self.ws.close()
            self.ws = None
        if self._reader is not None and self._reader is not threading.current_thread():
            self._reader.join(timeout=0.8)
        self._reader = None


class RollingDoubaoTranscriber:
    """Streams arbitrary-length PCM through chained Doubao Frontier sessions without dropping frames."""

    def __init__(
        self,
        soft_rollover_sec: float = 120.0,
        hard_rollover_sec: float = 165.0,
        min_silence_for_roll_sec: float = 0.45,
        frame_pace_sec: float = 0.0,
    ) -> None:
        self.soft_rollover_sec = soft_rollover_sec
        self.hard_rollover_sec = hard_rollover_sec
        self.min_silence_for_roll_sec = min_silence_for_roll_sec
        self.frame_pace_sec = frame_pace_sec

        self._cond = threading.Condition()
        self._pcm_buf = bytearray()
        self._frames: Deque[bytes] = collections.deque()
        self._finishing = False
        self._cancelled = False
        self._worker_done = False

        self._committed_parts: List[str] = []
        self._active_session: Optional[SingleUtteranceSession] = None
        self._rollovers = 0
        self._last_error = ""
        self._total_pcm_bytes = 0

        self._worker = threading.Thread(target=self._run_loop, name="rolling-doubao-asr", daemon=True)
        self._worker.start()

    def feed_pcm(self, pcm_bytes: bytes) -> None:
        if not pcm_bytes:
            return
        with self._cond:
            if self._finishing or self._cancelled:
                return
            self._total_pcm_bytes += len(pcm_bytes)
            self._pcm_buf.extend(pcm_bytes)
            while len(self._pcm_buf) >= PCM_FRAME_BYTES:
                frame = bytes(self._pcm_buf[:PCM_FRAME_BYTES])
                del self._pcm_buf[:PCM_FRAME_BYTES]
                self._frames.append(frame)
            self._cond.notify_all()

    def snapshot(self) -> Dict[str, Any]:
        with self._cond:
            committed = ""
            for p in self._committed_parts:
                committed = join_transcript(committed, p)
            active = self._active_session
            rollovers = self._rollovers
            err = self._last_error
            dur = self._total_pcm_bytes / float(SAMPLE_RATE * SAMPLE_WIDTH)
        live = active.compose_text(prefer_final=False) if active is not None else ""
        full = join_transcript(committed, live)
        return {
            "committed": committed,
            "partial": live,
            "full_text": full,
            "duration_sec": round(dur, 2),
            "rollovers": rollovers,
            "asr_error": err,
        }

    def finish(self, timeout: float = 15.0) -> Tuple[str, int, str]:
        with self._cond:
            if not self._finishing:
                if self._pcm_buf:
                    if len(self._pcm_buf) % 2 == 1:
                        self._pcm_buf.append(0)
                    if len(self._pcm_buf) < PCM_FRAME_BYTES:
                        self._pcm_buf.extend(b"\x00" * (PCM_FRAME_BYTES - len(self._pcm_buf)))
                    self._frames.append(bytes(self._pcm_buf))
                    self._pcm_buf.clear()
                self._finishing = True
                self._cond.notify_all()

        self._worker.join(timeout=timeout)
        with self._cond:
            committed = ""
            for p in self._committed_parts:
                committed = join_transcript(committed, p)
            active = self._active_session
            rollovers = self._rollovers
            err = self._last_error
        if active is not None:
            tail = active.compose_text(prefer_final=True) or active.compose_text(prefer_final=False)
            committed = join_transcript(committed, tail)
            active.close()
        return (committed.strip(), rollovers, err)

    def cancel(self) -> None:
        with self._cond:
            self._cancelled = True
            self._frames.clear()
            self._pcm_buf.clear()
            active = self._active_session
            self._cond.notify_all()
        if active is not None:
            active.close()
        self._worker.join(timeout=2.0)

    def _commit_session(self, sess: SingleUtteranceSession, count_rollover: bool) -> None:
        txt = sess.finish(timeout=4.5)
        with self._cond:
            if txt:
                self._committed_parts.append(txt)
            if count_rollover:
                self._rollovers += 1
            if self._active_session is sess:
                self._active_session = None

    def _run_loop(self) -> None:
        sess: Optional[SingleUtteranceSession] = None
        sess_audio_sec = 0.0
        silence_sec = 0.0
        consecutive_connect_failures = 0

        try:
            while True:
                with self._cond:
                    while not self._frames and not self._finishing and not self._cancelled:
                        self._cond.wait(timeout=0.5)
                    if self._cancelled:
                        break
                    if not self._frames and self._finishing:
                        break
                    frame = self._frames.popleft()

                if sess is None:
                    candidate = SingleUtteranceSession()
                    try:
                        candidate.connect(timeout=7.0)
                        sess = candidate
                        sess_audio_sec = 0.0
                        silence_sec = 0.0
                        consecutive_connect_failures = 0
                        with self._cond:
                            self._active_session = sess
                            self._last_error = ""
                    except Exception as exc:
                        consecutive_connect_failures += 1
                        with self._cond:
                            self._last_error = str(exc)
                            # Put frame back so short network blips don't drop audio
                            self._frames.appendleft(frame)
                        backoff = min(2.0, 0.3 * consecutive_connect_failures)
                        if consecutive_connect_failures >= 6:
                            # Drain frames if offline for long so memory stays bounded; WAV on disk still keeps full audio
                            with self._cond:
                                while len(self._frames) > 250:  # keep last 5s
                                    self._frames.popleft()
                        time.sleep(backoff)
                        continue

                is_silent = frame_is_silent(frame)
                ok = sess.send_frame(frame)
                if not ok or sess.is_done:
                    # Upstream ended or dropped; commit any recognized text and re-queue current frame
                    err_msg = sess.error
                    self._commit_session(sess, count_rollover=True)
                    sess = None
                    with self._cond:
                        if err_msg:
                            self._last_error = err_msg
                        self._frames.appendleft(frame)
                    continue

                frame_dur = len(frame) / float(SAMPLE_RATE * SAMPLE_WIDTH)
                sess_audio_sec += frame_dur
                silence_sec = (silence_sec + frame_dur) if is_silent else 0.0

                should_roll = (
                    (sess_audio_sec >= self.soft_rollover_sec and silence_sec >= self.min_silence_for_roll_sec)
                    or (sess_audio_sec >= self.hard_rollover_sec)
                )
                if should_roll:
                    self._commit_session(sess, count_rollover=True)
                    sess = None
                    sess_audio_sec = 0.0
                    silence_sec = 0.0

                if self.frame_pace_sec > 0:
                    time.sleep(self.frame_pace_sec)

            if sess is not None:
                self._commit_session(sess, count_rollover=False)
                sess = None
        finally:
            if sess is not None:
                sess.close()
            with self._cond:
                self._active_session = None
                self._worker_done = True
                self._cond.notify_all()


def transcribe_wav_file(
    wav_path: str,
    soft_rollover_sec: float = 110.0,
    hard_rollover_sec: float = 155.0,
) -> Tuple[str, int]:
    """Re-transcribe a saved 16kHz mono S16_LE WAV file via paced rolling Doubao sessions."""
    with wave.open(wav_path, "rb") as wf:
        if wf.getnchannels() != 1 or wf.getsampwidth() != 2 or wf.getframerate() != SAMPLE_RATE:
            raise ASRError(
                f"仅支持 16kHz 单声道 16-bit WAV，当前为 {wf.getframerate()}Hz/{wf.getnchannels()}ch/{wf.getsampwidth()*8}bit"
            )
        raw_pcm = wf.readframes(wf.getnframes())

    if not raw_pcm:
        return ("", 0)

    # Pace at ~10x realtime (2ms per 20ms frame) so VAD & threepass behave identically to live speech
    engine = RollingDoubaoTranscriber(
        soft_rollover_sec=soft_rollover_sec,
        hard_rollover_sec=hard_rollover_sec,
        min_silence_for_roll_sec=0.4,
        frame_pace_sec=0.0018,
    )
    engine.feed_pcm(raw_pcm)
    max_wait = max(15.0, (len(raw_pcm) / float(SAMPLE_RATE * SAMPLE_WIDTH)) * 0.35 + 15.0)
    text, rollovers, err = engine.finish(timeout=max_wait)
    if err and not text:
        raise ASRError(err)
    return (text, rollovers)
