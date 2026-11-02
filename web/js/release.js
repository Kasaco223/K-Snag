// Muestra la versión publicada más reciente, leída de la API pública de GitHub.
// Si no hay versión publicada (404), se agotó la cuota (403/429) o no hay red, la etiqueta
// conserva su texto original ("Windows 10 y 11"). Se guarda en caché para no gastar la cuota.

import { storage } from './site.js';

const REPO = 'Kasaco223/K-Snag';
const API_URL = `https://api.github.com/repos/${REPO}/releases/latest`;
const RELEASES_PREFIX = `https://github.com/${REPO}/releases/`;
const CACHE_KEY = 'kd.release.v1';
const FRESH_MS = 60 * 60 * 1000; // 1 h
const STALE_MS = 7 * 24 * 60 * 60 * 1000; // respaldo si la API falla
const TIMEOUT_MS = 6000;
const TAG_RE = /^v?(\d{1,4}\.\d{1,4}\.\d{1,4}(?:[-.][0-9A-Za-z]{1,12}(?:\.[0-9A-Za-z]{1,12})?)?)$/;

function readCache() {
  const raw = storage.get(CACHE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    if (typeof parsed?.t !== 'number') return null;
    return parsed;
  } catch {
    return null;
  }
}

function writeCache(data) {
  storage.set(CACHE_KEY, JSON.stringify({ t: Date.now(), data }));
}

function sanitize(json) {
  const match = TAG_RE.exec(String(json?.tag_name ?? '').trim());
  if (!match) return null;
  const notes = typeof json.html_url === 'string' && json.html_url.startsWith(RELEASES_PREFIX) ? json.html_url : null;
  return { version: match[1], notes };
}

async function fetchLatest() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(API_URL, {
      headers: { Accept: 'application/vnd.github+json' },
      credentials: 'omit',
      signal: controller.signal,
    });
    if (res.status === 404) return { ok: true, data: null }; // todavía no hay versión publicada
    if (!res.ok) return { ok: false };
    return { ok: true, data: sanitize(await res.json()) };
  } catch {
    return { ok: false };
  } finally {
    clearTimeout(timer);
  }
}

/** Devuelve {version, notes} o null. Nunca lanza. */
export async function loadLatestRelease() {
  const cached = readCache();
  const age = cached ? Date.now() - cached.t : Infinity;
  if (cached && age < FRESH_MS) return cached.data ?? null;

  const result = await fetchLatest();
  if (result.ok) {
    writeCache(result.data);
    return result.data;
  }
  return cached && age < STALE_MS ? cached.data ?? null : null;
}

/** Reemplaza el texto de `target` por "Versión X · Windows 10 y 11" cuando hay datos. */
export async function renderReleaseLabel(target) {
  if (!target) return;
  const release = await loadLatestRelease();
  if (!release) return;
  const suffix = ` · ${target.dataset.fallback || 'Windows 10 y 11'}`;
  const label = `Versión ${release.version}`;
  if (release.notes) {
    const link = document.createElement('a');
    link.href = release.notes;
    link.rel = 'noopener';
    link.textContent = label;
    link.title = 'Ver las novedades de esta versión';
    target.replaceChildren(link, document.createTextNode(suffix));
  } else {
    target.textContent = label + suffix;
  }
}
