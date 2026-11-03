// Centro de ayuda: pinta web/data/errors.json (generado por scripts/export_web_data.py
// desde el mismo catálogo que usa la app) y lleva al usuario al código de error del enlace #codigo.

import { el, icon, initSite, prefersReducedMotion, storage } from './site.js';

const DATA_URL = '/data/errors.json';
const LANG_KEY = 'kd.help.lang';
const CODE_RE = /^[a-z0-9_]{1,40}$/;
const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

const GROUPS = [
  {
    id: 'grupo-motor',
    icon: 'refresh',
    es: 'Cuando el sitio cambia o te limita',
    en: 'When the site changes or limits you',
    codes: ['http_403', 'engine_outdated', 'format_unavailable', 'bot_check', 'rate_limited'],
  },
  {
    id: 'grupo-acceso',
    icon: 'lock',
    es: 'Videos con acceso restringido',
    en: 'Restricted videos',
    codes: [
      'private', 'members_only', 'age_restricted', 'login_required', 'payment', 'drm', 'geo_blocked',
      'copyright', 'removed', 'unavailable', 'upcoming', 'not_live', 'unsupported_url',
    ],
  },
  {
    id: 'grupo-pc',
    icon: 'monitor-play',
    es: 'Tu PC y tu conexión',
    en: 'Your PC and connection',
    codes: ['network', 'ssl', 'disk_full', 'permission', 'ffmpeg_missing', 'blocked_by_windows', 'antivirus', 'merge_failed'],
  },
  { id: 'grupo-otros', icon: 'help', es: 'Otros', en: 'Other', codes: [] },
];

const UI = {
  es: {
    errorsTitle: 'Mensajes de error',
    errorsLead: 'Son los mismos mensajes que ves en la app. Cada uno tiene su propio enlace: el botón «Ayuda» de la app te trae directo aquí.',
    whatToDo: 'Qué puedes hacer',
    copyLink: (title) => `Copiar enlace a «${title}»`,
    copied: 'Enlace copiado',
    copyFallback: 'Copia la dirección de la barra',
    empty: (q) => `No encontramos ningún mensaje con «${q}». Prueba con otra palabra o revisa la sección de avisos de Windows.`,
    missing: (code) => `No encontramos el código «${code}». Puede ser de una versión más nueva de la app; aquí tienes todos los mensajes.`,
    count: (n) => `${n} ${n === 1 ? 'mensaje' : 'mensajes'}`,
    placeholder: 'Busca: 403, disco lleno, privado…',
  },
  en: {
    errorsTitle: 'Error messages',
    errorsLead: 'These are the same messages you see in the app. Each one has its own link: the app’s “Help” button brings you straight here.',
    whatToDo: 'What you can do',
    copyLink: (title) => `Copy link to “${title}”`,
    copied: 'Link copied',
    copyFallback: 'Copy the address bar',
    empty: (q) => `No messages match “${q}”. Try another word or check the Windows warnings section.`,
    missing: (code) => `We couldn’t find the code “${code}”. It may come from a newer version of the app; here are all the messages.`,
    count: (n) => `${n} ${n === 1 ? 'message' : 'messages'}`,
    placeholder: 'Search: 403, disk full, private…',
  },
};

const state = {
  lang: 'es',
  items: [],
  query: '',
};

