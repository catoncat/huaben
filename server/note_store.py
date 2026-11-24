#!/usr/bin/env python3
"""Persistent Note & continuous WAV audio storage for Story Note.

Each note is persisted in its own human-readable folder under `data/notes/<note_id>/`:
- `audio.wav`: continuous 16kHz 16-bit mono PCM audio (playable in Finder, Chrome, Android)
- `note.md`: YAML frontmatter + transcribed story text (directly usable in Obsidian/Markdown)
- `meta.json`: structured metadata (story number, sticker icon, duration, timestamps, rollovers)
"""

from __future__ import annotations

import datetime as dt
import json
import os
import re
import shutil
import struct
import tempfile
import threading
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

SAMPLE_RATE = 16000
CHANNELS = 1
SAMPLE_WIDTH = 2  # 16-bit

STORY_STICKERS = ["🦖", "🚀", "🏰", "🐳", "🦊", "🌈", "🦄", "🐙", "🐉", "🎈", "🦁", "🍄"]
_SAFE_ID_RE = re.compile(r"^[0-9]{8}-[0-9]{6}-[a-f0-9]{6}$")


def now_iso() -> str:
    return dt.datetime.now().astimezone().isoformat(timespec="seconds")


class WavStreamWriter:
    """Appends raw S16_LE PCM bytes to disk while keeping RIFF/WAVE sizes valid at every flush."""

    def __init__(self, path: Path, sample_rate: int = SAMPLE_RATE) -> None:
        self.path = path
        self.sample_rate = sample_rate
        self.data_bytes = 0
        self._lock = threading.Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = open(self.path, "w+b")
        self._write_header_unlocked(0)
        self._fh.flush()

    def _build_header(self, data_size: int) -> bytes:
        byte_rate = self.sample_rate * CHANNELS * SAMPLE_WIDTH
        block_align = CHANNELS * SAMPLE_WIDTH
        riff_size = min(0xFFFFFFFF, 36 + data_size)
        clamped_data = min(0xFFFFFFFF, data_size)
        return struct.pack(
            "<4sI4s4sIHHIIHH4sI",
            b"RIFF",
            riff_size,
            b"WAVE",
            b"fmt ",
            16,
            1,  # PCM format
            CHANNELS,
            self.sample_rate,
            byte_rate,
            block_align,
            SAMPLE_WIDTH * 8,
            b"data",
            clamped_data,
        )

    def _write_header_unlocked(self, data_size: int) -> None:
        self._fh.seek(0)
        self._fh.write(self._build_header(data_size))

    def append_pcm(self, pcm: bytes) -> int:
        if not pcm:
            return self.data_bytes
        if len(pcm) % 2 == 1:
            pcm = pcm + b"\x00"
        with self._lock:
            if self._fh.closed:
                return self.data_bytes
            self._fh.seek(44 + self.data_bytes)
            self._fh.write(pcm)
            self.data_bytes += len(pcm)
            self._write_header_unlocked(self.data_bytes)
            self._fh.flush()
            return self.data_bytes

    @property
    def duration_sec(self) -> float:
        return round(self.data_bytes / float(self.sample_rate * CHANNELS * SAMPLE_WIDTH), 2)

    def close(self) -> float:
        with self._lock:
            if not self._fh.closed:
                self._write_header_unlocked(self.data_bytes)
                self._fh.flush()
                os.fsync(self._fh.fileno())
                self._fh.close()
            return self.duration_sec


def _atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=f".{path.name}.")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fd = -1
            fh.write(content)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    finally:
        if fd >= 0:
            os.close(fd)
        if os.path.exists(tmp):
            try:
                os.unlink(tmp)
            except OSError:
                pass


def render_markdown(meta: Dict[str, Any]) -> str:
    title = str(meta.get("title") or f"第 {meta.get('story_number', 1)} 篇故事").replace("\n", " ").strip()
    sticker = str(meta.get("sticker") or "🦖")
    created_at = str(meta.get("created_at") or "")
    duration_sec = meta.get("duration_sec", 0)
    note_id = str(meta.get("id") or "")
    text = str(meta.get("text") or "").strip()
    body = text if text else "_（暂无识别文字，可点击「重新识别」生成）_"
    return (
        "---\n"
        f"id: {note_id}\n"
        f"story_number: {meta.get('story_number', 1)}\n"
        f"sticker: \"{sticker}\"\n"
        f"title: {json.dumps(title, ensure_ascii=False)}\n"
        f"created_at: {created_at}\n"
        f"duration_sec: {duration_sec}\n"
        "audio_file: audio.wav\n"
        "---\n\n"
        f"# {sticker} {title}\n\n"
        f"{body}\n"
    )


