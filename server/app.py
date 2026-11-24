#!/usr/bin/env python3
"""HTTP server for Story Note (hands-free continuous recording + Doubao ASR + Notes)."""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import re
import signal
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, Optional, Tuple
from urllib.parse import urlparse

SERVER_DIR = Path(__file__).resolve().parent
ROOT_DIR = SERVER_DIR.parent
WEB_DIR = ROOT_DIR / "web"
DEFAULT_DATA_DIR = ROOT_DIR / "data"

sys.path.insert(0, str(SERVER_DIR))
from doubao_frontier import ASRError, resolve_doubao_auth  # noqa: E402
from note_store import STORY_STICKERS, NoteStore, WavStreamWriter  # noqa: E402
from rolling_asr import RollingDoubaoTranscriber, transcribe_wav_file  # noqa: E402

MAX_JSON_BODY = 512 * 1024
MAX_PCM_CHUNK_BODY = 2 * 1024 * 1024  # ~64s PCM per single HTTP chunk
ACTIVE_SESSION_IDLE_SEC = 600.0  # 10 min idle safety cleanup


class ActiveRecording:
    """Pairs continuous on-disk WAV capture with a RollingDoubaoTranscriber."""

    def __init__(
        self,
        rec_id: str,
        wav_writer: WavStreamWriter,
        soft_rollover_sec: float = 120.0,
        hard_rollover_sec: float = 165.0,
    ) -> None:
        self.rec_id = rec_id
        self.wav_writer = wav_writer
        self.transcriber = RollingDoubaoTranscriber(
            soft_rollover_sec=soft_rollover_sec,
            hard_rollover_sec=hard_rollover_sec,
        )
        self.last_seq = -1
        self.last_active = time.monotonic()
        self.lock = threading.Lock()


class AppContext:
    def __init__(
        self,
        data_dir: Path,
        soft_rollover_sec: float = 120.0,
        hard_rollover_sec: float = 165.0,
    ) -> None:
        self.store = NoteStore(data_dir)
        self.soft_rollover_sec = soft_rollover_sec
        self.hard_rollover_sec = hard_rollover_sec
        self._lock = threading.Lock()
        self._recordings: Dict[str, ActiveRecording] = {}

    def reap_stale_recordings(self) -> None:
        now = time.monotonic()
        stale: list[ActiveRecording] = []
        with self._lock:
            for rid, rec in list(self._recordings.items()):
                if (now - rec.last_active) > ACTIVE_SESSION_IDLE_SEC:
                    stale.append(self._recordings.pop(rid))
        for rec in stale:
            try:
                dur = rec.wav_writer.close()
                txt, rolls, err = rec.transcriber.finish(timeout=4.0)
                if dur >= 1.0:
                    self.store.finalize_note(rec.rec_id, dur, txt, rolls, err)
                else:
                    self.store.discard_staging(rec.rec_id)
            except Exception:
                self.store.discard_staging(rec.rec_id)

    def start_recording(self) -> str:
        self.reap_stale_recordings()
        rec_id = self.store.new_id()
        wav_writer = self.store.create_staging_wav(rec_id)
        rec = ActiveRecording(
            rec_id=rec_id,
            wav_writer=wav_writer,
            soft_rollover_sec=self.soft_rollover_sec,
            hard_rollover_sec=self.hard_rollover_sec,
        )
        with self._lock:
            self._recordings[rec_id] = rec
        return rec_id

    def get_recording(self, rec_id: str) -> Optional[ActiveRecording]:
        with self._lock:
            return self._recordings.get(rec_id)

    def pop_recording(self, rec_id: str) -> Optional[ActiveRecording]:
        with self._lock:
            return self._recordings.pop(rec_id, None)


_RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")


class StoryNoteHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    ctx: AppContext  # attached on server class

    def log_message(self, fmt: str, *args: Any) -> None:
        if os.environ.get("STORY_NOTE_QUIET") == "1":
            return
        sys.stderr.write(f"[story-note] {self.address_string()} - {fmt % args}\n")

    def _send_json(self, status: int, payload: Dict[str, Any]) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _read_exact_body(self, max_bytes: int) -> Optional[bytes]:
        try:
            length = int(self.headers.get("Content-Length") or "0")
        except ValueError:
            self._send_json(400, {"error": "Invalid Content-Length"})
            return None
        if length < 0 or length > max_bytes:
            self._send_json(413, {"error": f"Payload exceeds {max_bytes} bytes"})
            return None
        return self.rfile.read(length) if length > 0 else b""

    def _read_json_body(self) -> Optional[Dict[str, Any]]:
        raw = self._read_exact_body(MAX_JSON_BODY)
        if raw is None:
            return None
        if not raw:
            return {}
        try:
            obj = json.loads(raw.decode("utf-8"))
            if not isinstance(obj, dict):
                self._send_json(400, {"error": "Expected JSON object"})
                return None
            return obj
        except (UnicodeDecodeError, ValueError):
            self._send_json(400, {"error": "Malformed JSON"})
            return None

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/api/health":
            self._send_json(200, {"ok": True, "stickers": STORY_STICKERS})
            return
        if path == "/api/notes":
            notes = self.ctx.store.list_notes()
            self._send_json(200, {"notes": notes})
            return

        m_audio = re.match(r"^/api/notes/([^/]+)/audio\.wav$", path)
        if m_audio:
            self._serve_wav_with_range(m_audio.group(1))
            return

        self._serve_static(path)

    def do_POST(self) -> None:
        path = urlparse(self.path).path

        if path == "/api/rec/start":
            # Drain optional empty body if client sent Content-Length: 0/2
            if self._read_exact_body(MAX_JSON_BODY) is None:
                return
            rec_id = self.ctx.start_recording()
            self._send_json(200, {"ok": True, "rec_id": rec_id})
            return

        m_chunk = re.match(r"^/api/rec/([^/]+)/chunk$", path)
        if m_chunk:
            self._handle_rec_chunk(m_chunk.group(1))
            return

        m_fin = re.match(r"^/api/rec/([^/]+)/finish$", path)
        if m_fin:
            self._handle_rec_finish(m_fin.group(1))
            return

        m_cancel = re.match(r"^/api/rec/([^/]+)/cancel$", path)
        if m_cancel:
            if self._read_exact_body(MAX_JSON_BODY) is None:
                return
            rec = self.ctx.pop_recording(m_cancel.group(1))
            if rec is not None:
                rec.wav_writer.close()
                rec.transcriber.cancel()
                self.ctx.store.discard_staging(rec.rec_id)
            self._send_json(200, {"ok": True})
            return

        m_re = re.match(r"^/api/notes/([^/]+)/retranscribe$", path)
        if m_re:
            if self._read_exact_body(MAX_JSON_BODY) is None:
                return
            self._handle_retranscribe(m_re.group(1))
            return

        self._send_json(404, {"error": "Not found"})

    def do_PATCH(self) -> None:
        path = urlparse(self.path).path
        m_note = re.match(r"^/api/notes/([^/]+)$", path)
        if not m_note:
            self._send_json(404, {"error": "Not found"})
            return
        body = self._read_json_body()
        if body is None:
            return
        updated = self.ctx.store.update_note(
            m_note.group(1),
            text=body.get("text") if isinstance(body.get("text"), str) else None,
            title=body.get("title") if isinstance(body.get("title"), str) else None,
            sticker=body.get("sticker") if isinstance(body.get("sticker"), str) else None,
        )
        if updated is None:
            self._send_json(404, {"error": "Note not found"})
            return
        self._send_json(200, {"ok": True, "note": updated})

    def do_DELETE(self) -> None:
        path = urlparse(self.path).path
        m_note = re.match(r"^/api/notes/([^/]+)$", path)
        if not m_note:
            self._send_json(404, {"error": "Not found"})
            return
        ok = self.ctx.store.delete_note(m_note.group(1))
        if not ok:
            self._send_json(404, {"error": "Note not found"})
            return
        self._send_json(200, {"ok": True})

    def _handle_rec_chunk(self, rec_id: str) -> None:
        pcm = self._read_exact_body(MAX_PCM_CHUNK_BODY)
        if pcm is None:
            return
        rec = self.ctx.get_recording(rec_id)
        if rec is None:
            self._send_json(404, {"error": "Recording session not found"})
            return
        seq_hdr = self.headers.get("X-Chunk-Seq")
        seq: Optional[int] = None
        if seq_hdr is not None:
            try:
                seq = int(seq_hdr)
            except ValueError:
                self._send_json(400, {"error": "Invalid X-Chunk-Seq"})
                return

        with rec.lock:
            rec.last_active = time.monotonic()
            if seq is None or seq > rec.last_seq:
                if seq is not None:
                    rec.last_seq = seq
                if pcm:
                    rec.wav_writer.append_pcm(pcm)
                    rec.transcriber.feed_pcm(pcm)
            snap = rec.transcriber.snapshot()
            snap["ok"] = True
            snap["audio_duration_sec"] = rec.wav_writer.duration_sec
        self._send_json(200, snap)

    def _handle_rec_finish(self, rec_id: str) -> None:
        tail_pcm = self._read_exact_body(MAX_PCM_CHUNK_BODY)
        if tail_pcm is None:
            return
        rec = self.ctx.pop_recording(rec_id)
        if rec is None:
            existing = self.ctx.store.get_note(rec_id)
            if existing is not None:
                self._send_json(200, {"ok": True, "note": existing})
                return
            self._send_json(404, {"error": "Recording session not found"})
            return

        with rec.lock:
            if tail_pcm:
                rec.wav_writer.append_pcm(tail_pcm)
                rec.transcriber.feed_pcm(tail_pcm)
            duration_sec = rec.wav_writer.close()

        text, rollovers, asr_err = rec.transcriber.finish(timeout=12.0)
        if duration_sec < 0.25 and not text:
            self.ctx.store.discard_staging(rec_id)
            self._send_json(400, {"error": "录音太短啦，再讲长一点点吧"})
            return

        note = self.ctx.store.finalize_note(
            rec_id=rec_id,
            duration_sec=duration_sec,
            text=text,
            rollovers=rollovers,
            asr_error=asr_err,
        )
        self._send_json(200, {"ok": True, "note": note})

    def _handle_retranscribe(self, note_id: str) -> None:
        wav_path = self.ctx.store.get_audio_path(note_id)
        if wav_path is None:
            self._send_json(404, {"error": "Note audio not found"})
            return
        try:
            text, rollovers = transcribe_wav_file(
                str(wav_path),
                soft_rollover_sec=self.ctx.soft_rollover_sec,
                hard_rollover_sec=self.ctx.hard_rollover_sec,
            )
            updated = self.ctx.store.update_note(
                note_id,
                text=text,
                rollovers=rollovers,
                asr_error="",
            )
            self._send_json(200, {"ok": True, "note": updated})
        except ASRError as exc:
            self._send_json(502, {"error": str(exc)})

    def _serve_wav_with_range(self, note_id: str) -> None:
        wav_path = self.ctx.store.get_audio_path(note_id)
        if wav_path is None:
            self._send_json(404, {"error": "Audio not found"})
            return
        file_size = wav_path.stat().st_size
        range_hdr = self.headers.get("Range")
        start, end = 0, file_size - 1
        status = HTTPStatus.OK

        if range_hdr:
            m = _RANGE_RE.match(range_hdr.strip())
            if m and file_size > 0:
                g1, g2 = m.group(1), m.group(2)
                if g1 == "" and g2 != "":
                    suffix = int(g2)
                    start = max(0, file_size - suffix)
                elif g1 != "":
                    start = int(g1)
                    end = int(g2) if g2 != "" else (file_size - 1)
                if start >= file_size or start > end:
                    self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    self.send_header("Content-Range", f"bytes */{file_size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                end = min(end, file_size - 1)
                status = HTTPStatus.PARTIAL_CONTENT

        chunk_len = (end - start + 1) if file_size > 0 else 0
        self.send_response(status)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Accept-Ranges", "bytes")
        if status == HTTPStatus.PARTIAL_CONTENT:
            self.send_header("Content-Range", f"bytes {start}-{end}/{file_size}")
        self.send_header("Content-Length", str(chunk_len))
        self.send_header("Cache-Control", "public, max-age=3600")
        self.end_headers()

        if chunk_len > 0:
            with open(wav_path, "rb") as fh:
                fh.seek(start)
                remaining = chunk_len
                while remaining > 0:
                    buf = fh.read(min(65536, remaining))
                    if not buf:
                        break
                    self.wfile.write(buf)
                    remaining -= len(buf)

    def _serve_static(self, url_path: str) -> None:
        rel = "index.html" if url_path in ("", "/") else url_path.lstrip("/")
        target = (WEB_DIR / rel).resolve()
        if not str(target).startswith(str(WEB_DIR.resolve())) or not target.is_file():
            self._send_json(404, {"error": "Not found"})
            return
        ctype, _ = mimetypes.guess_type(str(target))
        if target.suffix == ".webmanifest" or target.name == "manifest.json":
            ctype = "application/manifest+json; charset=utf-8"
        elif ctype and ctype.startswith("text/"):
            ctype = f"{ctype}; charset=utf-8"
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", ctype or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)


def build_server(
    host: str,
    port: int,
    data_dir: Path,
    soft_rollover_sec: float = 120.0,
    hard_rollover_sec: float = 165.0,
) -> Tuple[ThreadingHTTPServer, AppContext]:
    ctx = AppContext(
        data_dir=data_dir,
        soft_rollover_sec=soft_rollover_sec,
        hard_rollover_sec=hard_rollover_sec,
    )

    class BoundHandler(StoryNoteHandler):
        pass

    BoundHandler.ctx = ctx
    httpd = ThreadingHTTPServer((host, port), BoundHandler)
    httpd.daemon_threads = True
    return httpd, ctx


def main() -> int:
    parser = argparse.ArgumentParser(description="Story Note server")
    parser.add_argument("--host", default="127.0.0.1", help="Bind host (default 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8788, help="Bind port (default 8788)")
    parser.add_argument("--data-dir", default=str(DEFAULT_DATA_DIR), help="Data directory for notes & audio")
    args = parser.parse_args()

    # Warm Doubao auth asynchronously on startup so first tap is instant
    threading.Thread(target=lambda: resolve_doubao_auth(False), daemon=True).start()

    httpd, _ctx = build_server(args.host, args.port, Path(args.data_dir))
    stop_once = threading.Event()

    def _shutdown(_signum: int, _frame: Any) -> None:
        if not stop_once.is_set():
            stop_once.set()
            threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    addr = httpd.server_address
    print(f"Story Note listening on http://{addr[0]}:{addr[1]} (data={args.data_dir})", flush=True)
    httpd.serve_forever()
    httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
