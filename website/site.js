(() => {
  const THEME_KEY = 'site-theme-mode';

  function applyTheme(theme) {
    const isDark = theme === 'dark';
    document.documentElement.classList.toggle('dark', isDark);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', isDark ? '#18181b' : '#fafafa');
    document.querySelectorAll('[data-theme-toggle]').forEach((btn) => {
      btn.setAttribute('aria-pressed', String(isDark));
      btn.setAttribute('title', isDark ? '切换为亮色主题' : '切换为暗色主题');
    });
  }

  function initThemeToggle() {
    const current = document.documentElement.classList.contains('dark') ? 'dark' : 'light';
    applyTheme(current);
    document.querySelectorAll('[data-theme-toggle]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const next = document.documentElement.classList.contains('dark') ? 'light' : 'dark';
        try {
          localStorage.setItem(THEME_KEY, next);
        } catch (_) {}
        applyTheme(next);
      });
    });
  }

  function showToast(text) {
    let el = document.getElementById('site-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'site-toast';
      el.className = 'toast';
      el.setAttribute('role', 'status');
      document.body.appendChild(el);
    }
    el.textContent = text;
    el.classList.add('is-visible');
    clearTimeout(showToast._timer);
    showToast._timer = setTimeout(() => el.classList.remove('is-visible'), 2200);
  }

  function initFooterSpotlight() {
    document.querySelectorAll('[data-wordmark-svg]').forEach((svg) => {
      const circle = svg.querySelector('#glow-circle');
      if (!circle) return;
      svg.addEventListener('pointermove', (ev) => {
        const rect = svg.getBoundingClientRect();
        const vb = svg.viewBox.baseVal;
        if (!rect.width || !vb.width) return;
        const x = ((ev.clientX - rect.left) / rect.width) * vb.width;
        const y = ((ev.clientY - rect.top) / rect.height) * vb.height;
        circle.setAttribute('cx', x.toFixed(1));
        circle.setAttribute('cy', y.toFixed(1));
      });
    });
  }

  function initShotTabs() {
    const frame = document.querySelector('[data-shot-frame]');
    const img = document.querySelector('[data-shot-img]');
    const caption = document.querySelector('[data-shot-caption]');
    const tabs = document.querySelectorAll('[data-shot-tab]');
    if (!frame || !img || !tabs.length) return;

    tabs.forEach((btn) => {
      btn.addEventListener('click', () => {
        tabs.forEach((b) => {
          b.classList.remove('is-active');
          b.setAttribute('aria-selected', 'false');
        });
        btn.classList.add('is-active');
        btn.setAttribute('aria-selected', 'true');
        img.src = btn.dataset.src;
        img.alt = btn.dataset.alt || '';
        if (caption) caption.textContent = btn.dataset.caption || '';
        const isPhone = btn.dataset.ratio === 'phone';
        frame.classList.toggle('is-phone', isPhone);
        frame.classList.toggle('is-wide', !isPhone);
      });
    });
  }

  function initStorybookDemo() {
    const chips = document.querySelectorAll('[data-word-key]');
    const boxes = document.querySelector('[data-tianzi-boxes]');
    const pyEl = document.querySelector('[data-tianzi-py]');
    const noteEl = document.querySelector('[data-tianzi-note]');
    const speakBtn = document.querySelector('[data-tianzi-speak]');
    if (!chips.length || !boxes) return;

    const WORDS = {
      dino: {
        chars: ['小', '恐', '龙'],
        pinyin: 'xiǎo · kǒng · lóng',
        note: '故事主角词组，点击可收进「字词盒」，随时回听孩子自己的原声例句。',
        text: '小恐龙',
      },
      slowly: {
        chars: ['慢', '慢', '地'],
        pinyin: 'màn · màn · de',
        note: '若口述识别成「慢慢的」，后台会悄悄画一条温润下划线，点一下即可把「的」换为「地」。',
        text: '慢慢地',
      },
      room: {
        chars: ['房', '间'],
        pinyin: 'fáng · jiān',
        note: '离线语义分词自动识别词组边界，不把词生硬拆成单字，一年级识字更自然。',
        text: '房间',
      },
      forest: {
        chars: ['大', '森', '林'],
        pinyin: 'dà · sēn · lín',
        note: '点田字格可看笔顺动画并替换同音字，改完自动记入时光机，不怕改错。',
        text: '大森林',
      },
    };

    let activeText = WORDS.dino.text;

    function selectWord(key) {
      const item = WORDS[key] || WORDS.dino;
      activeText = item.text;
      chips.forEach((c) => c.classList.toggle('is-active', c.dataset.wordKey === key));
      boxes.innerHTML = item.chars
        .map((ch) => `<div class="tianzi-cell"><span>${ch}</span></div>`)
        .join('');
      if (pyEl) pyEl.textContent = item.pinyin;
      if (noteEl) noteEl.textContent = item.note;
    }

    chips.forEach((chip) => {
      chip.addEventListener('click', () => selectWord(chip.dataset.wordKey));
    });

    if (speakBtn) {
      speakBtn.addEventListener('click', () => {
        if ('speechSynthesis' in window) {
          window.speechSynthesis.cancel();
          const u = new SpeechSynthesisUtterance(activeText);
          u.lang = 'zh-CN';
          u.rate = 0.92;
          window.speechSynthesis.speak(u);
          showToast(`正在朗读「${activeText}」`);
        } else {
          showToast('当前浏览器不支持语音合成');
        }
      });
    }

    selectWord('dino');
  }

  function initReleaseHydration() {
    const manifestPath = document.documentElement.dataset.releaseJson;
    if (!manifestPath) return;
    fetch(manifestPath)
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        if (!data) return;
        document.querySelectorAll('[data-release-version]').forEach((el) => {
          el.textContent = `v${data.version}`;
        });
        document.querySelectorAll('[data-release-size]').forEach((el) => {
          el.textContent = data.sizeLabel || '';
        });
        document.querySelectorAll('[data-release-sha]').forEach((el) => {
          el.textContent = data.sha256 ? data.sha256.slice(0, 16) + '…' : '';
        });
        document.querySelectorAll('[data-release-apk]').forEach((el) => {
          el.setAttribute('href', data.apkUrl);
        });
        const list = document.querySelector('[data-release-notes]');
        if (list && Array.isArray(data.releaseNotes) && data.releaseNotes.length) {
          list.innerHTML = data.releaseNotes.map((n) => `<li>${n}</li>`).join('');
        }
      })
      .catch(() => {});
  }

  function initCopyButtons() {
    document.querySelectorAll('[data-copy-text]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const text = btn.dataset.copyText || '';
        try {
          await navigator.clipboard.writeText(text);
          showToast(btn.dataset.copyToast || '已复制链接');
        } catch (_) {
          showToast(text);
        }
      });
    });
  }

  document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();
    initFooterSpotlight();
    initShotTabs();
    initStorybookDemo();
    initReleaseHydration();
    initCopyButtons();
  });
})();
