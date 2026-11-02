// K-Downloader UI entry point: boot, shell (nav rail + banners), routing, theme/language,
// global keyboard, paste, drag & drop, clipboard suggestions and Python events.
import { connect, api, mockScenario } from './api.js';
import { store, setState, isActive } from './store.js';
import { loadDictionaries, setLang, t, getLang } from './i18n.js';
import { h, btn, clear, setText, isEditable } from './dom.js';
import { icon } from './icons.js';
import { formatPercent } from './format.js';
import { toast, openModal, confirmDialog, closeMenu, isModalOpen } from './ui/feedback.js';
import { navigate, removeJob, coerceUrl, showError, openPage } from './actions.js';
import { engineProgress } from './views/components.js';
import { createHome } from './views/home.js';
import { createHistory } from './views/history.js';
import { createSettings } from './views/settings.js';
import { createHelp } from './views/help.js';
import { createOnboarding } from './views/onboarding.js';

const NAV = [
  { route: 'home', iconName: 'download', label: 'nav.home' },
  { route: 'history', iconName: 'history', label: 'nav.history' },
  { route: 'settings', iconName: 'settings', label: 'nav.settings' },
  { route: 'help', iconName: 'help', label: 'nav.help' },
];

const views = {};
let pendingIncoming = null;
let closeModalOpen = false;
let booted = false;

const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------- theme / motion / language
const lightQuery = window.matchMedia('(prefers-color-scheme: light)');

function applyTheme() {
  const pref = store.settings?.theme || 'system';
  const resolved = pref === 'system' ? (lightQuery.matches ? 'light' : 'dark') : pref;
  if (document.documentElement.dataset.theme !== resolved) document.documentElement.dataset.theme = resolved;
}

function applyMotion() {
  if (store.settings?.reduce_motion) document.documentElement.dataset.motion = 'reduce';
  else delete document.documentElement.dataset.motion;
}

lightQuery.addEventListener('change', applyTheme);

// ---------------------------------------------------------------- views
function factory(route) {
  switch (route) {
    case 'home': return createHome();
    case 'history': return createHistory({ onRedownload: (url) => { navigate('home'); views.home.fillAndProbe(url); } });
    case 'settings': return createSettings();
    case 'help': return createHelp();
    case 'welcome': return createOnboarding({ onFinish: finishOnboarding });
    default: return null;
  }
}

function getView(route) {
  if (!views[route]) {
    const v = factory(route);
    if (!v) return null;
    views[route] = v;
    v.el.hidden = true;
    $('views').append(v.el);
  }
  return views[route];
}

function showRoute(route, opts = {}) {
  const view = getView(route);
  if (!view) return;
  for (const [key, v] of Object.entries(views)) {
    const on = key === route;
    if (v.el.hidden === on) v.el.hidden = !on;
  }
  $('app').classList.toggle('onboarding', route === 'welcome');
  for (const b of document.querySelectorAll('.rail-btn')) {
    if (b.dataset.route === route) b.setAttribute('aria-current', 'page');
    else b.removeAttribute('aria-current');
  }
  if (!opts.keepScroll) $('main').scrollTop = 0;
  view.el.classList.remove('view-enter');
  void view.el.offsetWidth;
  view.el.classList.add('view-enter');
  view.show?.(opts);
}

function destroyViews() {
  for (const [key, v] of Object.entries(views)) {
    v.destroy?.();
    v.el.remove();
    delete views[key];
  }
}

function finishOnboarding() {
  views.welcome?.destroy();
  views.welcome?.el.remove();
  delete views.welcome;
  navigate('home', { force: true });
  if (pendingIncoming) {
    const url = pendingIncoming;
    pendingIncoming = null;
    views.home.fillAndProbe(url);
  } else {
    views.home.focusInput();
  }
}

// ---------------------------------------------------------------- nav rail
const railRefs = { badge: null, engine: null, engineDot: null, engineTip: null };

