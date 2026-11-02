// S8 settings: General, Descargas, Componentes, Avanzado, Acerca de.
// Controls save immediately with save_settings(patch); every control re-syncs on 'settings' events.
import { h, btn, clear, setText } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';
import { store } from '../store.js';
import { api } from '../api.js';
import { saveSettings, chooseFolder, chooseCookiesFile, clearCookiesFile, openPage, showError } from '../actions.js';
import { toast } from '../ui/feedback.js';
import { createComponentRows } from './components.js';

const SECTIONS = ['general', 'downloads', 'components', 'advanced', 'about'];

export function createSettings() {
  const syncers = [];
  const unsubs = [];
  const s = () => store.settings || {};

  // ---------------------------------------------------------------- controls
  function segmented(key, options, { label, onPick } = {}) {
    const seg = h('div', { class: 'segmented', role: 'radiogroup', attrs: { 'aria-label': label || key } });
    const buttons = options.map((o, i) => {
      const b = h('button', {
        class: 'seg', type: 'button', role: 'radio', dataset: { value: String(o.value) },
        on: {
          click: () => pick(o.value),
          keydown: (e) => {
            const d = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
            if (!d) return;
            e.preventDefault();
            const next = options[(i + d + options.length) % options.length];
            pick(next.value);
            buttons[(i + d + options.length) % options.length].focus();
          },
        },
      }, o.iconName ? icon(o.iconName, { size: 15 }) : null, h('span', { text: o.label }));
      seg.append(b);
      return b;
    });
    function pick(value) {
      if (onPick) onPick(value);
      else if (s()[key] !== value) saveSettings({ [key]: value });
      paint(value);
    }
    function paint(value) {
      for (const b of buttons) {
        const on = b.dataset.value === String(value);
        b.setAttribute('aria-checked', String(on));
        b.tabIndex = on ? 0 : -1;
      }
    }
    syncers.push(() => paint(s()[key]));
    paint(s()[key]);
    return seg;
  }

  function switchControl(key, labelId) {
    const input = h('input', { type: 'checkbox', class: 'switch', role: 'switch', attrs: { 'aria-labelledby': labelId } });
    input.checked = Boolean(s()[key]);
    input.addEventListener('change', () => saveSettings({ [key]: input.checked }));
    syncers.push(() => { input.checked = Boolean(s()[key]); });
    return input;
  }

  let rowId = 0;
  function row(titleKey, descKey, control, { stack = false } = {}) {
    const id = `set-${++rowId}`;
    const ctrl = typeof control === 'function' ? control(id) : control;
    return h('div', { class: ['set-row', stack ? 'set-row-stack' : ''] },
      h('div', { class: 'set-text' },
        h('span', { class: 'set-title', id, text: t(titleKey) }),
        descKey ? h('span', { class: 'set-desc', text: t(descKey) }) : null),
      h('div', { class: 'set-control' }, ctrl));
  }

  function switchRow(key, titleKey, descKey) {
    const r = row(titleKey, descKey, (id) => switchControl(key, id));
    r.classList.add('set-row-switch');
    r.addEventListener('click', (e) => {
      if (e.target.closest('.set-control')) return;
      const input = r.querySelector('input.switch');
      input.click();
    });
    return r;
  }

  function section(key, iconName, children) {
    return h('section', { class: 'set-section card', id: `set-${key}`, dataset: { section: key }, attrs: { 'aria-labelledby': `set-h-${key}` } },
      h('h2', { class: 'set-section-title', id: `set-h-${key}` }, icon(iconName, { size: 18 }), h('span', { text: t(`settings.section.${key}`) })),
      children);
  }

  // ---------------------------------------------------------------- General
  const updateBtn = btn({ label: t('settings.check_updates_now'), iconName: 'refresh', kind: 'secondary', size: 'sm' });
  updateBtn.addEventListener('click', async () => {
    updateBtn.disabled = true;
    updateBtn.classList.add('busy');
    const res = await api.check_app_update();
    updateBtn.disabled = false;
    updateBtn.classList.remove('busy');
    if (!res.ok) return showError(res.error);
    if (res.update) {
      store.state.update = res.update;
      store.ui.dismissedUpdate = null;
      store.emit('update', res.update);
      toast(t('update.available', { v: res.update.version }), { kind: 'info' });
    } else {
      toast(t('settings.up_to_date', { v: store.state?.version || '' }), { kind: 'success' });
    }
  });

  const general = section('general', 'settings', [
    row('settings.language', 'settings.language_desc', segmented('language', [
      { value: 'auto', label: t('settings.lang_auto') }, { value: 'es', label: 'Español' }, { value: 'en', label: 'English' },
    ], { label: t('settings.language') }), { stack: true }),
    row('settings.theme', null, segmented('theme', [
      { value: 'system', label: t('settings.theme_system'), iconName: 'monitor' },
      { value: 'light', label: t('settings.theme_light'), iconName: 'sun' },
      { value: 'dark', label: t('settings.theme_dark'), iconName: 'moon' },
    ], { label: t('settings.theme') }), { stack: true }),
    switchRow('reduce_motion', 'settings.reduce_motion', 'settings.reduce_motion_desc'),
    switchRow('clipboard_suggest', 'settings.clipboard', 'settings.clipboard_desc'),
    switchRow('notify_on_finish', 'settings.notify', 'settings.notify_desc'),
    switchRow('open_folder_on_finish', 'settings.open_folder', 'settings.open_folder_desc'),
    switchRow('check_app_updates', 'settings.check_updates', 'settings.check_updates_desc'),
    h('div', { class: 'set-actions' }, updateBtn),
  ]);

  // ---------------------------------------------------------------- Descargas
  const folderPath = h('span', { class: 'dest-path' });
  syncers.push(() => {
    setText(folderPath, s().download_dir || '');
    folderPath.title = s().download_dir || '';
  });
  const filenamePreview = h('code', { class: 'filename-example' });
  const exampleFor = (style) => (style === 'legacy_k' ? 'K-20261009_143205.mp4' : style === 'simple' ? 'Big_Buck_Bunny_60fps_4K_[aqz-KE-bpKQ].mp4' : 'Big Buck Bunny 60fps 4K [aqz-KE-bpKQ].mp4');
  syncers.push(() => setText(filenamePreview, exampleFor(s().filename_style)));

  const downloads = section('downloads', 'download', [
    row('settings.folder', null, h('div', { class: 'dest' },
      h('span', { class: 'dest-pill' }, icon('folder', { size: 16 }), folderPath),
      btn({ label: t('common.change'), iconName: 'folderOpen', kind: 'secondary', size: 'sm', onClick: () => chooseFolder() })), { stack: true }),
    row('settings.favorite', 'settings.favorite_desc', segmented('favorite_preset', [
      { value: 'recommended', label: t('option.recommended.name') }, { value: 'max', label: t('option.max.name') },
      { value: 'mp3', label: 'MP3' }, { value: 'm4a', label: 'M4A' },
    ], { label: t('settings.favorite') }), { stack: true }),
    switchRow('quick_download', 'settings.quick', 'settings.quick_desc'),
    row('settings.filename', 'settings.filename_desc', h('div', { class: 'stack-2' },
      segmented('filename_style', [
        { value: 'title_id', label: t('settings.filename_title_id') },
        { value: 'legacy_k', label: t('settings.filename_legacy') },
        { value: 'simple', label: t('settings.filename_simple') },
      ], { label: t('settings.filename') }),
      filenamePreview), { stack: true }),
    row('settings.concurrent', 'settings.concurrent_desc', segmented('max_concurrent', [1, 2, 3, 4].map((n) => ({ value: n, label: String(n) })), {
      label: t('settings.concurrent'),
      onPick: (v) => { if (s().max_concurrent !== Number(v)) saveSettings({ max_concurrent: Number(v) }); },
    })),
  ]);

  // ---------------------------------------------------------------- Componentes
  const rows = createComponentRows('settings');
  const engineUpdateBtn = btn({ label: t('engine.update_now'), iconName: 'refresh', kind: 'secondary', size: 'sm' });
  engineUpdateBtn.addEventListener('click', async () => {
    const res = await api.engine_update(true);
    if (!res.ok) return showError(res.error);
    toast(t('engine.updating_toast'), { kind: 'info' });
  });
  const repairAllBtn = btn({ label: t('engine.repair_all'), iconName: 'wrench', kind: 'ghost', size: 'sm' });
  repairAllBtn.addEventListener('click', async () => {
    const res = await api.engine_repair(null);
    if (!res.ok) return showError(res.error);
    toast(t('engine.repairing_toast'), { kind: 'info' });
  });
  const engineSummary = h('p', { class: 'set-desc' });
  function syncEngine() {
    const e = store.engine;
    if (!e) return;
    rows.update(e);
    engineUpdateBtn.disabled = Boolean(e.busy);
    repairAllBtn.disabled = Boolean(e.busy);
    setText(engineSummary, e.busy ? t('engine.summary_busy') : e.ready ? t('engine.summary_ready') : t('engine.summary_missing'));
  }
  const components = section('components', 'package', [
    h('div', { class: 'set-row set-row-stack' },
      h('div', { class: 'set-text' }, h('span', { class: 'set-title', text: t('engine.title') }), engineSummary),
      h('div', { class: 'set-actions' }, engineUpdateBtn, repairAllBtn)),
    rows.el,
    row('settings.channel', 'settings.channel_desc', segmented('engine_channel', [
      { value: 'nightly', label: t('settings.channel_nightly') }, { value: 'stable', label: t('settings.channel_stable') },
    ], { label: t('settings.channel') }), { stack: true }),
    switchRow('auto_update_engine', 'settings.auto_engine', 'settings.auto_engine_desc'),
  ]);

  // ---------------------------------------------------------------- Avanzado
  const cookieStatus = h('span', { class: 'cookie-status' });
  const cookieClear = btn({ label: t('cookies.clear'), iconName: 'trash', kind: 'ghost', size: 'sm', onClick: () => clearCookiesFile() });
  syncers.push(() => {
    const file = s().cookies_file;
    clear(cookieStatus);
    if (file) {
      cookieStatus.append(icon('cookie', { size: 15 }), h('span', { class: 'dest-path', text: file, attrs: { title: file } }));
    } else {
      cookieStatus.append(h('span', { class: 'muted', text: t('cookies.none') }));
    }
    cookieClear.hidden = !file;
  });
  const advanced = section('advanced', 'wrench', [
    row('cookies.title', 'cookies.desc', h('div', { class: 'stack-2' },
      h('div', { class: 'callout callout-warning' }, icon('alert', { size: 18 }), h('p', { text: t('cookies.risk_inline') })),
      h('div', { class: 'cookie-row' }, cookieStatus,
        btn({ label: t('cookies.choose'), iconName: 'folderOpen', kind: 'secondary', size: 'sm', onClick: () => chooseCookiesFile() }),
        cookieClear)), { stack: true }),
    row('settings.codec', 'settings.codec_desc', segmented('max_codec', [
      { value: 'vp9', label: t('settings.codec_vp9') }, { value: 'av1', label: t('settings.codec_av1') },
    ], { label: t('settings.codec') }), { stack: true }),
  ]);

  // ---------------------------------------------------------------- Acerca de
  const versionText = h('span', { class: 'num' });
  syncers.push(() => setText(versionText, t('about.version', { v: store.state?.version || '' })));
  const link = (key, iconName, labelKey) => btn({ label: t(labelKey), iconName, kind: 'secondary', size: 'sm', onClick: () => openPage(key) });
  const about = section('about', 'info', [
    h('div', { class: 'about-head' },
      h('img', { src: 'assets/logo.svg', alt: '', width: 56, height: 56, attrs: { draggable: 'false' } }),
      h('div', {},
        h('strong', { class: 'about-name', text: 'K-Downloader' }),
        h('div', { class: 'muted' }, versionText, ' · ', h('span', { text: t('about.license') })))),
    h('div', { class: 'about-links' },
      link('home', 'globe', 'about.link_home'),
      link('github', 'external', 'about.link_github'),
      link('releases', 'sparkles', 'about.link_releases'),
      link('issues', 'bug', 'about.link_issues'),
      link('responsible', 'shield', 'about.link_responsible'),
      link('donate', 'heart', 'about.link_donate')),
    h('div', { class: 'responsible-card' },
      h('div', { class: 'responsible-head' }, icon('shield', { size: 18 }), h('strong', { text: t('onb.responsible_title') })),
      h('ul', { class: 'responsible-list' }, ['onb.responsible_1', 'onb.responsible_2', 'onb.responsible_3'].map((k) => h('li', { text: t(k) })))),
    h('p', { class: 'muted small', text: t('about.not_affiliated') }),
    h('p', { class: 'muted small', text: t('about.credits') }),
  ]);

  // ---------------------------------------------------------------- page + section chips
  const nav = h('nav', { class: 'chips set-nav', attrs: { 'aria-label': t('settings.sections') } });
  const chipFor = {};
  for (const key of SECTIONS) {
    const chip = h('button', { class: 'chip chip-filter', type: 'button', attrs: { 'aria-pressed': 'false' }, on: { click: () => jump(key) } }, h('span', { text: t(`settings.section.${key}`) }));
    chipFor[key] = chip;
    nav.append(chip);
  }
  function mark(key) {
    store.ui.settingsSection = key;
    for (const [k, c] of Object.entries(chipFor)) c.setAttribute('aria-pressed', String(k === key));
  }
  const sectionsEl = { general, downloads, components, advanced, about };
  function jump(key, smooth = true) {
    mark(key);
    sectionsEl[key]?.scrollIntoView({ block: 'start', behavior: smooth ? 'smooth' : 'auto' });
  }

  const header = h('header', { class: 'page-head sticky-head' }, h('h1', { class: 'page-title', text: t('settings.title') }), nav);
  const el = h('section', { class: 'view view-settings', attrs: { 'aria-label': t('settings.title') } },
    header, h('div', { class: 'content stack-4' }, general, downloads, components, advanced, about));

  // Highlight the section in view while scrolling.
  let observer = null;
  if ('IntersectionObserver' in window) {
    observer = new IntersectionObserver((entries) => {
      const visible = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
      if (visible[0]) mark(visible[0].target.dataset.section);
    }, { rootMargin: '-120px 0px -55% 0px' });
    Object.values(sectionsEl).forEach((sec) => observer.observe(sec));
  }

  const syncAll = () => syncers.forEach((fn) => fn());
  unsubs.push(store.on('settings', syncAll));
  unsubs.push(store.on('engine', syncEngine));
  syncAll();
  syncEngine();
  mark(store.ui.settingsSection || 'general');

  return {
    el,
    destroy() {
      unsubs.forEach((u) => u());
      observer?.disconnect();
    },
    show(opts = {}) {
      if (opts.section) requestAnimationFrame(() => jump(opts.section, false));
    },
  };
}
