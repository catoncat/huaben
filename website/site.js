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
    showToast._timer = setTimeout(() => el.classList.remove('is-visible'), 2000);
  }

  function initQrPopovers() {
    const wraps = Array.from(document.querySelectorAll('[data-qr-wrap]'));
    if (!wraps.length) return;
    wraps.forEach((wrap) => {
      const trigger = wrap.querySelector('[data-qr-trigger]');
      if (!trigger) return;
      trigger.addEventListener('click', (ev) => {
        ev.stopPropagation();
        const willOpen = !wrap.classList.contains('is-open');
        wraps.forEach((w) => w.classList.remove('is-open'));
        if (willOpen) wrap.classList.add('is-open');
        trigger.setAttribute('aria-expanded', String(willOpen));
      });
    });
    document.addEventListener('click', (ev) => {
      wraps.forEach((w) => {
        if (!w.contains(ev.target)) {
          w.classList.remove('is-open');
          const t = w.querySelector('[data-qr-trigger]');
          if (t) t.setAttribute('aria-expanded', 'false');
        }
      });
    });
    document.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape') {
        wraps.forEach((w) => {
          w.classList.remove('is-open');
          const t = w.querySelector('[data-qr-trigger]');
          if (t) t.setAttribute('aria-expanded', 'false');
        });
      }
    });
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

  function initShotGallery() {
    const img = document.querySelector('[data-shot-img]');
    const caption = document.querySelector('[data-shot-caption]');
    const countEl = document.querySelector('[data-shot-count]');
    const tabs = Array.from(document.querySelectorAll('[data-shot-tab]'));
    if (!img || !tabs.length) return;

    let activeIdx = 0;

    function selectIdx(idx) {
      activeIdx = (idx + tabs.length) % tabs.length;
      const btn = tabs[activeIdx];
      tabs.forEach((b, i) => {
        b.classList.toggle('is-active', i === activeIdx);
        b.setAttribute('aria-selected', String(i === activeIdx));
      });
      img.src = btn.dataset.src;
      img.alt = btn.dataset.alt || '';
      if (caption) caption.textContent = btn.dataset.caption || '';
      if (countEl) countEl.textContent = `${activeIdx + 1} / ${tabs.length}`;
    }

    tabs.forEach((btn, idx) => {
      btn.addEventListener('click', () => selectIdx(idx));
    });

    const prevBtn = document.querySelector('[data-shot-prev]');
    const nextBtn = document.querySelector('[data-shot-next]');
    if (prevBtn) prevBtn.addEventListener('click', () => selectIdx(activeIdx - 1));
    if (nextBtn) nextBtn.addEventListener('click', () => selectIdx(activeIdx + 1));

    selectIdx(0);
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
        document.querySelectorAll('[data-release-apk]').forEach((el) => {
          el.setAttribute('href', data.latestApkUrl || data.apkUrl);
        });
        document.querySelectorAll('[data-release-copy]').forEach((el) => {
          el.dataset.copyText = data.apkUrl;
        });
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
    initQrPopovers();
    initFooterSpotlight();
    initShotGallery();
    initReleaseHydration();
    initCopyButtons();
  });
})();