function buildRail() {
  const rail = clear($('rail'));
  rail.setAttribute('aria-label', t('nav.label'));
  rail.append(h('div', { class: 'rail-logo' }, h('img', { src: 'assets/logo.svg', alt: 'K-Downloader', width: 36, height: 36, attrs: { draggable: 'false' } })));
  const items = h('div', { class: 'rail-items' });
  for (const n of NAV) {
    const b = h('button', {
      class: 'rail-btn', type: 'button', dataset: { route: n.route }, attrs: { 'aria-label': t(n.label) },
      on: { click: () => navigate(n.route) },
    }, icon(n.iconName, { size: 22 }), h('span', { class: 'tooltip', attrs: { 'aria-hidden': 'true' }, text: n.route === 'settings' ? `${t(n.label)}  (Ctrl+,)` : t(n.label) }));
    if (n.route === 'home') {
      railRefs.badge = h('span', { class: 'rail-badge num', hidden: true });
      b.append(railRefs.badge);
    }
    items.append(b);
  }
  // «Apoyar el proyecto» no es una vista: abre /donar en el navegador (clave "donate" de WEB_PAGES).
  items.append(h('button', {
    class: 'rail-btn rail-donate', type: 'button', attrs: { 'aria-label': t('nav.donate') },
    on: { click: () => openPage('donate') },
  }, icon('heart', { size: 22 }), h('span', { class: 'tooltip', attrs: { 'aria-hidden': 'true' }, text: t('nav.donate') })));
  rail.append(items, h('div', { class: 'rail-spacer' }));
  railRefs.engineDot = h('span', { class: 'engine-dot' });
  railRefs.engineTip = h('span', { class: 'tooltip', attrs: { 'aria-hidden': 'true' } });
  railRefs.engine = h('button', {
    class: 'engine-chip', type: 'button',
    on: { click: () => navigate('settings', { section: 'components' }) },
  }, icon('package', { size: 18 }), railRefs.engineDot, railRefs.engineTip);
  rail.append(railRefs.engine);
  if (store.mode === 'mock') rail.append(h('span', { class: 'demo-tag', text: t('nav.demo'), attrs: { title: t('nav.demo_hint') } }));
  clear($('drop-overlay')).append(h('div', { class: 'drop-label' }, icon('dropDown', { size: 18 }), h('span', { text: t('home.drop_here') })));
  syncEngineChip();
  syncRailBadge();
}

function engineState() {
  const e = store.engine;
  if (!e) return 'unknown';
  const comps = Object.values(e.components || {});
  if (e.busy || comps.some((c) => ['checking', 'downloading', 'installing'].includes(c.state))) return 'busy';
  if (comps.some((c) => c.state === 'error' || c.state === 'blocked') || !e.ready) return 'warn';
  return 'ready';
}

function syncEngineChip() {
  if (!railRefs.engine) return;
  const s = engineState();
  railRefs.engine.dataset.state = s;
  const label = t(`engine.chip.${s}`);
  railRefs.engine.setAttribute('aria-label', label);
  setText(railRefs.engineTip, label);
}

let badgeQueued = false;
function syncRailBadge() {
  if (badgeQueued) return;
  badgeQueued = true;
  setTimeout(() => {
    badgeQueued = false;
    if (!railRefs.badge) return;
    let n = 0;
    for (const j of store.jobs.values()) if (isActive(j)) n++;
    railRefs.badge.hidden = n === 0;
    setText(railRefs.badge, n > 9 ? '9+' : String(n));
  }, 60);
}

