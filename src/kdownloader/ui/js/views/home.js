// S1 home (URL bar, clipboard chip, empty state), S2 analyzing skeleton, S3 media card.
// Embeds the downloads list (S4-S6) below the card.
import { h, btn, clear, setText, toggle, thumbImg } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';
import { store } from '../store.js';
import { api } from '../api.js';
import {
  formatSize, formatDuration, formatLongDuration, optionLabel, optionName, heightLabel, codecLabel, shortUrl,
} from '../format.js';
import { toast, announce } from '../ui/feedback.js';
import {
  coerceUrl, hasListParam, isPlaylistPage, guessVideoId, saveSettings, chooseFolder, chooseCookiesFile,
  errorTexts, showError, openHelp, copyText, navigate, openPage,
} from '../actions.js';
import { createDownloads } from './downloads.js';

const FAVORITES = ['recommended', 'max', 'mp3', 'm4a'];
const TILE_ICONS = { recommended: 'sparkles', max: 'zap', mp3: 'music' };
const SLOW_PROBE_MS = 20000;
const SITES = ['YouTube', 'TikTok', 'Instagram', 'X', 'Facebook', 'Vimeo', 'SoundCloud', 'Twitch', 'Reddit'];

// yt-dlp replaces characters Windows forbids with full-width look-alikes.
const WIN_SAFE = { '<': '＜', '>': '＞', ':': '：', '"': '＂', '/': '⧸', '\\': '⧹', '|': '｜', '?': '？', '*': '＊' };

function truncateBytes(text, maxBytes) {
  const enc = new TextEncoder();
  if (enc.encode(text).length <= maxBytes) return text;
  let out = '';
  for (const ch of text) {
    if (enc.encode(out + ch).length > maxBytes) break;
    out += ch;
  }
  return out;
}