const $ = (id) => document.getElementById(id);
const fold = (text) =>
  String(text ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase();

function initialLang() {
  const fromQuery = new URLSearchParams(window.location.search).get('lang');
  if (fromQuery === 'es' || fromQuery === 'en') return fromQuery;
  const saved = storage.get(LANG_KEY);
  return saved === 'en' ? 'en' : 'es';
}

function validTexts(texts) {
  return texts && typeof texts.title === 'string' && typeof texts.message === 'string';
}

function parseItems(json) {
  const list = Array.isArray(json) ? json : Array.isArray(json?.errors) ? json.errors : [];
  const seen = new Set();
  return list.filter((item) => {
    const ok = item && CODE_RE.test(item.code) && !seen.has(item.code) && validTexts(item.es) && validTexts(item.en);
    if (ok) seen.add(item.code);
    return ok;
  });
}

/* ---------- Pintado ---------- */

function buildCard(item) {
  const card = el('article', { class: 'err-card', id: item.code, 'data-code': item.code });
  const title = el('h4', { class: 'err-title' });
  const message = el('p', { class: 'err-message' });
  const helpLabel = el('span', { class: 'err-help-label' });
  const helpText = el('p', { class: 'err-help-text' });
  const help = el('div', { class: 'err-help' }, icon('info', 'icon-sm'), el('div', {}, helpLabel, helpText));
  const copy = el('button', { class: 'err-copy', type: 'button' }, icon('link', 'icon-sm'));
  const tooltip = el('span', { class: 'err-copied', role: 'status' });
  const head = el(
    'div',
    { class: 'err-head' },
    el('a', { class: 'err-code', href: `#${item.code}` }, icon('hash', 'icon-sm'), el('span', { text: item.code })),
    el('span', { class: 'err-head-actions' }, tooltip, copy),
  );
  card.append(head, title, message, help);
  card.setAttribute('aria-labelledby', `${item.code}-title`);
  title.id = `${item.code}-title`;

  copy.addEventListener('click', () => copyLink(item.code, tooltip));

  // Texto de búsqueda en ambos idiomas, sin tildes
  card.dataset.search = fold(
    [item.code, item.code.replace(/_/g, ' '), item.es.title, item.es.message, item.es.help, item.en.title, item.en.message, item.en.help].join(' '),
  );
  card._kd = { item, title, message, helpLabel, helpText, help, copy };
  return card;
}

function fillCard(card) {
  const { item, title, message, helpLabel, helpText, help, copy } = card._kd;
  const texts = item[state.lang];
  title.textContent = texts.title;
  message.textContent = texts.message;
  helpLabel.textContent = UI[state.lang].whatToDo;
  helpText.textContent = texts.help || '';
  help.hidden = !texts.help;
  copy.setAttribute('aria-label', UI[state.lang].copyLink(texts.title));
  copy.title = UI[state.lang].copyLink(texts.title);
}

function render() {
  const container = $('error-groups');
  const byCode = new Map(state.items.map((item) => [item.code, item]));
  const assigned = new Set();
  const fragment = document.createDocumentFragment();

  for (const group of GROUPS) {
    let codes = group.codes.filter((code) => byCode.has(code));
    if (!group.codes.length) codes = [...byCode.keys()].filter((code) => !assigned.has(code));
    codes.forEach((code) => assigned.add(code));
    if (!codes.length) continue;

    const heading = el('h3', { class: 'group-title', id: group.id }, icon(group.icon), el('span', { class: 'group-name' }), el('span', { class: 'group-count' }));
    const grid = el('div', { class: 'err-grid' }, ...codes.map((code) => buildCard(byCode.get(code))));
    const section = el('section', { class: 'err-group', 'aria-labelledby': group.id }, heading, grid);
    section._kd = { group, heading };
    fragment.append(section);
  }

  container.replaceChildren(fragment);
  container.removeAttribute('aria-busy');
  applyLang();
}

function applyLang() {
  const ui = UI[state.lang];
  const section = $('errores');
  section.lang = state.lang;
  for (const node of section.querySelectorAll('[data-i18n]')) {
    const value = ui[node.dataset.i18n];
    if (typeof value === 'string') node.textContent = value;
  }
  for (const button of section.querySelectorAll('.lang-toggle button')) {
    button.setAttribute('aria-pressed', String(button.dataset.lang === state.lang));
  }
  $('help-search').placeholder = ui.placeholder;
  for (const card of section.querySelectorAll('.err-card')) fillCard(card);
  applyFilter();
}

function applyFilter() {
  const query = fold(state.query).trim();
  const words = query.split(/\s+/).filter(Boolean);
  let visibleTotal = 0;
  for (const group of document.querySelectorAll('.err-group')) {
    let visible = 0;
    for (const card of group.querySelectorAll('.err-card')) {
      const match = words.every((word) => card.dataset.search.includes(word));
      card.hidden = !match;
      if (match) visible += 1;
    }
    group.hidden = visible === 0;
    visibleTotal += visible;
    const { group: meta, heading } = group._kd;
    heading.querySelector('.group-name').textContent = meta[state.lang];
    heading.querySelector('.group-count').textContent = UI[state.lang].count(visible);
  }
  const empty = $('error-empty');
  empty.hidden = visibleTotal > 0 || !state.items.length;
  empty.textContent = empty.hidden ? '' : UI[state.lang].empty(state.query.trim());
}

/* ---------- Enlaces #codigo ---------- */

async function copyLink(code, tooltip) {
  const url = `${window.location.origin}${window.location.pathname}#${code}`;
  let ok = false;
  try {
    await navigator.clipboard.writeText(url);
    ok = true;
  } catch {
    window.history.replaceState(null, '', `#${code}`);
  }
  tooltip.textContent = ok ? UI[state.lang].copied : UI[state.lang].copyFallback;
  tooltip.classList.add('is-visible');
  window.clearTimeout(tooltip._timer);
  tooltip._timer = window.setTimeout(() => {
    tooltip.classList.remove('is-visible');
    tooltip.textContent = '';
  }, 2200);
}

function currentHashId() {
  let raw = window.location.hash.slice(1);
  try {
    raw = decodeURIComponent(raw);
  } catch {
    return null;
  }
  return ID_RE.test(raw) ? raw : null;
}

function highlight(target) {
  document.querySelectorAll('.is-target').forEach((node) => node.classList.remove('is-target'));
  void target.offsetWidth; // reinicia la animación si se repite el mismo código
  target.classList.add('is-target');
  if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
  target.focus({ preventScroll: true });
}

function goToHash({ smooth = false } = {}) {
  const id = currentHashId();
  const missing = $('missing-code');
  missing.hidden = true;
  missing.textContent = '';
  if (!id) return;

  let target = document.getElementById(id);
  if (target?.classList.contains('err-card') && target.hidden) {
    // El código está oculto por la búsqueda: la limpiamos para mostrarlo
    state.query = '';
    $('help-search').value = '';
    applyFilter();
  }
  if (!target && CODE_RE.test(id) && state.items.length) {
    missing.textContent = UI[state.lang].missing(id);
    missing.hidden = false;
    target = $('errores');
  }
  if (!target) return;

  const behavior = smooth && !prefersReducedMotion() ? 'smooth' : 'auto';
  target.scrollIntoView({ behavior, block: 'start' });
  if (target.classList.contains('err-card') || target.classList.contains('guide-card')) highlight(target);
}

/* ---------- Inicio ---------- */

function initControls() {
  const search = $('help-search');
  $('help-search-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const first = document.querySelector('.err-card:not([hidden])');
    if (first) first.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' });
  });
  search.addEventListener('input', () => {
    state.query = search.value;
    applyFilter();
  });
  for (const button of document.querySelectorAll('.lang-toggle button')) {
    button.addEventListener('click', () => {
      state.lang = button.dataset.lang === 'en' ? 'en' : 'es';
      storage.set(LANG_KEY, state.lang);
      applyLang();
    });
  }
  window.addEventListener('hashchange', () => goToHash({ smooth: true }));
}

async function loadData() {
  try {
    const res = await fetch(DATA_URL);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const items = parseItems(await res.json());
    if (!items.length) throw new Error('vacío');
    return items;
  } catch {
    return null;
  }
}

async function main() {
  initSite();
  state.lang = initialLang();
  initControls();
  const items = await loadData();
  if (!items) {
    // El HTML ya trae el catálogo en español (scripts/export_web_data.py): se queda, sin buscador ni idiomas.
    if (!$('error-groups').querySelector('.err-card')) $('error-load-failed').hidden = false;
    goToHash();
    return;
  }
  state.items = items;
  render();
  // Esperamos un cuadro para que el navegador calcule el layout antes de desplazarnos
  window.requestAnimationFrame(() => goToHash());
}

main();
