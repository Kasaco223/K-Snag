// Bridge to the Python backend (docs/architecture.md §4-§6).
// - Inside pywebview: window.pywebview.api (available after the 'pywebviewready' event).
// - In a normal browser, or with ?mock=1: js/mock-api.js, a realistic in-page simulation.
// JS never sends file paths or URLs to open: only job ids, history ids, error codes and page keys.
import { applyEvent, store } from './store.js';

// window.kd.emit must exist before ui_ready() so the event pump never calls an undefined function.
window.kd = Object.assign(window.kd || {}, {
  emit(evt) {
    try {
      applyEvent(typeof evt === 'string' ? JSON.parse(evt) : evt);
    } catch (err) {
      console.error('[kd.emit] evento inválido', err);
    }
    return true;
  },
});

/** Every bridge method in the contract (§4). Calls always resolve to {ok: true, ...} | {ok: false, error}. */
export const METHODS = [
  'ui_ready', 'get_state', 'accept_terms', 'finish_onboarding',
  'engine_setup', 'engine_update', 'engine_repair',
  'probe', 'cancel_probe', 'enqueue', 'quick_download',
  'cancel', 'remove', 'retry', 'cancel_all', 'clear_finished',
  'open_file', 'show_in_folder', 'open_source', 'copy_details', 'open_help', 'open_page',
  'choose_folder', 'choose_cookies_file', 'clear_cookies_file', 'save_settings', 'read_clipboard',
  'history', 'history_remove', 'history_clear',
  'check_app_update', 'apply_app_update', 'confirm_close',
];

let impl = null;
let mockModule = null;

function bridgeError(code, details) {
  return {
    code,
    action: 'retry',
    auto: null,
    title: '',
    message: '',
    details: details || '',
    texts: {
      es: { title: 'No pudimos hablar con la app', message: 'Vuelve a intentarlo. Si sigue pasando, reinicia K-Snag.' },
      en: { title: "We couldn't reach the app", message: 'Try again. If it keeps happening, restart K-Snag.' },
    },
  };
}

async function call(name, args) {
  if (!impl || typeof impl[name] !== 'function') {
    return { ok: false, error: bridgeError('ui_bridge_missing', `Método no disponible: ${name}`) };
  }
  try {
    const res = await impl[name](...args);
    if (res && typeof res === 'object' && typeof res.ok === 'boolean') return res;
    return { ok: false, error: bridgeError('ui_bad_response', `Respuesta inesperada de ${name}`) };
  } catch (err) {
    console.error(`[api] ${name} falló`, err);
    return { ok: false, error: bridgeError('ui_bridge', String((err && err.message) || err)) };
  }
}

/** Typed facade: api.probe(url, playlist) etc. */
export const api = {
  ui_ready: () => call('ui_ready', []),
  get_state: () => call('get_state', []),
  accept_terms: () => call('accept_terms', []),
  finish_onboarding: (patch) => call('finish_onboarding', [patch || {}]),
  engine_setup: () => call('engine_setup', []),
  engine_update: (force) => call('engine_update', [Boolean(force)]),
  engine_repair: (component) => call('engine_repair', [component ?? null]),
  probe: (url, playlist) => call('probe', [String(url), Boolean(playlist)]),
  cancel_probe: () => call('cancel_probe', []),
  enqueue: (token, option, items) => call('enqueue', [token, option, items ?? null]),
  quick_download: (url) => call('quick_download', [String(url)]),
  cancel: (jobId) => call('cancel', [jobId]),
  remove: (jobId) => call('remove', [jobId]),
  retry: (jobId, mode) => call('retry', [jobId, mode || 'normal']),
  cancel_all: () => call('cancel_all', []),
  clear_finished: () => call('clear_finished', []),
  open_file: (jobId) => call('open_file', [jobId]),
  show_in_folder: (jobId) => call('show_in_folder', [jobId]),
  open_source: (jobId) => call('open_source', [jobId]),
  copy_details: (jobId) => call('copy_details', [jobId]),
  open_help: (code) => call('open_help', [code || 'unknown']),
  open_page: (key) => call('open_page', [key]),
  choose_folder: () => call('choose_folder', []),
  choose_cookies_file: () => call('choose_cookies_file', []),
  clear_cookies_file: () => call('clear_cookies_file', []),
  save_settings: (patch) => call('save_settings', [patch || {}]),
  read_clipboard: () => call('read_clipboard', []),
  history: (query) => call('history', [query || '']),
  history_remove: (id) => call('history_remove', [id]),
  history_clear: () => call('history_clear', []),
  check_app_update: () => call('check_app_update', []),
  apply_app_update: () => call('apply_app_update', []),
  confirm_close: () => call('confirm_close', []),
};

function hasBridge() {
  return Boolean(window.pywebview && window.pywebview.api && typeof window.pywebview.api.ui_ready === 'function');
}

function waitForBridge(ms) {
  if (hasBridge()) return Promise.resolve(true);
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(hasBridge()), ms);
    window.addEventListener('pywebviewready', () => {
      clearTimeout(timer);
      // pywebview fills window.pywebview.api right before dispatching the event.
      setTimeout(() => resolve(hasBridge()), 0);
    }, { once: true });
  });
}

/**
 * Picks the backend. Returns 'pywebview' or 'mock'.
 * ?mock=1 forces the mock. Without pywebview we wait ~2 s for 'pywebviewready' before falling back.
 * If pywebview's object already exists (injected but API not ready) we wait longer instead of mocking.
 */
export async function connect() {
  const params = new URLSearchParams(location.search);
  const forceMock = params.get('mock') === '1';
  if (!forceMock) {
    const injected = typeof window.pywebview === 'object' && window.pywebview !== null;
    const ok = await waitForBridge(injected ? 15000 : 2000);
    if (ok) {
      impl = window.pywebview.api;
      store.mode = 'pywebview';
      return 'pywebview';
    }
  }
  mockModule = await import('./mock-api.js');
  impl = mockModule.createMockApi((evt) => window.kd.emit(evt));
  store.mode = 'mock';
  if (!forceMock) {
    // The real bridge showed up late: reload once so we never mix demo data with the real app.
    window.addEventListener('pywebviewready', () => location.reload(), { once: true });
  }
  return 'mock';
}

/** Scenario hints from the mock (?scenario=S0..S9), or null in the real app. */
export function mockScenario() {
  return mockModule ? mockModule.scenarioUi() : null;
}
