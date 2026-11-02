// Última versión para Android, leída de /data/android.json: el mismo archivo que la app lee
// (desde raw.githubusercontent.com) para buscar actualizaciones. Se valida todo antes de usarlo;
// si falta o no es válido, la página se queda con los enlaces /android/descargar del HTML.

const DATA_URL = '/data/android.json';
// El repo se llamaba K-YoutubeVideoDownloaderHD: los punteros siguen usando ese nombre (GitHub redirige)
// porque las versiones 1.0.0 a 1.0.3 de la app solo aceptan esas direcciones. Se aceptan los dos.
const REPOS = ['Kasaco223/K-YoutubeVideoDownloaderHD', 'Kasaco223/K-Snag'];
const VERSION_RE = /^\d{1,3}\.\d{1,2}\.\d{1,2}$/;
const SHA256_RE = /^[0-9a-f]{64}$/;
const NAME_RE = /^[A-Za-z0-9._-]{1,80}\.apk$/;

export const ABIS = ['arm64-v8a', 'armeabi-v7a', 'x86_64'];
export const FALLBACK_URL = '/android/descargar';

function sanitize(json) {
  if (json?.schema !== 1) return null;
  const version = String(json.version ?? '');
  if (!VERSION_RE.test(version)) return null;
  const bases = REPOS.map((repo) => `https://github.com/${repo}/releases/download/android-v${version}/`);
  const apks = {};
  for (const abi of ABIS) {
    const apk = json.apks?.[abi];
    const url = String(apk?.url ?? '');
    const base = bases.find((prefix) => url.startsWith(prefix));
    const name = base ? url.slice(base.length) : '';
    const sha256 = String(apk?.sha256 ?? '').toLowerCase();
    if (!base || !NAME_RE.test(name) || !SHA256_RE.test(sha256)) continue;
    const size = Number(apk.size);
    apks[abi] = { url, name, sha256, size: Number.isSafeInteger(size) && size > 0 ? size : null };
  }
  if (!apks['arm64-v8a']) return null;
  const notes = String(json.notes_url ?? '');
  const cert = String(json.cert_sha256 ?? '').toLowerCase();
  return {
    version,
    apks,
    notesUrl: REPOS.some((repo) => notes === `https://github.com/${repo}/releases/tag/android-v${version}`) ? notes : null,
    certSha256: SHA256_RE.test(cert) ? cert : null,
  };
}

let pending = null;

/** {version, apks: {abi: {url, name, sha256, size}}, notesUrl, certSha256} o null. Nunca lanza. */
export function loadAndroidRelease() {
  pending ??= (async () => {
    try {
      const res = await fetch(DATA_URL); // mismo modo que el <link rel="preload" crossorigin> de android.html
      if (!res.ok) return null;
      return sanitize(await res.json());
    } catch {
      return null;
    }
  })();
  return pending;
}

/** 58123456 → "58 MB" */
export function formatSize(bytes) {
  return bytes ? `${Math.round(bytes / 1_000_000)} MB` : '';
}

/** "3f1c…" → "3F:1C:…" (como lo muestra keytool), para comparar a simple vista. */
export function formatFingerprint(hex) {
  return hex ? hex.toUpperCase().match(/.{2}/g).join(':') : '';
}
