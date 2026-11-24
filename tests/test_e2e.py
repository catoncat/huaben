#!/usr/bin/env python3
"""End-to-end test for Story Note: continuous WAV capture, Doubao rolling ASR, and Note CRUD."""

from __future__ import annotations

import http.client
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import wave
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT_DIR / "server"))

from app import build_server  # noqa: E402


def synth_pcm_16k(text: str) -> bytes:
    with tempfile.NamedTemporaryFile(suffix=".aiff", delete=False) as f1, tempfile.NamedTemporaryFile(
        suffix=".wav", delete=False
    ) as f2:
        p1, p2 = f1.name, f2.name
    try:
        subprocess.run(["say", "-o", p1, text], check=True)
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", p1, p2], check=True)
        with wave.open(p2, "rb") as wf:
            return wf.readframes(wf.getnframes())
    finally:
        for p in (p1, p2):
            if os.path.exists(p):
                os.unlink(p)


class StoryNoteE2ETest(unittest.TestCase):
    def setUp(self) -> None:
        os.environ["STORY_NOTE_QUIET"] = "1"
        self.tmp_dir = Path(tempfile.mkdtemp(prefix="story-note-test-"))
        # Configure short soft_rollover_sec=3.0s so a ~10s 2-sentence story exercises real session rollover!
        self.httpd, self.ctx = build_server(
            "127.0.0.1",
            0,
            self.tmp_dir,
            soft_rollover_sec=3.0,
            hard_rollover_sec=12.0,
        )
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2.0)
        import shutil
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def _req(self, method: str, path: str, body: bytes = b"", headers: dict | None = None) -> tuple[int, dict, bytes]:
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=25.0)
        try:
            conn.request(method, path, body=body, headers=headers or {})
            resp = conn.getresponse()
            raw = resp.read()
            hdrs = {k.lower(): v for k, v in resp.getheaders()}
            return resp.status, hdrs, raw
        finally:
            conn.close()

    def test_full_story_recording_rollover_and_note_persistence(self) -> None:
        part1 = synth_pcm_16k("从前有一只爱探险的小猫咪。")
        pause = b"\x00\x00" * int(16000 * 0.9)
        part2 = synth_pcm_16k("他坐着热气球飞到了月亮上面。")
        full_pcm = part1 + pause + part2
        expected_sec = round(len(full_pcm) / 32000.0, 2)

        # 1. Start recording
        st, _, raw = self._req("POST", "/api/rec/start")
        self.assertEqual(st, 200)
        rec_id = json.loads(raw)["rec_id"]

        # 2. Stream chunks + verify X-Chunk-Seq deduplication
        seq = 0
        chunk_size = 8000  # 250ms @ 16kHz S16_LE
        for off in range(0, len(full_pcm), chunk_size):
            seq += 1
            piece = full_pcm[off : off + chunk_size]
            st, _, raw = self._req(
                "POST",
                f"/api/rec/{rec_id}/chunk",
                body=piece,
                headers={"X-Chunk-Seq": str(seq)},
            )
            self.assertEqual(st, 200)
            if seq == 1:
                # Duplicate retry of seq=1 must NOT double-append WAV bytes!
                st2, _, _ = self._req(
                    "POST",
                    f"/api/rec/{rec_id}/chunk",
                    body=piece,
                    headers={"X-Chunk-Seq": "1"},
                )
                self.assertEqual(st2, 200)
            time.sleep(0.03)

        # 3. Finish recording & save note
        st, _, raw = self._req("POST", f"/api/rec/{rec_id}/finish")
        self.assertEqual(st, 200, raw.decode("utf-8", "replace"))
        note = json.loads(raw)["note"]

        self.assertEqual(note["story_number"], 1)
        self.assertAlmostEqual(note["duration_sec"], expected_sec, delta=0.05)
        self.assertGreaterEqual(note["rollovers"], 1, f"Expected >=1 rollover, got {note}")
        self.assertIn("猫", note["text"])
        self.assertTrue("月亮" in note["text"] or "气球" in note["text"], f"Unexpected text: {note['text']}")

        # 4. Verify on-disk Markdown & WAV integrity
        md_path = Path(note["note_md_path"])
        self.assertTrue(md_path.is_file())
        md_text = md_path.read_text(encoding="utf-8")
        self.assertIn(note["text"], md_text)

        # 5. Verify HTTP Range request (206 Partial Content) for Android Chrome <audio> seeking
        st_range, hdrs, wav_slice = self._req(
            "GET",
            note["audio_url"],
            headers={"Range": "bytes=0-43"},
        )
        self.assertEqual(st_range, 206)
        self.assertEqual(len(wav_slice), 44)
        self.assertEqual(wav_slice[:4], b"RIFF")
        self.assertIn("bytes 0-43/", hdrs.get("content-range", ""))

        # 6. Verify PATCH update & Retranscribe
        st_patch, _, raw_patch = self._req(
            "PATCH",
            f"/api/notes/{note['id']}",
            body=json.dumps({"sticker": "🚀", "title": "小猫飞向月亮"}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(st_patch, 200)
        patched = json.loads(raw_patch)["note"]
        self.assertEqual(patched["sticker"], "🚀")
        self.assertEqual(patched["title"], "小猫飞向月亮")

        st_re, _, raw_re = self._req("POST", f"/api/notes/{note['id']}/retranscribe")
        self.assertEqual(st_re, 200)
        re_note = json.loads(raw_re)["note"]
        self.assertIn("猫", re_note["text"])


if __name__ == "__main__":
    unittest.main()