class NoteStore:
    """Thread-safe filesystem store for story voice notes."""

    def __init__(self, root_dir: Path) -> None:
        self.root_dir = root_dir
        self.notes_dir = self.root_dir / "notes"
        self.staging_dir = self.root_dir / "staging"
        self.notes_dir.mkdir(parents=True, exist_ok=True)
        self.staging_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()

    def is_valid_id(self, note_id: str) -> bool:
        return bool(_SAFE_ID_RE.match(note_id or ""))

    def new_id(self) -> str:
        return f"{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:6]}"

    def _next_story_number_unlocked(self) -> int:
        max_num = 0
        for d in self.notes_dir.iterdir():
            if not d.is_dir():
                continue
            meta_path = d / "meta.json"
            if not meta_path.is_file():
                continue
            try:
                data = json.loads(meta_path.read_text(encoding="utf-8"))
                num = int(data.get("story_number") or 0)
                if num > max_num:
                    max_num = num
            except (OSError, ValueError, TypeError):
                continue
        return max_num + 1

    def create_staging_wav(self, rec_id: str) -> WavStreamWriter:
        wav_path = self.staging_dir / rec_id / "audio.wav"
        return WavStreamWriter(wav_path)

    def discard_staging(self, rec_id: str) -> None:
        if not self.is_valid_id(rec_id):
            return
        shutil.rmtree(self.staging_dir / rec_id, ignore_errors=True)

    def finalize_note(
        self,
        rec_id: str,
        duration_sec: float,
        text: str,
        rollovers: int = 0,
        asr_error: str = "",
    ) -> Dict[str, Any]:
        if not self.is_valid_id(rec_id):
            raise ValueError("Invalid recording ID")
        staging_folder = self.staging_dir / rec_id
        staging_wav = staging_folder / "audio.wav"
        if not staging_wav.is_file():
            raise FileNotFoundError("Staging audio missing")

        with self._lock:
            num = self._next_story_number_unlocked()
            sticker = STORY_STICKERS[(num - 1) % len(STORY_STICKERS)]
            clean_text = text.strip()
            auto_title = self._derive_title(clean_text, num)
            created = now_iso()

            note_folder = self.notes_dir / rec_id
            note_folder.mkdir(parents=True, exist_ok=True)
            target_wav = note_folder / "audio.wav"
            os.replace(staging_wav, target_wav)
            shutil.rmtree(staging_folder, ignore_errors=True)

            meta: Dict[str, Any] = {
                "id": rec_id,
                "story_number": num,
                "sticker": sticker,
                "title": auto_title,
                "text": clean_text,
                "duration_sec": round(duration_sec, 2),
                "audio_bytes": target_wav.stat().st_size,
                "rollovers": rollovers,
                "asr_error": asr_error if not clean_text else "",
                "created_at": created,
                "updated_at": created,
                "audio_url": f"/api/notes/{rec_id}/audio.wav",
                "note_md_path": str((note_folder / "note.md").resolve()),
            }
            self._save_meta_and_md_unlocked(note_folder, meta)
            return meta

    @staticmethod
    def _derive_title(text: str, story_number: int) -> str:
        if not text:
            return f"第 {story_number} 篇故事"
        first_clause = re.split(r"[。！？!?\n]", text, maxsplit=1)[0].strip(" ，,、：:；;")
        if not first_clause:
            return f"第 {story_number} 篇故事"
        if len(first_clause) > 16:
            return first_clause[:16] + "…"
        return first_clause

    def _save_meta_and_md_unlocked(self, folder: Path, meta: Dict[str, Any]) -> None:
        meta["audio_url"] = f"/api/notes/{meta['id']}/audio.wav"
        meta["note_md_path"] = str((folder / "note.md").resolve())
        _atomic_write_text(folder / "meta.json", json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
        _atomic_write_text(folder / "note.md", render_markdown(meta))

    def list_notes(self) -> List[Dict[str, Any]]:
        items: List[Dict[str, Any]] = []
        with self._lock:
            for d in self.notes_dir.iterdir():
                if not d.is_dir() or not self.is_valid_id(d.name):
                    continue
                meta = self._read_note_unlocked(d.name)
                if meta is not None:
                    items.append(meta)
        items.sort(key=lambda m: (m.get("created_at") or "", m.get("story_number") or 0), reverse=True)
        return items

    def get_note(self, note_id: str) -> Optional[Dict[str, Any]]:
        if not self.is_valid_id(note_id):
            return None
        with self._lock:
            return self._read_note_unlocked(note_id)

    def _read_note_unlocked(self, note_id: str) -> Optional[Dict[str, Any]]:
        folder = self.notes_dir / note_id
        meta_path = folder / "meta.json"
        wav_path = folder / "audio.wav"
        if not meta_path.is_file() or not wav_path.is_file():
            return None
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            if not isinstance(meta, dict):
                return None
            meta["id"] = note_id
            meta["audio_url"] = f"/api/notes/{note_id}/audio.wav"
            meta["note_md_path"] = str((folder / "note.md").resolve())
            return meta
        except (OSError, ValueError):
            return None

    def get_audio_path(self, note_id: str) -> Optional[Path]:
        if not self.is_valid_id(note_id):
            return None
        wav = self.notes_dir / note_id / "audio.wav"
        return wav if wav.is_file() else None

    def update_note(
        self,
        note_id: str,
        *,
        text: Optional[str] = None,
        title: Optional[str] = None,
        sticker: Optional[str] = None,
        rollovers: Optional[int] = None,
        asr_error: Optional[str] = None,
    ) -> Optional[Dict[str, Any]]:
        if not self.is_valid_id(note_id):
            return None
        with self._lock:
            meta = self._read_note_unlocked(note_id)
            if meta is None:
                return None
            if text is not None:
                meta["text"] = text.strip()
                if title is None and (not meta.get("title") or str(meta["title"]).startswith("第 ")):
                    meta["title"] = self._derive_title(meta["text"], int(meta.get("story_number") or 1))
            if title is not None and title.strip():
                meta["title"] = title.strip()[:40]
            if sticker is not None and sticker.strip():
                meta["sticker"] = sticker.strip()[:4]
            if rollovers is not None:
                meta["rollovers"] = int(rollovers)
            if asr_error is not None:
                meta["asr_error"] = asr_error
            meta["updated_at"] = now_iso()
            self._save_meta_and_md_unlocked(self.notes_dir / note_id, meta)
            return meta

    def delete_note(self, note_id: str) -> bool:
        if not self.is_valid_id(note_id):
            return False
        with self._lock:
            folder = self.notes_dir / note_id
            if not folder.is_dir():
                return False
            shutil.rmtree(folder, ignore_errors=True)
            return True
