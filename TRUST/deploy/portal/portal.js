(() => {
  const STORAGE_KEY = 'b-project-appearance';
  const themes = [
    {id: 'tech-blue', name: '科技蓝', description: '深海军蓝导航 · 科技蓝主色（默认）', colors: ['#24364a', '#409eff']},
    {id: 'forest-green', name: '护眼墨绿', description: '墨绿导航 · 统一科技蓝主色', colors: ['#20382f', '#409eff']},
    {id: 'purple-elegant', name: '雅致深灰', description: '深灰导航 · 统一科技蓝主色', colors: ['#2c313a', '#409eff']},
    {id: 'dark-pro', name: '暗夜黑', description: '深色海军蓝 · 暗光护眼', colors: ['#1f3043', '#409eff']}
  ];
  const layouts = [
    {id: 'side', name: '左侧菜单', description: '经典纵向导航'},
    {id: 'top', name: '顶部导航', description: '横向菜单 · 内容全宽'},
    {id: 'compact', name: '图标窄栏', description: '窄侧栏 · 悬停提示'}
  ];
  const frame = document.querySelector('#portal-frame');
  const list = document.querySelector('#services');
  const search = document.querySelector('#search');
  const categoryButtons = [...document.querySelectorAll('[data-category]')];
  const overlay = document.querySelector('#appearance-overlay');
  const panel = document.querySelector('#appearance-panel');
  const audience = document.documentElement.dataset.audience || 'internal';
  let services = [], category = '全部', lastFocus = null;

  function readPreference() {
    try {
      const cookie = document.cookie.split('; ').find(item => item.startsWith(STORAGE_KEY + '='));
      const raw = cookie ? decodeURIComponent(cookie.slice(STORAGE_KEY.length + 1)) : localStorage.getItem(STORAGE_KEY);
      const value = JSON.parse(raw || '{}');
      return {
        theme: themes.some(item => item.id === value.theme) ? value.theme : 'tech-blue',
        layout: layouts.some(item => item.id === value.layout) ? value.layout : 'side'
      };
    } catch {
      return {theme: 'tech-blue', layout: 'side'};
    }
  }

  let preference = readPreference();

  function persistPreference() {
    const value = JSON.stringify(preference);
    try { localStorage.setItem(STORAGE_KEY, value); } catch { /* current-page appearance still works */ }
    document.cookie = STORAGE_KEY + '=' + encodeURIComponent(value) + '; Path=/; Max-Age=31536000; SameSite=Lax';
  }

  function applyAppearance() {
    document.documentElement.dataset.theme = preference.theme;
    document.documentElement.style.colorScheme = preference.theme === 'dark-pro' ? 'dark' : 'light';
    frame.dataset.layout = preference.layout;
    document.querySelector('meta[name="theme-color"]').content = preference.theme === 'dark-pro' ? '#1f3043' :
      ({'forest-green': '#20382f', 'purple-elegant': '#2c313a'}[preference.theme] || '#24364a');
    document.querySelectorAll('[data-theme-option]').forEach(button => {
      const active = button.dataset.themeOption === preference.theme;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
      button.querySelector('.option-check').hidden = !active;
    });
    document.querySelectorAll('[data-layout-option]').forEach(button => {
      const active = button.dataset.layoutOption === preference.layout;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    });
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function buildAppearanceOptions() {
    const themeRoot = document.querySelector('#theme-options');
    for (const theme of themes) {
      const button = element('button', 'theme-option');
      button.type = 'button'; button.dataset.themeOption = theme.id;
      const swatch = element('span', 'theme-swatch'); swatch.setAttribute('aria-hidden', 'true');
      const navColor = element('i'), primaryColor = element('i');
      navColor.style.background = theme.colors[0]; primaryColor.style.background = theme.colors[1];
      swatch.append(navColor, primaryColor);
      const copy = element('span', 'option-copy');
      copy.append(element('strong', '', theme.name), element('small', '', theme.description));
      const check = element('span', 'option-check', '✓'); check.setAttribute('aria-hidden', 'true');
      button.append(swatch, copy, check);
      button.addEventListener('click', () => { preference.theme = theme.id; persistPreference(); applyAppearance(); });
      themeRoot.append(button);
    }
    const layoutRoot = document.querySelector('#layout-options');
    for (const layout of layouts) {
      const button = element('button', 'layout-option');
      button.type = 'button'; button.dataset.layoutOption = layout.id;
      const thumb = element('span', 'layout-thumb'); thumb.dataset.thumb = layout.id; thumb.setAttribute('aria-hidden', 'true');
      const bar = element('i', 'thumb-bar'), body = element('i', 'thumb-body'); body.append(element('b'), element('b')); thumb.append(bar, body);
      button.append(thumb, element('strong', '', layout.name), element('small', '', layout.description));
      button.addEventListener('click', () => { preference.layout = layout.id; persistPreference(); applyAppearance(); });
      layoutRoot.append(button);
    }
  }

  function openAppearance(event) {
    lastFocus = event.currentTarget;
    overlay.hidden = false;
    document.body.classList.add('appearance-open');
    panel.focus();
  }

  function closeAppearance() {
    overlay.hidden = true;
    document.body.classList.remove('appearance-open');
    lastFocus?.focus();
  }

  function render() {
    const term = search.value.trim().toLocaleLowerCase();
    const matches = services.filter(service => (category === '全部' || service.category === category) &&
      [service.title, service.code, service.description, service.endpoint].join(' ').toLocaleLowerCase().includes(term));
    list.replaceChildren();
    for (const service of matches) {
      const card = element('article', 'card' + (service.id === 'trust' ? ' featured' : ''));
      card.dataset.category = service.category;
      const top = element('div', 'card-top');
      top.append(element('span', 'app-icon', service.code));
      if (audience === 'internal') top.append(element('span', 'badge', service.badge));
      const bottom = element('div', 'card-bottom');
      bottom.append(element('span', 'endpoint', service.endpoint));
      const link = element('a', 'open-link', '进入应用');
      link.href = service.url; link.target = '_blank'; link.rel = 'noopener noreferrer';
      link.setAttribute('aria-label', '进入' + service.title + '（新标签页）');
      link.append(element('span', '', '↗')); bottom.append(link);
      card.append(top, element('h2', '', service.title), element('p', 'description', service.description), bottom);
      list.append(card);
    }
    document.querySelector('#list-title').textContent = category === '全部' ? '全部应用' : category;
    document.querySelector('#result-count').textContent = matches.length + ' 个入口';
    document.querySelector('#empty').hidden = matches.length !== 0;
    for (const button of categoryButtons) {
      const active = button.dataset.category === category;
      button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active));
      if (button.closest('.side-category-nav')) button.title = frame.dataset.layout === 'compact' ? button.textContent.trim() : '';
    }
  }

  buildAppearanceOptions();
  applyAppearance();
  if (audience === 'public') search.placeholder = '搜索应用名称或缩写';
  categoryButtons.forEach(button => button.addEventListener('click', () => { category = button.dataset.category; render(); }));
  search.addEventListener('input', render);
  document.querySelector('#reset').addEventListener('click', () => { search.value = ''; category = '全部'; render(); search.focus(); });
  document.querySelectorAll('[data-appearance-open]').forEach(button => button.addEventListener('click', openAppearance));
  document.querySelector('.appearance-close').addEventListener('click', closeAppearance);
  document.querySelector('.appearance-reset').addEventListener('click', () => {
    preference = {theme: 'tech-blue', layout: 'side'}; persistPreference(); applyAppearance(); render();
  });
  overlay.addEventListener('mousedown', event => { if (event.target === overlay) closeAppearance(); });
  panel.addEventListener('keydown', event => {
    if (event.key === 'Escape') { closeAppearance(); return; }
    if (event.key !== 'Tab') return;
    const focusable = [...panel.querySelectorAll('button:not([disabled])')];
    const first = focusable[0], last = focusable.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  });
  window.addEventListener('storage', event => {
    if (event.key === STORAGE_KEY) { preference = readPreference(); applyAppearance(); render(); }
  });

  fetch('/portal-assets/services.json', {cache: 'no-store'}).then(response => {
    if (!response.ok) throw new Error('Catalogue unavailable');
    return response.json();
  }).then(data => {
    services = data.services;
    if (!Array.isArray(services) || data.audience !== audience || !Array.isArray(data.categories)) throw new Error('Catalogue invalid');
    categoryButtons.forEach(button => {
      button.hidden = button.dataset.category !== '全部' && !data.categories.includes(button.dataset.category);
    });
    document.querySelector('#app-count').textContent = services.length;
    render();
  }).catch(() => {
    document.querySelector('#load-error').hidden = false;
    document.querySelector('#result-count').textContent = '读取失败';
  });
})();