// ---------------------------------------------------------------- banners
function renderBanners() {
  const host = clear($('banners'));
  if (!store.state || store.route === 'welcome') return;
  const up = store.state.update;
  if (up && up.version && store.ui.dismissedUpdate !== up.version) {
    const apply = btn({ label: t('update.apply'), iconName: 'download', kind: 'primary', size: 'sm' });
    apply.addEventListener('click', async () => {
      const active = [...store.jobs.values()].filter(isActive).length;
      if (active) {
        const yes = await confirmDialog({ title: t('update.active_title'), message: t('update.active_body', { count: active }), confirmLabel: t('update.apply'), cancelLabel: t('update.wait') });
        if (!yes) return;
      }
      apply.disabled = true;
      apply.classList.add('busy');
      toast(t('update.applying'), { kind: 'info', timeout: 8000 });
      const res = await api.apply_app_update();
      apply.classList.remove('busy');
      if (!res.ok) {
        apply.disabled = false;
        showError(res.error, 'update.failed');
      }
    });
    host.append(h('div', { class: 'banner banner-update', role: 'status' },
      h('span', { class: 'banner-icon' }, icon('sparkles', { size: 18 })),
      h('span', { class: 'banner-text' }, h('strong', { text: t('update.title') }), ' ', h('span', { text: t('update.text', { v: up.version }) })),
      btn({ label: t('update.notes'), kind: 'link', size: 'sm', onClick: () => openPage('releases') }),
      apply,
      btn({ label: t('common.dismiss'), iconName: 'x', kind: 'ghost', size: 'sm', iconOnly: true, onClick: () => { store.ui.dismissedUpdate = up.version; renderBanners(); } })));
  }
  const notice = store.state.notice;
  const noticeText = notice ? notice[getLang()] || notice.es || notice.en : null;
  if (noticeText && store.ui.dismissedNotice !== noticeText) {
    host.append(h('div', { class: 'banner banner-notice', role: 'status' },
      h('span', { class: 'banner-icon' }, icon('megaphone', { size: 18 })),
      h('span', { class: 'banner-text', text: noticeText }),
      btn({ label: t('common.dismiss'), iconName: 'x', kind: 'ghost', size: 'sm', iconOnly: true, onClick: () => { store.ui.dismissedNotice = noticeText; renderBanners(); } })));
  }
  const e = store.engine;
  if (e && !e.ready && !store.state.first_run) {
    const s = engineState();
    const failed = Object.entries(e.components || {}).filter(([, c]) => c.state === 'error' || c.state === 'blocked');
    let text;
    let action = null;
    if (s === 'busy') {
      const p = engineProgress(e);
      text = p !== null ? t('engine.banner_busy_pct', { pct: formatPercent(p) }) : t('engine.banner_busy');
    } else if (failed.length) {
      text = failed.some(([, c]) => c.state === 'blocked') ? t('engine.banner_blocked') : t('engine.banner_error');
      action = btn({ label: t('engine.repair'), iconName: 'wrench', kind: 'primary', size: 'sm', onClick: async () => {
        const res = await api.engine_repair(null);
        if (!res.ok) showError(res.error);
      } });
    } else {
      text = t('engine.banner_missing');
      action = btn({ label: t('engine.setup_now'), iconName: 'download', kind: 'primary', size: 'sm', onClick: async () => {
        const res = await api.engine_setup();
        if (!res.ok) showError(res.error);
      } });
    }
    host.append(h('div', { class: ['banner', s === 'busy' ? 'banner-engine' : 'banner-warning'], role: 'status' },
      h('span', { class: 'banner-icon' }, s === 'busy' ? h('span', { class: 'spinner' }) : icon('alert', { size: 18 })),
      h('span', { class: 'banner-text', text }),
      action,
      btn({ label: t('engine.see_components'), kind: 'link', size: 'sm', onClick: () => navigate('settings', { section: 'components' }) })));
  }
}

let bannerQueued = false;
function queueBanners() {
  if (bannerQueued) return;
  bannerQueued = true;
  setTimeout(() => {
    bannerQueued = false;
    renderBanners();
  }, 120);
}

