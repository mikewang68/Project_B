(function () {
  'use strict';
  const PREF_KEY = 'b-ehm-prefs';
  const DEFAULT_THEME = 'tech-blue';
  const DEFAULT_LAYOUT = 'side';
  const THEMES = ['tech-blue', 'forest-green', 'purple-elegant', 'dark-pro'];
  const LAYOUTS = ['side', 'top', 'compact'];
  function readPrefs() {
    const fallback = { theme: DEFAULT_THEME, layout: DEFAULT_LAYOUT };
    try {
      const parsed = JSON.parse(localStorage.getItem(PREF_KEY) || '{}');
      return { theme: THEMES.includes(parsed.theme) ? parsed.theme : fallback.theme, layout: LAYOUTS.includes(parsed.layout) ? parsed.layout : fallback.layout };
    } catch (_) { return fallback; }
  }
  let prefs = readPrefs();
  function persist() { try { localStorage.setItem(PREF_KEY, JSON.stringify(prefs)); } catch (_) { /* 会话内仍可切换 */ } }
  function applyTheme(theme) { document.documentElement.setAttribute('data-theme', theme); }
  function applyLayout(layout) {
    document.documentElement.setAttribute('data-layout', layout);
    const shell = document.getElementById('appShell');
    if (shell) shell.setAttribute('data-layout', layout);
    if (layout !== 'side') document.body.classList.remove('sidebar-collapsed');
  }
  function syncControls() {
    document.querySelectorAll('[data-theme-option]').forEach((button) => { const active = button.dataset.themeOption === prefs.theme; button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active)); });
    document.querySelectorAll('[data-layout-option]').forEach((button) => { const active = button.dataset.layoutOption === prefs.layout; button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active)); });
  }
  function setTheme(theme) { if (!THEMES.includes(theme)) return; prefs = { ...prefs, theme }; applyTheme(theme); persist(); syncControls(); }
  function setLayout(layout) { if (!LAYOUTS.includes(layout)) return; prefs = { ...prefs, layout }; applyLayout(layout); persist(); syncControls(); refreshTopMenu(); }
  function reset() { prefs = { theme: DEFAULT_THEME, layout: DEFAULT_LAYOUT }; document.body.classList.remove('sidebar-collapsed'); applyTheme(prefs.theme); applyLayout(prefs.layout); persist(); syncControls(); }
  function openDrawer() { const drawer = document.getElementById('preferenceDrawer'); const backdrop = document.getElementById('preferenceBackdrop'); if (!drawer || !backdrop) return; drawer.classList.add('open'); drawer.setAttribute('aria-hidden', 'false'); backdrop.classList.add('show'); document.getElementById('preferenceClose')?.focus(); }
  function closeDrawer() { const drawer = document.getElementById('preferenceDrawer'); const backdrop = document.getElementById('preferenceBackdrop'); if (!drawer || !backdrop) return; drawer.classList.remove('open'); drawer.setAttribute('aria-hidden', 'true'); backdrop.classList.remove('show'); }
  function refreshTopMenu() {
    const target = document.getElementById('topLayoutNav'); const source = document.getElementById('navigation'); if (!target || !source) return;
    target.innerHTML = [...source.querySelectorAll('.nav-group')].map((group) => {
      const title = group.querySelector('.nav-parent strong')?.textContent.trim() || '功能菜单';
      const children = [...group.querySelectorAll('.nav-child')];
      const active = children.some((item) => item.classList.contains('active')) ? ' active' : '';
      const submenu = children.map((item) => {
        const page = item.dataset.page || '';
        const label = item.childNodes[0]?.textContent.trim() || item.textContent.replace(/\s+/g, ' ').trim();
        const selected = item.classList.contains('active') ? ' active' : '';
        return `<button class="top-layout-subitem${selected}" type="button" data-top-page="${page}">${label}</button>`;
      }).join('');
      return `<div class="top-layout-group${active}"><button class="top-layout-group-button" type="button">${title}<i>⌄</i></button><div class="top-layout-dropdown">${submenu}</div></div>`;
    }).join('');
  }
  applyTheme(prefs.theme);
  document.documentElement.setAttribute('data-layout', prefs.layout);
  window.EhmPreferences = { get current() { return { ...prefs }; }, setTheme, setLayout, reset, open: openDrawer, close: closeDrawer, refreshTopMenu, toggleSidebar() { if (prefs.layout === 'side') document.body.classList.toggle('sidebar-collapsed'); } };
  document.addEventListener('DOMContentLoaded', () => {
    applyLayout(prefs.layout); syncControls(); refreshTopMenu();
    document.getElementById('appearanceSettings')?.addEventListener('click', openDrawer);
    document.getElementById('preferenceClose')?.addEventListener('click', closeDrawer);
    document.getElementById('preferenceBackdrop')?.addEventListener('click', closeDrawer);
    document.getElementById('preferenceReset')?.addEventListener('click', reset);
    document.getElementById('sidebarToggle')?.addEventListener('click', (event) => { event.preventDefault(); event.stopImmediatePropagation(); window.EhmPreferences.toggleSidebar(); }, true);
    document.querySelectorAll('[data-theme-option]').forEach((button) => button.addEventListener('click', () => setTheme(button.dataset.themeOption)));
    document.querySelectorAll('[data-layout-option]').forEach((button) => button.addEventListener('click', () => setLayout(button.dataset.layoutOption)));
    document.getElementById('topLayoutNav')?.addEventListener('click', (event) => { const button = event.target.closest('[data-top-page]'); if (!button || typeof window.setPage !== 'function') return; window.setPage(button.dataset.topPage); refreshTopMenu(); });
    document.addEventListener('keydown', (event) => { if (event.key === 'Escape') closeDrawer(); });
  });
  window.addEventListener('storage', (event) => { if (event.key !== PREF_KEY) return; prefs = readPrefs(); applyTheme(prefs.theme); applyLayout(prefs.layout); syncControls(); });
})();
