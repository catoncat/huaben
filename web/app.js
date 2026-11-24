(() => {
  "use strict";

  let stickers = ["🦖", "🚀", "🏰", "🐳", "🦊", "🌈", "🦄", "🐙", "🐉", "🎈", "🦁", "🍄"];
  let notes = [];
  let editingNoteId = null;
  let activeAudio = null;
  let activePlayingId = null;
  let toastTimeout = null;

  const studioCard = document.getElementById("studioCard");
  const studioBadge = document.getElementById("studioBadge");
  const timerDisplay = document.getElementById("timerDisplay");
  const recordToggleBtn = document.getElementById("recordToggleBtn");
  const recordBtnIcon = document.getElementById("recordBtnIcon");
  const recordBtnLabel = document.getElementById("recordBtnLabel");
  const recordBtnSub = document.getElementById("recordBtnSub");
  const waveBars = Array.from(document.querySelectorAll("#waveBars span"));
  const liveTranscriptBox = document.getElementById("liveTranscriptBox");
  const cancelRecBtn = document.getElementById("cancelRecBtn");
  const noteCountNum = document.getElementById("noteCountNum");
  const emptyShelf = document.getElementById("emptyShelf");
  const notesList = document.getElementById("notesList");
  const httpsWarning = document.getElementById("httpsWarning");
  const toastBanner = document.getElementById("toastBanner");

  const editDialog = document.getElementById("editDialog");
  const editForm = document.getElementById("editForm");
  const editTitleInput = document.getElementById("editTitleInput");
  const editTextInput = document.getElementById("editTextInput");
  const closeEditBtn = document.getElementById("closeEditBtn");
  const cancelEditBtn = document.getElementById("cancelEditBtn");

  function showToast(msg, ok = false) {
    if (toastTimeout) clearTimeout(toastTimeout);
    toastBanner.textContent = msg;
    toastBanner.className = ok ? "toast-banner ok" : "toast-banner";
    toastBanner.hidden = false;
    toastTimeout = setTimeout(() => { toastBanner.hidden = true; }, 4200);
  }

  function formatDuration(sec) {
    const s = Math.max(0, Math.round(Number(sec) || 0));
    return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
  }

  function formatDateLabel(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    const pad = (n) => String(n).padStart(2, "0");
    return `${d.getMonth() + 1}月${d.getDate()}日 ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  function renderLiveTranscript(text, placeholder) {
    liveTranscriptBox.innerHTML = "";
    const p = document.createElement("p");
    if (text && text.trim()) {
      p.style.margin = "0";
      p.textContent = text;
    } else {
      p.className = "live-placeholder";
      p.textContent = placeholder;
    }
    liveTranscriptBox.appendChild(p);
    liveTranscriptBox.scrollTop = liveTranscriptBox.scrollHeight;
  }

  function updateWaveVisualizer(active, level) {
    for (let i = 0; i < waveBars.length; i++) {
      if (!active) {
        waveBars[i].style.height = "6px";
        continue;
      }
      const centerDist = Math.abs(i - (waveBars.length - 1) / 2) / (waveBars.length / 2);
      const wave = Math.sin(Date.now() / 110 + i * 0.65) * 0.25 + 0.75;
      const h = Math.round(6 + Math.min(1, level * 4.2) * (1 - centerDist * 0.45) * wave * 28);
      waveBars[i].style.height = `${Math.max(6, Math.min(34, h))}px`;
    }
  }

  function setStudioVisualState(mode) {
    studioCard.classList.remove("is-recording", "is-saving");
    if (mode === "recording") {
      studioCard.classList.add("is-recording");
      studioBadge.textContent = "🔴 正在听你讲故事…";
      recordToggleBtn.setAttribute("aria-pressed", "true");
      recordBtnIcon.textContent = "⏹️";
      recordBtnLabel.textContent = "讲完了点这里";
      recordBtnSub.textContent = "自动存好录音和文字";
      cancelRecBtn.hidden = false;
    } else if (mode === "saving") {
      studioCard.classList.add("is-saving");
      studioBadge.textContent = "✨ 正在存进故事本…";
      recordBtnIcon.textContent = "⏳";
      recordBtnLabel.textContent = "正在保存故事…";
      recordBtnSub.textContent = "等一小下就好啦";
      cancelRecBtn.hidden = true;
    } else {
      studioBadge.textContent = "准备开讲";
      timerDisplay.textContent = "00:00";
      recordToggleBtn.setAttribute("aria-pressed", "false");
      recordBtnIcon.textContent = "🎙️";
      recordBtnLabel.textContent = "点一下开始讲";
      recordBtnSub.textContent = "手可以放开 · 想多久都不会断";
      cancelRecBtn.hidden = true;
      updateWaveVisualizer(false, 0);
    }
  }

  const recorder = new window.StoryRecorder.RecorderController({
    onTranscript: (text) => {
      if (text) renderLiveTranscript(text, "");
    },
    onTick: (elapsedSec, level) => {
      timerDisplay.textContent = formatDuration(elapsedSec);
      updateWaveVisualizer(true, level);
    },
  });

  function stopActivePlayback() {
    if (activeAudio) {
      activeAudio.pause();
      activeAudio = null;
    }
    const prevId = activePlayingId;
    activePlayingId = null;
    if (prevId) {
      const btn = document.querySelector(`[data-play-id="${prevId}"]`);
      if (btn) {
        btn.classList.remove("playing");
        btn.textContent = "▶️ 听我讲的";
      }
    }
  }

  function togglePlayNote(note, playBtn, rangeEl, durEl) {
    if (activePlayingId === note.id && activeAudio) {
      stopActivePlayback();
      durEl.textContent = formatDuration(note.duration_sec);
      return;
    }
    stopActivePlayback();
    const audio = new Audio(note.audio_url);
    activeAudio = audio;
    activePlayingId = note.id;
    playBtn.classList.add("playing");
    playBtn.textContent = "⏸️ 暂停";

    audio.addEventListener("timeupdate", () => {
      const total = Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : note.duration_sec || 1;
      rangeEl.max = String(total);
      rangeEl.value = String(audio.currentTime);
      durEl.textContent = `${formatDuration(audio.currentTime)} / ${formatDuration(total)}`;
    });
    audio.addEventListener("ended", () => {
      if (activePlayingId === note.id) {
        stopActivePlayback();
        rangeEl.value = "0";
        durEl.textContent = formatDuration(note.duration_sec);
      }
    });
    audio.addEventListener("error", () => {
      showToast("播放录音失败，请重试");
      stopActivePlayback();
    });

    const startTime = Number(rangeEl.value) || 0;
    if (startTime > 0 && startTime < (note.duration_sec || 0)) audio.currentTime = startTime;
    audio.play().catch((err) => {
      showToast(`无法播放：${err.message || err}`);
      stopActivePlayback();
    });
  }

  async function cycleSticker(note) {
    const idx = stickers.indexOf(note.sticker);
    const nextSticker = stickers[(idx + 1) % stickers.length];
    const resp = await fetch(`/api/notes/${encodeURIComponent(note.id)}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sticker: nextSticker }),
    }).catch(() => null);
    if (resp && resp.ok) {
      const data = await resp.json();
      if (data.note) {
        notes = notes.map((n) => (n.id === note.id ? data.note : n));
        renderNotes();
      }
    }
  }

  async function retranscribeNote(note, btn) {
    const orig = btn.textContent;
    btn.disabled = true;
    btn.textContent = "⏳ 识别中…";
    try {
      const resp = await fetch(`/api/notes/${encodeURIComponent(note.id)}/retranscribe`, { method: "POST" });
      const data = await resp.json();
      if (!resp.ok || !data.note) throw new Error(data.error || "重新识别失败");
      notes = notes.map((n) => (n.id === note.id ? data.note : n));
      renderNotes();
      showToast("✨ 文字已重新识别更新！", true);
    } catch (err) {
      showToast(err.message || "重新识别失败");
      btn.disabled = false;
      btn.textContent = orig;
    }
  }

  async function deleteNote(note) {
    if (!window.confirm(`确定要删掉「第 ${note.story_number} 篇：${note.title}」的录音和文字吗？`)) return;
    if (activePlayingId === note.id) stopActivePlayback();
    try {
      const resp = await fetch(`/api/notes/${encodeURIComponent(note.id)}`, { method: "DELETE" });
      if (!resp.ok) throw new Error("删除失败");
      notes = notes.filter((n) => n.id !== note.id);
      renderNotes();
    } catch (err) {
      showToast(err.message || "删除失败");
    }
  }

  function renderNotes() {
    noteCountNum.textContent = String(notes.length);
    emptyShelf.hidden = notes.length > 0;
    notesList.innerHTML = "";

    for (const note of notes) {
      const card = document.createElement("article");
      card.className = "note-card";

      const top = document.createElement("div");
      top.className = "note-top";
      const identity = document.createElement("div");
      identity.className = "note-identity";

      const stickerBtn = document.createElement("button");
      stickerBtn.type = "button";
      stickerBtn.className = "sticker-btn";
      stickerBtn.title = "点一下换个小图标";
      stickerBtn.textContent = note.sticker || "🦖";
      stickerBtn.addEventListener("click", () => cycleSticker(note));

      const headings = document.createElement("div");
      headings.className = "note-headings";
      const numRow = document.createElement("div");
      numRow.className = "note-number-row";
      const badge = document.createElement("span");
      badge.className = "note-badge-num";
      badge.textContent = `第 ${note.story_number} 篇`;
      const timeSpan = document.createElement("span");
      timeSpan.className = "note-time";
      timeSpan.textContent = formatDateLabel(note.created_at);
      numRow.append(badge, timeSpan);

      const titleEl = document.createElement("h3");
      titleEl.className = "note-title";
      titleEl.textContent = note.title || `第 ${note.story_number} 篇故事`;
      headings.append(numRow, titleEl);
      identity.append(stickerBtn, headings);
      top.appendChild(identity);

      const playerBar = document.createElement("div");
      playerBar.className = "player-bar";
      const playBtn = document.createElement("button");
      playBtn.type = "button";
      playBtn.className = activePlayingId === note.id ? "play-btn playing" : "play-btn";
      playBtn.dataset.playId = note.id;
      playBtn.textContent = activePlayingId === note.id ? "⏸️ 暂停" : "▶️ 听我讲的";

      const progressWrap = document.createElement("div");
      progressWrap.className = "player-progress-wrap";
      const rangeEl = document.createElement("input");
      rangeEl.type = "range";
      rangeEl.className = "player-range";
      rangeEl.min = "0";
      rangeEl.max = String(Math.max(1, note.duration_sec || 1));
      rangeEl.step = "0.1";
      rangeEl.value = "0";
      rangeEl.setAttribute("aria-label", "播放进度");
      const durEl = document.createElement("span");
      durEl.className = "player-dur";
      durEl.textContent = formatDuration(note.duration_sec);

      rangeEl.addEventListener("input", () => {
        if (activePlayingId === note.id && activeAudio) activeAudio.currentTime = Number(rangeEl.value) || 0;
      });
      playBtn.addEventListener("click", () => togglePlayNote(note, playBtn, rangeEl, durEl));
      progressWrap.append(rangeEl, durEl);
      playerBar.append(playBtn, progressWrap);

      const textDiv = document.createElement("div");
      if (note.text && note.text.trim()) {
        textDiv.className = "note-text";
        textDiv.textContent = note.text;
      } else {
        textDiv.className = "note-text is-empty";
        textDiv.textContent = note.asr_error
          ? `录音已安全保存（识别暂未完成：${note.asr_error}，可点下方「重新识别」）`
          : "录音已保存（没有识别到说话内容，可点「重新识别」）";
      }

      const footer = document.createElement("div");
      footer.className = "note-footer";
      const editBtn = document.createElement("button");
      editBtn.type = "button";
      editBtn.className = "tool-btn";
      editBtn.textContent = "✏️ 改字";
      editBtn.addEventListener("click", () => {
        editingNoteId = note.id;
        editTitleInput.value = note.title || "";
        editTextInput.value = note.text || "";
        if (typeof editDialog.showModal === "function") editDialog.showModal();
      });

      const reBtn = document.createElement("button");
      reBtn.type = "button";
      reBtn.className = "tool-btn";
      reBtn.textContent = "🔄 重新识别";
      reBtn.addEventListener("click", () => retranscribeNote(note, reBtn));

      const delBtn = document.createElement("button");
      delBtn.type = "button";
      delBtn.className = "tool-btn danger";
      delBtn.textContent = "🗑️ 删除";
      delBtn.addEventListener("click", () => deleteNote(note));

      footer.append(editBtn, reBtn, delBtn);
      card.append(top, playerBar, textDiv, footer);
      notesList.appendChild(card);
    }
  }

  recordToggleBtn.addEventListener("click", async () => {
    if (recorder.isRecording) {
      if (!recorder.canFinishNow()) return;
      recordToggleBtn.disabled = true;
      setStudioVisualState("saving");
      try {
        const saved = await recorder.finish();
        if (saved) {
          notes = [saved, ...notes.filter((n) => n.id !== saved.id)];
          renderNotes();
          renderLiveTranscript(saved.text || "", "录音已存好！点右边绿色按钮就能听自己讲的故事啦。");
          showToast(`🎉 第 ${saved.story_number} 篇故事存好啦！`, true);
        }
      } catch (err) {
        showToast(err.message || "保存失败");
      } finally {
        setStudioVisualState("idle");
        recordToggleBtn.disabled = false;
      }
    } else {
      stopActivePlayback();
      recordToggleBtn.disabled = true;
      try {
        await recorder.start();
        setStudioVisualState("recording");
        renderLiveTranscript("", "开口说话吧，你说的每一句话都会出现在这里…");
      } catch (err) {
        setStudioVisualState("idle");
        const msg = err && err.name === "NotAllowedError"
          ? "请点「允许」开启麦克风权限，才能录故事哦！"
          : `启动录音失败：${err.message || err}`;
        showToast(msg);
      } finally {
        recordToggleBtn.disabled = false;
      }
    }
  });

  cancelRecBtn.addEventListener("click", async () => {
    await recorder.cancel();
    setStudioVisualState("idle");
    renderLiveTranscript("", "已取消刚才的录音，随时可以重新开始讲！");
  });

  closeEditBtn.addEventListener("click", () => editDialog.close());
  cancelEditBtn.addEventListener("click", () => editDialog.close());

  editForm.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    if (!editingNoteId) return;
    try {
      const resp = await fetch(`/api/notes/${encodeURIComponent(editingNoteId)}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: editTitleInput.value.trim(), text: editTextInput.value.trim() }),
      });
      const data = await resp.json();
      if (!resp.ok || !data.note) throw new Error(data.error || "保存修改失败");
      notes = notes.map((n) => (n.id === editingNoteId ? data.note : n));
      renderNotes();
      editDialog.close();
      showToast("已保存修改", true);
    } catch (err) {
      showToast(err.message || "保存失败");
    }
  });

  httpsWarning.hidden = window.isSecureContext || location.hostname === "localhost" || location.hostname === "127.0.0.1";

  Promise.all([fetch("/api/health"), fetch("/api/notes")])
    .then(async ([hResp, nResp]) => {
      if (hResp.ok) {
        const h = await hResp.json();
        if (Array.isArray(h.stickers) && h.stickers.length > 0) stickers = h.stickers;
      }
      if (nResp.ok) {
        const n = await nResp.json();
        notes = Array.isArray(n.notes) ? n.notes : [];
        renderNotes();
      }
    })
    .catch((err) => showToast(`连接服务失败：${err.message || err}`));
})();