// ---------------------------------------------------------------- close confirmation (confirm_close event)
async function onConfirmClose(active) {
  if (closeModalOpen) return;
  closeModalOpen = true;
  closeMenu();
  const choice = await openModal({
    title: t('close.title'),
    body: t('close.body', { count: active || 1 }),
    iconName: 'alert',
    tone: 'warning',
    dismissValue: 'stay',
    actions: [
      { label: t('close.quit'), kind: 'danger', value: 'quit' },
      { label: t('close.stay'), kind: 'primary', value: 'stay' },
    ],
  });
  closeModalOpen = false;
  if (choice === 'quit') {
    const res = await api.confirm_close();
    if (!res.ok) showError(res.error);
  }
}

// ---------------------------------------------------------------- incoming links (protocol / second instance)
function onIncoming(url) {
  const urls = coerceUrl(url);
  if (!urls.length) return;
  if (store.route === 'welcome') {
    pendingIncoming = urls[0];
    return;
  }
  navigate('home');
  // Always show the preview first; never download without a click.
  views.home.fillAndProbe(urls[0]);
}

// ---------------------------------------------------------------- global input
let pasteSeen = false;

function installGlobalHandlers() {
  document.addEventListener('keydown', (e) => {
    if (isModalOpen()) return;
    const key = e.key;
    if (e.ctrlKey && !e.altKey && (key === ',' || e.code === 'Comma')) {
      e.preventDefault();
      if (store.route !== 'welcome') navigate('settings');
      return;
    }
    if (e.ctrlKey && !e.altKey && (key === 'l' || key === 'L')) {
      if (store.route === 'welcome') return;
      e.preventDefault();
      navigate('home');
      views.home.focusInput();
      return;
    }
    if (e.ctrlKey && (key === 'f' || key === 'F') && store.route === 'history') {
      e.preventDefault();
      views.history?.focusSearch();
      return;
    }
    if (key === 'Escape') {
      if (closeMenu()) return;
      if (store.route === 'home' && views.home?.escape()) e.preventDefault();
      return;
    }
    if ((key === 'Delete' || key === 'Supr') && !isEditable(e.target)) {
      const card = e.target instanceof Element ? e.target.closest('.job') : null;
      if (card) {
        e.preventDefault();
        removeJob(store.jobs.get(card.dataset.id));
      }
      return;
    }
    if (e.ctrlKey && (key === 'v' || key === 'V') && !isEditable(e.target) && store.route !== 'welcome') {
      // The paste event below normally handles it; fall back to the backend clipboard if it doesn't fire.
      pasteSeen = false;
      setTimeout(async () => {
        if (pasteSeen) return;
        const res = await api.read_clipboard();
        if (res.ok && res.url) {
          navigate('home');
          views.home.analyzeText(res.url, { user: true });
        }
      }, 160);
    }
  });

  document.addEventListener('paste', (e) => {
    pasteSeen = true;
    if (isEditable(e.target) || isModalOpen() || store.route === 'welcome') return;
    const text = e.clipboardData ? e.clipboardData.getData('text') : '';
    if (!text) return;
    e.preventDefault();
    if (coerceUrl(text).length) {
      navigate('home');
      views.home.analyzeText(text, { user: true });
    } else {
      toast(t('home.not_a_link'), { kind: 'info' });
    }
  });

  // Drag & drop: WebView2 would navigate to a dropped link unless every dragover/drop is prevented.
  let depth = 0;
  const setDragging = (on) => document.body.classList.toggle('dragging', on);
  document.addEventListener('dragenter', (e) => {
    e.preventDefault();
    if (store.route === 'welcome') return;
    depth++;
    setDragging(true);
  });
  document.addEventListener('dragleave', (e) => {
    e.preventDefault();
    depth = Math.max(0, depth - 1);
    if (!depth) setDragging(false);
  });
  document.addEventListener('dragover', (e) => {
    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = store.route === 'welcome' ? 'none' : 'copy';
  });
  document.addEventListener('drop', (e) => {
    e.preventDefault();
    depth = 0;
    setDragging(false);
    if (store.route === 'welcome' || isModalOpen()) return;
    const dt = e.dataTransfer;
    if (!dt) return;
    const text = dt.getData('text/uri-list') || dt.getData('text/plain') || dt.getData('URL') || '';
    if (coerceUrl(text).length) {
      navigate('home');
      views.home.analyzeText(text, { user: true });
    } else if (dt.files && dt.files.length) {
      toast(t('home.drop_file'), { kind: 'info' });
    } else {
      toast(t('home.not_a_link'), { kind: 'info' });
    }
  });

  window.addEventListener('focus', () => {
    if (store.route === 'home') views.home?.checkClipboard();
  });
}