function filenamePreview(media, opt, style) {
  const ext = opt ? opt.container : 'mp4';
  if (style === 'legacy_k') {
    const d = new Date();
    const p = (n) => String(n).padStart(2, '0');
    return `K-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.${ext}`;
  }
  const id = media.id || guessVideoId(media.url) || '…';
  let base = `${truncateBytes(media.title || '', 120)} [${id}]`;
  if (style === 'simple') {
    base = base.normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/\s+/g, '_').replace(/[^\w.\-[\]]/g, '').replace(/_+/g, '_');
  } else {
    base = base.replace(/[<>:"/\\|?*]/g, (c) => WIN_SAFE[c]);
  }
  return `${base}.${ext}`;
}

function allOptions(media) {
  return [...(media.options || []), ...(media.more || [])];
}

function findOption(media, key) {
  return allOptions(media).find((o) => o.key === key) || null;
}

function defaultSelection(media) {
  const fav = store.settings?.favorite_preset;
  const favOpt = fav ? findOption(media, fav) : null;
  if (favOpt && favOpt.available !== false) return fav;
  const first = allOptions(media).find((o) => o.available !== false);
  return first ? first.key : null;
}

export function createHome() {
  const st = store.ui.home;
  const unsubs = [];
  let slowTimer = 0;
  let clipUrl = null;

  // ---------------------------------------------------------------- URL bar
  const input = h('input', {
    class: 'url-input', type: 'text', id: 'url-input', placeholder: t('home.placeholder'), value: st.input || '',
    attrs: { inputmode: 'url', autocomplete: 'off', spellcheck: 'false', 'aria-label': t('home.input_label'), enterkeyhint: 'go' },
  });
  const clearInput = btn({ label: t('home.clear_input'), iconName: 'x', kind: 'ghost', iconOnly: true, cls: 'url-clear', onClick: () => {
    input.value = '';
    st.input = '';
    syncInputButtons();
    input.focus();
  } });
  const pasteBtn = btn({ label: t('home.paste'), iconName: 'paste', kind: 'soft', cls: 'paste-btn', onClick: () => pasteFromClipboard() });
  const goBtn = btn({ label: t('home.analyze'), iconName: 'arrowRight', kind: 'primary', cls: 'go-btn', onClick: () => analyzeText(input.value, { user: true }) });
  const bar = h('div', { class: 'urlbar' }, h('span', { class: 'url-icon' }, icon('link', { size: 20 })), input, clearInput, pasteBtn, goBtn);
  const hint = h('div', { class: 'url-hint', role: 'alert', hidden: true });
  const chipSlot = h('div', { class: 'clip-slot' });
  const quickNote = h('div', { class: 'quick-note', hidden: true });
  const meta = h('div', { class: 'urlbar-meta' }, hint, chipSlot, quickNote);
  const donateBtn = btn({ label: t('nav.donate'), iconName: 'heart', kind: 'secondary', iconOnly: true, cls: 'donate-btn', onClick: () => openPage('donate') });
  const barRow = h('div', { class: 'urlbar-row' }, bar, donateBtn);
  const urlArea = h('div', { class: 'url-area' }, h('div', { class: 'aurora', attrs: { 'aria-hidden': 'true' } }), barRow, meta);

  input.addEventListener('input', () => {
    st.input = input.value;
    hint.hidden = true;
    syncInputButtons();
  });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      analyzeText(input.value, { user: true });
    } else if (e.key === 'Escape' && input.value && st.stage === 'idle') {
      e.preventDefault();
      e.stopPropagation();
      input.value = '';
      st.input = '';
      syncInputButtons();
    }
  });
  input.addEventListener('paste', (e) => {
    // Pasting into the empty bar analyzes right away (like Ctrl+V anywhere).
    const text = e.clipboardData ? e.clipboardData.getData('text') : '';
    if (!input.value.trim() && coerceUrl(text).length) {
      e.preventDefault();
      analyzeText(text, { user: true });
    }
  });

  function syncInputButtons() {
    const value = input.value.trim();
    const has = Boolean(value);
    clearInput.hidden = !has;
    // No "Analizar" while the bar already shows the link being analyzed / previewed.
    goBtn.hidden = !has || (value === st.url && (st.stage === 'probing' || st.stage === 'media'));
  }

  function renderQuickNote() {
    const s = store.settings;
    const on = Boolean(s && s.quick_download);
    quickNote.hidden = !on;
    clear(quickNote);
    if (on) {
      quickNote.append(icon('zap', { size: 14 }),
        h('span', { text: t('home.quick_on', { option: optionName(s.favorite_preset) }) }),
        btn({ label: t('home.quick_off'), kind: 'link', size: 'sm', onClick: () => saveSettings({ quick_download: false }) }));
    }
  }

  // ---------------------------------------------------------------- clipboard chip
  function hideChip() {
    clear(chipSlot);
    clipUrl = null;
    pasteBtn.classList.remove('pulse');
  }

  function showChip(url) {
    clipUrl = url;
    clear(chipSlot);
    const chip = h('div', { class: 'clip-chip' },
      icon('clipboard', { size: 15 }),
      h('span', { class: 'clip-label', text: t('home.clip_found') }),
      h('span', { class: 'clip-url', text: shortUrl(url), attrs: { title: url } }),
      btn({ label: t('home.clip_use'), kind: 'link', size: 'sm', onClick: () => {
        hideChip();
        analyzeText(url, { user: true });
      } }),
      btn({ label: t('home.clip_dismiss'), iconName: 'x', kind: 'ghost', size: 'sm', iconOnly: true, onClick: () => {
        store.ui.clipboardSuggested = url;
        hideChip();
      } }));
    chipSlot.append(chip);
    pasteBtn.classList.add('pulse');
  }

  async function checkClipboard() {
    const s = store.settings;
    if (!s || !s.clipboard_suggest || store.route !== 'home' || st.stage === 'probing') return;
    const res = await api.read_clipboard();
    if (!res.ok || !res.url) {
      hideChip();
      return;
    }
    const url = coerceUrl(res.url)[0];
    if (!url || url === store.ui.clipboardSuggested || url === input.value.trim() || url === st.url) {
      if (url !== clipUrl) hideChip();
      return;
    }
    showChip(url);
  }

  async function pasteFromClipboard() {
    const res = await api.read_clipboard();
    const url = res.ok && res.url ? coerceUrl(res.url)[0] : null;
    if (!url) {
      toast(t('home.clip_empty'), { kind: 'info' });
      input.focus();
      return;
    }
    hideChip();
    analyzeText(url, { user: true });
  }

  // ---------------------------------------------------------------- analyze / probe
  function showHint(text) {
    setText(hint, text);
    hint.hidden = false;
    bar.classList.remove('shake');
    void bar.offsetWidth;
    bar.classList.add('shake');
  }

  /** text from the bar, a paste, a drop or the clipboard chip. */
  function analyzeText(text, { user = false, incoming = false } = {}) {
    const urls = coerceUrl(text);
    if (!urls.length) {
      if (String(text || '').trim()) showHint(t('home.not_a_link'));
      else input.focus();
      return;
    }
    hint.hidden = true;
    const url = urls[0];
    input.value = url;
    st.input = url;
    syncInputButtons();
    store.ui.clipboardSuggested = url;
    if (clipUrl === url) hideChip();
    if (urls.length > 1) toast(t('home.many_links', { count: urls.length }), { kind: 'info', timeout: 6000 });
    if (user && !incoming && store.settings?.quick_download) return quickDownload(url);
    return probe(url);
  }

  async function probe(url, { scope } = {}) {
    const seq = ++st.seq;
    st.url = url;
    st.scope = scope || (isPlaylistPage(url) ? 'all' : 'one');
    st.stage = 'probing';
    st.quick = false;
    st.error = null;
    st.media = null;
    if (!scope) {
      st.singleMedia = null;
      st.listMedia = null;
    }
    st.probeStarted = Date.now();
    rerender();
    const res = await api.probe(url, st.scope === 'all');
    if (seq !== st.seq) return; // canceled or superseded
    clearTimeout(slowTimer);
    if (res.ok && res.media) {
      const media = res.media;
      if (st.scope === 'all' || media.is_playlist) st.listMedia = media;
      else st.singleMedia = media;
      st.media = media;
      st.stage = 'media';
      st.selected = defaultSelection(media);
      st.moreOpen = !findOption({ options: media.options || [], more: [] }, st.selected) && Boolean(st.selected);
      announce(t('a11y.analyzed', { title: media.title || '' }));
    } else if (res.error && res.error.code === 'canceled') {
      st.stage = 'idle';
    } else {
      st.stage = 'error';
      st.error = res.error;
      announce(errorTexts(res.error).title);
    }
    rerender();
  }

  async function quickDownload(url) {
    const seq = ++st.seq;
    st.url = url;
    st.stage = 'probing';
    st.quick = true;
    st.error = null;
    st.media = null;
    st.probeStarted = Date.now();
    rerender();
    const res = await api.quick_download(url);
    if (seq !== st.seq) return;
    clearTimeout(slowTimer);
    if (res.ok) {
      toast(t('home.quick_started', { option: optionName(store.settings.favorite_preset) }), {
        kind: 'success',
        action: { label: t('home.quick_off'), fn: () => saveSettings({ quick_download: false }) },
      });
      resetCard({ clearInput: true });
    } else if (res.error && res.error.code === 'canceled') {
      st.stage = 'idle';
      rerender();
    } else {
      st.stage = 'error';
      st.error = res.error;
      rerender();
    }
  }

  function cancelProbe() {
    st.seq++;
    clearTimeout(slowTimer);
    api.cancel_probe();
    if (st.scope === 'all' && st.singleMedia) {
      st.scope = 'one';
      st.media = st.singleMedia;
      st.stage = 'media';
    } else {
      st.stage = 'idle';
    }
    rerender();
    input.focus();
  }

  function resetCard({ clearInput = false } = {}) {
    st.seq++;
    st.stage = 'idle';
    st.media = null;
    st.singleMedia = null;
    st.listMedia = null;
    st.error = null;
    st.url = null;
    st.quick = false;
    if (clearInput) {
      input.value = '';
      st.input = '';
      syncInputButtons();
    }
    rerender();
  }

  async function setScope(scope) {
    if (scope === st.scope || st.stage !== 'media') return;
    if (scope === 'all') {
      if (st.listMedia) {
        st.scope = 'all';
        st.media = st.listMedia;
        st.selected = defaultSelection(st.media);
        rerender();
        return;
      }
      await probe(st.url, { scope: 'all' });
      if (st.stage === 'error' && st.singleMedia) {
        showError(st.error);
        st.scope = 'one';
        st.media = st.singleMedia;
        st.stage = 'media';
        rerender();
      }
    } else {
      st.scope = 'one';
      st.media = st.singleMedia;
      st.selected = defaultSelection(st.media);
      rerender();
    }
  }

  // ---------------------------------------------------------------- probe slot rendering
  const slot = h('div', { class: 'probe-slot' });
  // Re-render through the store: after a language switch the view is rebuilt while a probe may still be
  // pending, and its continuation must repaint the live instance, not the detached one.
  const rerender = () => store.emit('home:render');

  function renderSlot() {
    syncInputButtons();
    clear(slot);
    clearTimeout(slowTimer);
    if (st.stage === 'probing') slot.append(skeletonCard());
    else if (st.stage === 'media' && st.media) slot.append(mediaCard(st.media));
    else if (st.stage === 'error') slot.append(errorCard(st.error));
    refreshEmpty();
  }

  function skeletonCard() {
    const slowNote = h('p', { class: 'probe-slow muted', text: t('home.probe_slow'), hidden: true });
    const label = st.quick ? t('home.quick_preparing') : st.scope === 'all' ? t('home.analyzing_list') : t('home.analyzing');
    const card = h('article', { class: 'card probe-card skeleton enter', attrs: { 'aria-busy': 'true', 'aria-label': label } },
      h('div', { class: 'media-head' },
        h('div', { class: 'media-thumb sk' }),
        h('div', { class: 'media-info' },
          h('div', { class: 'sk sk-line', style: { width: '86%' } }),
          h('div', { class: 'sk sk-line', style: { width: '58%' } }),
          h('div', { class: 'sk-badges' }, h('div', { class: 'sk sk-badge' }), h('div', { class: 'sk sk-badge' })))),
      h('div', { class: 'options' }, h('div', { class: 'sk sk-tile' }), h('div', { class: 'sk sk-tile' }), h('div', { class: 'sk sk-tile' })),
      h('div', { class: 'probe-foot' },
        h('span', { class: 'spinner' }),
        h('div', { class: 'probe-foot-text' },
          h('strong', { text: label }),
          h('span', { class: 'muted probe-url', text: shortUrl(st.url || '', 60), attrs: { title: st.url || '' } })),
        btn({ label: t('common.cancel'), kind: 'secondary', size: 'sm', onClick: cancelProbe })),
      slowNote);
    const elapsed = Date.now() - (st.probeStarted || Date.now());
    slowTimer = setTimeout(() => { slowNote.hidden = false; }, Math.max(0, SLOW_PROBE_MS - elapsed));
    return card;
  }

  function errorCard(err) {
    const texts = errorTexts(err);
    const actionsRow = h('div', { class: 'job-error-actions' });
    const retryProbe = () => (st.url ? (st.quick ? quickDownload(st.url) : probe(st.url)) : null);
    const primary = (() => {
      switch (err && err.action) {
        case 'update_retry':
          return { label: t('erroraction.update_retry'), iconName: 'refresh', run: async () => {
            const res = await api.engine_update(true);
            if (!res.ok) return showError(res.error);
            toast(t('engine.updating_toast'), { kind: 'info' });
            retryProbe();
          } };
        case 'retry':
        case 'retry_later':
          return { label: t('erroraction.retry'), iconName: 'retry', run: retryProbe };
        case 'cookies':
          return { label: t('erroraction.cookies'), iconName: 'cookie', run: async () => { if (await chooseCookiesFile()) retryProbe(); } };
        case 'repair_engine':
          return { label: t('erroraction.repair_engine'), iconName: 'wrench', run: async () => {
            const res = await api.engine_repair(null);
            if (!res.ok) return showError(res.error);
            toast(t('engine.repairing_toast'), { kind: 'info', action: { label: t('engine.see_components'), fn: () => navigate('settings', { section: 'components' }) } });
          } };
        case 'choose_folder':
          return { label: t('erroraction.choose_folder'), iconName: 'folderOpen', run: async () => { if (await chooseFolder()) retryProbe(); } };
        default:
          return null;
      }
    })();
    if (primary) actionsRow.append(btn({ label: primary.label, iconName: primary.iconName, kind: 'primary', size: 'sm', onClick: primary.run }));
    actionsRow.append(btn({ label: t('home.try_other'), kind: 'secondary', size: 'sm', onClick: () => { resetCard({ clearInput: true }); input.focus(); } }));
    if (err && err.details) actionsRow.append(btn({ label: t('jobs.action.copy_details'), iconName: 'copy', kind: 'ghost', size: 'sm', onClick: () => copyText(err.details, 'jobs.details_copied') }));
    if (err && err.code) actionsRow.append(btn({ label: t('jobs.action.help'), iconName: 'help', kind: 'ghost', size: 'sm', onClick: () => openHelp(err.code) }));
    return h('article', { class: 'card probe-card probe-error enter', role: 'alert' },
      h('div', { class: 'job-error-title' }, icon('alertCircle', { size: 18 }), h('strong', { text: texts.title })),
      texts.message ? h('p', { class: 'job-error-msg', text: texts.message }) : null,
      texts.help ? h('p', { class: 'muted small', text: texts.help }) : null,
      actionsRow);
  }

  // ---------------------------------------------------------------- media card (S3)
  function badgesFor(media) {
    const out = [];
    const opts = allOptions(media).filter((o) => o.kind === 'video' && o.available !== false);
    const maxH = Math.max(0, ...opts.map((o) => o.height || 0));
    const maxFps = Math.max(0, ...opts.map((o) => o.fps || 0));
    if (media.is_playlist) out.push(['list', t('badge.playlist')]);
    if (maxH >= 2160) out.push(['k4', '4K']);
    else if (maxH >= 1440) out.push(['qhd', '1440p']);
    if (maxFps >= 50) out.push(['fps', t('badge.fps', { n: Math.round(maxFps) })]);
    if (opts.some((o) => o.hdr)) out.push(['hdr', 'HDR']);
    if (media.is_live) out.push(['live', t('badge.live')]);
    if ((media.age_limit || 0) >= 18) out.push(['age', '+18']);
    return out;
  }

  function mediaCard(media) {
    const settings = store.settings || {};
    const thumbEl = h('div', { class: 'media-thumb' }, thumbImg(media.thumbnail),
      h('span', { class: 'media-thumb-fallback' }, icon(media.is_playlist ? 'listVideo' : 'film', { size: 28 })));
    const dur = media.is_playlist ? '' : formatDuration(media.duration);
    if (dur) thumbEl.append(h('span', { class: 'duration num', text: dur }));
    const badges = h('div', { class: 'badges' }, badgesFor(media).map(([k, label]) => h('span', { class: ['badge', `badge-${k}`], text: label })));
    const sub = [media.uploader, media.site].filter(Boolean).join(' · ');
    const head = h('div', { class: 'media-head' }, thumbEl,
      h('div', { class: 'media-info' },
        h('h2', { class: 'media-title', text: media.title || t('jobs.untitled'), attrs: { title: media.title || '' } }),
        sub ? h('div', { class: 'media-sub' }, icon('user', { size: 14 }), h('span', { text: sub })) : null,
        badges));
    const closeBtn = btn({ label: t('home.close_card'), iconName: 'x', kind: 'ghost', iconOnly: true, cls: 'card-close', onClick: () => resetCard({ clearInput: true }) });

    const parts = [closeBtn, head];

    if (media.degraded) {
      parts.push(h('div', { class: 'callout callout-warning' }, icon('alert', { size: 18 }),
        h('div', {}, h('strong', { text: t('home.degraded_title') }), h('p', { text: t('home.degraded_body') }))));
    }
    if (media.is_live) {
      parts.push(h('div', { class: 'callout callout-info' }, icon('info', { size: 18 }), h('p', { text: t('home.live_note') })));
    }
    if ((media.age_limit || 0) >= 18 && media.is_youtube) {
      parts.push(h('div', { class: 'callout callout-info' }, icon('info', { size: 18 }), h('p', { text: t('home.age_note') })));
    }

    // "Solo este video / Toda la lista"
    if (hasListParam(st.url || media.url || '')) {
      const seg = h('div', { class: 'segmented', role: 'radiogroup', attrs: { 'aria-label': t('home.scope_label') } });
      for (const [key, label] of [['one', t('home.scope_one')], ['all', st.listMedia ? t('home.scope_all_n', { count: st.listMedia.count || 0 }) : t('home.scope_all')]]) {
        seg.append(h('button', {
          class: 'seg', type: 'button', role: 'radio', attrs: { 'aria-checked': String(st.scope === key) },
          on: { click: () => setScope(key) },
        }, h('span', { text: label })));
      }
      parts.push(h('div', { class: 'scope-row' }, icon('listVideo', { size: 16 }), h('span', { class: 'muted', text: t('home.scope_hint') }), seg));
    }

    if (media.is_playlist) {
      const summary = [t('home.list_count', { count: media.count || (media.entries || []).length })];
      const total = formatLongDuration(media.total_duration);
      if (total) summary.push(total);
      const entries = (media.entries || []).slice(0, 4);
      const rest = (media.count || (media.entries || []).length) - entries.length;
      parts.push(h('div', { class: 'list-summary' },
        h('div', { class: 'list-summary-head' }, icon('listVideo', { size: 16 }), h('strong', { text: summary.join(' · ') })),
        h('ol', { class: 'list-entries' }, entries.map((e) => h('li', {},
          h('span', { class: 'entry-title', text: e.title || e.id || '' }),
          h('span', { class: 'muted num', text: formatDuration(e.duration) })))),
        rest > 0 ? h('p', { class: 'muted small', text: t('home.list_more', { count: rest }) }) : null));
    }

    if (!allOptions(media).length) {
      parts.push(h('div', { class: 'callout callout-warning' }, icon('alert', { size: 18 }), h('p', { text: t('home.no_options') })));
      return h('article', { class: 'card media-card enter' }, parts);
    }

    // Option tiles
    const tiles = h('div', { class: 'options', role: 'radiogroup', attrs: { 'aria-label': t('home.format_label') } });
    const moreRows = h('div', { class: 'more-list', role: 'radiogroup', attrs: { 'aria-label': t('home.more_options') } });
    const radios = [];
    const cta = btn({ label: media.is_playlist ? t('home.download_list', { count: media.count || 0 }) : t('home.download'), iconName: 'download', kind: 'cta', size: 'lg', cls: 'cta' });
    const fileLine = h('span', { class: 'filename-text' });
    const remember = h('input', { type: 'checkbox', id: 'remember', checked: Boolean(st.remember) });
    const rememberLabel = h('label', { class: 'check', htmlFor: 'remember' }, remember, h('span', { class: 'check-box' }, icon('check', { size: 12, strokeWidth: 3 })), h('span', { text: t('home.remember') }));
    remember.addEventListener('change', () => { st.remember = remember.checked; });

    function select(key, focus = false) {
      st.selected = key;
      for (const r of radios) {
        const on = r.dataset.key === key;
        r.setAttribute('aria-checked', String(on));
        r.tabIndex = on ? 0 : -1;
        if (on && focus) r.focus();
      }
      if (!radios.some((r) => r.dataset.key === key && r.tabIndex === 0) && radios[0]) radios[0].tabIndex = 0;
      const opt = findOption(media, key);
      const canRemember = FAVORITES.includes(key) && !media.is_playlist;
      remember.disabled = !canRemember;
      rememberLabel.classList.toggle('disabled', !canRemember);
      rememberLabel.title = canRemember ? '' : t('home.remember_disabled');
      setText(fileLine, media.is_playlist ? t('home.filename_list') : filenamePreview(media, opt, settings.filename_style));
      cta.disabled = !opt || opt.available === false;
    }

    function keyNav(e) {
      const idx = radios.indexOf(e.currentTarget);
      let next = -1;
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown') next = idx + 1;
      if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') next = idx - 1;
      if (next === -1) return;
      e.preventDefault();
      const visible = radios.filter((r) => r.offsetParent !== null && !r.disabled);
      if (!visible.length) return;
      const cur = visible.indexOf(e.currentTarget);
      const target = visible[(cur + (next > idx ? 1 : -1) + visible.length) % visible.length];
      select(target.dataset.key, true);
    }

    for (const opt of media.options || []) {
      const disabled = opt.available === false;
      const size = opt.size ? formatSize(opt.size, opt.size_approx) : media.is_playlist ? t('home.size_varies') : t('home.size_unknown');
      const tile = h('button', {
        class: ['tile', `tile-${opt.key}`], type: 'button', role: 'radio', disabled, dataset: { key: opt.key },
        attrs: { 'aria-checked': 'false' }, tabIndex: -1,
        on: { click: () => select(opt.key), keydown: keyNav },
      },
      h('span', { class: 'tile-top' },
        h('span', { class: 'tile-icon' }, icon(TILE_ICONS[opt.key] || (opt.kind === 'audio' ? 'music' : 'film'), { size: 18 })),
        h('span', { class: 'tile-check' }, icon('check', { size: 12, strokeWidth: 3 }))),
      h('span', { class: 'tile-name', text: optionName(opt.key) }),
      h('span', { class: 'tile-label', text: optionLabel(opt) }),
      h('span', { class: 'tile-size num', text: disabled ? t('home.not_available') : size }),
      h('span', { class: 'tile-hint', text: t(`option.${opt.key}.hint`) }));
      radios.push(tile);
      tiles.append(tile);
    }

    for (const opt of media.more || []) {
      const disabled = opt.available === false;
      const quality = opt.kind === 'audio' ? t('option.audio_only', { fmt: String(opt.container).toUpperCase() }) : heightLabel(opt.height, opt.fps) + (opt.hdr ? ' HDR' : '');
      const detail = opt.kind === 'audio' ? codecLabel(opt) : [String(opt.container).toUpperCase(), codecLabel(opt)].filter(Boolean).join(' · ');
      const size = opt.size ? formatSize(opt.size, opt.size_approx) : '';
      const row = h('button', {
        class: 'more-row', type: 'button', role: 'radio', disabled, dataset: { key: opt.key }, attrs: { 'aria-checked': 'false' }, tabIndex: -1,
        on: { click: () => select(opt.key), keydown: keyNav },
      },
      h('span', { class: 'radio-dot' }),
      h('span', { class: 'more-quality', text: quality }),
      h('span', { class: 'more-detail muted', text: detail }),
      h('span', { class: 'more-size num muted', text: disabled ? t('home.not_available') : size }));
      radios.push(row);
      moreRows.append(row);
    }

    const moreToggle = btn({ label: t('home.more_options'), iconName: 'chevronDown', kind: 'ghost', size: 'sm', cls: 'more-toggle', attrs: { 'aria-expanded': String(Boolean(st.moreOpen)), 'aria-controls': 'more-list' } });
    moreRows.id = 'more-list';
    moreRows.hidden = !st.moreOpen;
    moreToggle.addEventListener('click', () => {
      st.moreOpen = !st.moreOpen;
      moreRows.hidden = !st.moreOpen;
      moreToggle.setAttribute('aria-expanded', String(st.moreOpen));
    });

    const destPath = h('span', { class: 'dest-path', text: settings.download_dir || '', attrs: { title: settings.download_dir || '' } });
    const dest = h('div', { class: 'dest' },
      h('span', { class: 'dest-pill' }, icon('folder', { size: 16 }), h('span', { class: 'muted', text: t('home.save_in') }), destPath),
      btn({ label: t('common.change'), kind: 'link', size: 'sm', onClick: () => chooseFolder() }));
    const fileRow = h('div', { class: 'filename' }, icon('film', { size: 14 }), fileLine);

    cta.addEventListener('click', async () => {
      const key = st.selected;
      if (!key) return;
      cta.disabled = true;
      cta.classList.add('busy');
      if (st.remember && FAVORITES.includes(key) && !media.is_playlist) {
        await saveSettings({ favorite_preset: key, quick_download: true });
      }
      const res = await api.enqueue(media.token, key, media.is_playlist ? 'all' : null);
      cta.classList.remove('busy');
      if (res.ok) {
        const n = Array.isArray(res.job_ids) ? res.job_ids.length : 1;
        toast(t('home.added', { count: n }), { kind: 'success', timeout: 2600 });
        st.remember = false;
        resetCard({ clearInput: true });
        requestAnimationFrame(() => downloads.el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }));
      } else {
        cta.disabled = false;
        showError(res.error);
      }
    });

    parts.push(tiles, h('div', { class: 'more' }, moreToggle, moreRows), h('div', { class: 'card-divider' }), dest, fileRow,
      h('div', { class: 'card-foot' }, rememberLabel, cta));

    const card = h('article', { class: 'card media-card enter', attrs: { 'aria-label': media.title || '' } }, parts);
    card.addEventListener('animationend', () => card.classList.remove('enter'), { once: true });
    select(st.selected || defaultSelection(media));
    // Keep destination + filename in sync with settings changes while the card is open.
    card._onSettings = (s) => {
      setText(destPath, s.download_dir || '');
      destPath.title = s.download_dir || '';
      select(st.selected);
    };
    return card;
  }

  // ---------------------------------------------------------------- empty state + downloads
  const empty = h('div', { class: 'empty' },
    h('div', { class: 'empty-art', attrs: { 'aria-hidden': 'true' } }, h('span', { class: 'empty-orb' }), icon('download', { size: 34, strokeWidth: 1.8 })),
    h('h2', { class: 'empty-title', text: t('home.empty_title') }),
    h('p', { class: 'empty-text', text: t('home.empty_text') }),
    h('div', { class: 'empty-tips' },
      h('span', { class: 'tip' }, h('kbd', { text: 'Ctrl' }), '+', h('kbd', { text: 'V' }), h('span', { text: t('home.tip_paste') })),
      h('span', { class: 'tip' }, icon('dropDown', { size: 15 }), h('span', { text: t('home.tip_drop') })),
      h('span', { class: 'tip' }, h('kbd', { text: 'Enter' }), h('span', { text: t('home.tip_enter') }))),
    h('div', { class: 'sites' },
      h('span', { class: 'sites-label', text: t('home.sites_label') }),
      SITES.map((s) => h('span', { class: 'site', text: s })),
      h('span', { class: 'site site-more', text: t('home.sites_more') })),
    h('p', { class: 'responsible muted small' }, icon('shield', { size: 14 }), h('span', { text: t('home.responsible_short') })));

  let jobsEmpty = true;
  const downloads = createDownloads({ onEmptyChange: (isEmpty) => { jobsEmpty = isEmpty; refreshEmpty(); } });

  function refreshEmpty() {
    empty.hidden = !(jobsEmpty && st.stage === 'idle');
  }

  const el = h('section', { class: 'view view-home', attrs: { 'aria-label': t('nav.home') } },
    urlArea, h('div', { class: 'content' }, slot, downloads.el, empty));

  // ---------------------------------------------------------------- subscriptions
  unsubs.push(store.on('home:render', () => renderSlot()));
  unsubs.push(store.on('settings', (s) => {
    renderQuickNote();
    const card = slot.querySelector('.media-card');
    if (card && card._onSettings) card._onSettings(s);
    if (!s.clipboard_suggest) hideChip();
  }));

  syncInputButtons();
  renderQuickNote();
  renderSlot();

  return {
    el,
    destroy() {
      clearTimeout(slowTimer);
      unsubs.forEach((u) => u());
      downloads.destroy();
    },
    show() {
      checkClipboard();
    },
    focusInput() {
      input.focus();
      input.select();
    },
    analyzeText,
    fillAndProbe(url) {
      analyzeText(url, { incoming: true });
    },
    checkClipboard,
    /** Esc: cancel probe, then close the card. Returns true if it handled the key. */
    escape() {
      if (st.stage === 'probing') {
        cancelProbe();
        return true;
      }
      if (st.stage === 'media' || st.stage === 'error') {
        resetCard({ clearInput: true });
        input.focus();
        return true;
      }
      return false;
    },
    probeUrl(url) {
      input.value = url;
      st.input = url;
      syncInputButtons();
      return probe(url);
    },
    get stage() { return st.stage; },
  };
}