// ---------------------------------------------------------------- store subscriptions
function installStoreHandlers() {
  store.on('route', (route, opts) => {
    showRoute(route, opts || {});
    renderBanners();
  });
  store.on('lang', (lang) => {
    setLang(lang);
    if (!booted) return;
    const route = store.route;
    destroyViews();
    buildRail();
    showRoute(route, { keepScroll: true });
    renderBanners();
  });
  store.on('settings', () => {
    applyTheme();
    applyMotion();
  });
  store.on('engine', () => {
    syncEngineChip();
    queueBanners();
  });
  store.on('update', queueBanners);
  store.on('notice', queueBanners);
  store.on('state', queueBanners);
  store.on('job', syncRailBadge);
  store.on('job_removed', syncRailBadge);
  store.on('incoming_url', onIncoming);
  store.on('confirm_close', onConfirmClose);
}

// ---------------------------------------------------------------- boot
function fatal(err) {
  const box = $('views');
  clear(box);
  $('app').classList.add('onboarding');
  const texts = (err && err.texts && err.texts[getLang()]) || { title: t('errors.boot_title'), message: t('errors.boot_text') };
  box.append(h('section', { class: 'view fatal' },
    h('div', { class: 'onb-card' },
      h('div', { class: 'onb-icon onb-icon-danger' }, icon('alertCircle', { size: 28 })),
      h('h1', { class: 'onb-title', text: texts.title || t('errors.boot_title') }),
      h('p', { class: 'onb-lead', text: texts.message || t('errors.boot_text') }),
      err && err.details ? h('pre', { class: 'job-error-details', text: err.details }) : null,
      h('div', { class: 'onb-foot onb-foot-center' }, btn({ label: t('erroraction.retry'), iconName: 'retry', kind: 'primary', onClick: () => location.reload() })))));
  hideBoot();
}

function hideBoot() {
  const boot = $('boot');
  $('app').removeAttribute('data-booting');
  if (boot) {
    boot.classList.add('gone');
    setTimeout(() => boot.remove(), 400);
  }
}

async function boot() {
  await loadDictionaries();
  setLang((navigator.language || 'es').toLowerCase().startsWith('es') ? 'es' : 'en');
  await connect();
  const res = await api.ui_ready();
  if (!res.ok || !res.state) {
    fatal(res.error);
    return;
  }
  installStoreHandlers();
  setState(res.state);
  setLang(store.lang);
  applyTheme();
  applyMotion();
  buildRail();
  installGlobalHandlers();

  const scenario = mockScenario();
  const firstRun = Boolean(store.state.first_run) || !store.settings?.first_run_done;
  const route = firstRun ? 'welcome' : (scenario && scenario.route) || 'home';
  navigate(route, { force: true });
  booted = true;
  renderBanners();
  hideBoot();

  if (!firstRun && route === 'home') {
    if (scenario && scenario.autoProbe && scenario.probeUrl) views.home.probeUrl(scenario.probeUrl);
    else {
      views.home.checkClipboard();
      if (!document.activeElement || document.activeElement === document.body) views.home.focusInput();
    }
  }
}

boot().catch((err) => {
  console.error('[boot]', err);
  fatal({ details: String((err && err.stack) || err) });
});
