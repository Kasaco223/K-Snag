// Mock of the whole Python bridge (docs/architecture.md §4-§6) so the UI runs in any browser.
// URL params:  ?mock=1&scenario=S0..S9&theme=dark|light|system&lang=es|en
//   extras:    &freeze=1 (stop the simulation clock)  &update=1 (update banner)  &notice=1 (remote notice)
//              &close=1 (fire confirm_close after load)  &list=1 (S3 with a list= URL)  &clip=0 (empty clipboard)
//              &fail=ffmpeg|ytdlp|deno (S0: that component fails once, to show "Reintentar")
// Probe tricks: URLs containing "private", "bot", "nosoportado", "lowq" or "live" return those cases;
//               URLs containing "error" create a job that fails with a 403 and tries to fix itself.
// Thumbnails are inline SVG data: URIs (no network).

const KB = 1024;
const MB = KB * 1024;
const GB = MB * 1024;

const params = new URLSearchParams(location.search);
const SCENARIO = (params.get('scenario') || 'S1').toUpperCase();
const LANG = ['es', 'en'].includes(params.get('lang')) ? params.get('lang') : null;
const THEME = ['dark', 'light', 'system'].includes(params.get('theme')) ? params.get('theme') : null;
const FREEZE = params.get('freeze') === '1';
const FAIL = params.get('fail');

const clone = (v) => JSON.parse(JSON.stringify(v));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const nowSec = () => Date.now() / 1000;
const rand = (a, b) => a + Math.random() * (b - a);
let idCounter = 0;
const newId = (prefix = 'j') => `${prefix}${Date.now().toString(36)}${(idCounter++).toString(36)}`;

// ---------------------------------------------------------------- thumbnails (inline SVG)
function svgUri(svg) {
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
}

const BUNNY = svgUri(
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180">' +
  '<defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#6fb8ef"/><stop offset="1" stop-color="#d7f0ff"/></linearGradient></defs>' +
  '<rect width="320" height="180" fill="url(#s)"/><circle cx="264" cy="40" r="20" fill="#fff3a8"/>' +
  '<ellipse cx="70" cy="196" rx="180" ry="74" fill="#5cbf5b"/><ellipse cx="268" cy="206" rx="170" ry="80" fill="#47a449"/>' +
  '<path d="M20 150 q8 -18 16 0 M40 156 q6 -14 12 0 M280 150 q8 -16 16 0" stroke="#2f7d32" stroke-width="3" fill="none"/>' +
  '<g fill="#f3efe8"><ellipse cx="166" cy="124" rx="36" ry="31"/><circle cx="166" cy="84" r="21"/>' +
  '<ellipse cx="156" cy="52" rx="6.5" ry="21" transform="rotate(-8 156 52)"/><ellipse cx="177" cy="52" rx="6.5" ry="21" transform="rotate(8 177 52)"/></g>' +
  '<ellipse cx="156" cy="52" rx="2.6" ry="14" fill="#f2b6bd" transform="rotate(-8 156 52)"/><ellipse cx="177" cy="52" rx="2.6" ry="14" fill="#f2b6bd" transform="rotate(8 177 52)"/>' +
  '<circle cx="159" cy="82" r="2.8" fill="#2b2b2b"/><circle cx="173" cy="82" r="2.8" fill="#2b2b2b"/><ellipse cx="166" cy="92" rx="4.2" ry="2.6" fill="#ee9aa4"/>' +
  '</svg>'
);

const PALETTES = [
  ['#1e3a8a', '#7c3aed', 'mountains'],
  ['#0f766e', '#22d3ee', 'waves'],
  ['#9a3412', '#f59e0b', 'sun'],
  ['#312e81', '#ec4899', 'circles'],
  ['#14532d', '#84cc16', 'mountains'],
  ['#1f2937', '#6366f1', 'bars'],
  ['#7f1d1d', '#fb7185', 'waves'],
  ['#0c4a6e', '#38bdf8', 'sun'],
];

function artThumb(seed) {
  const [a, b, kind] = PALETTES[seed % PALETTES.length];
  let shapes = '';
  if (kind === 'mountains') {
    shapes = '<path d="M0 180 L70 90 L120 140 L190 60 L260 130 L320 80 L320 180Z" fill="rgba(255,255,255,.18)"/>' +
      '<path d="M0 180 L90 120 L150 160 L230 100 L320 150 L320 180Z" fill="rgba(0,0,0,.22)"/><circle cx="250" cy="45" r="16" fill="rgba(255,255,255,.55)"/>';
  } else if (kind === 'waves') {
    shapes = '<path d="M0 110 Q40 90 80 110 T160 110 T240 110 T320 110 V180 H0Z" fill="rgba(255,255,255,.18)"/>' +
      '<path d="M0 135 Q40 115 80 135 T160 135 T240 135 T320 135 V180 H0Z" fill="rgba(0,0,0,.18)"/>';
  } else if (kind === 'sun') {
    shapes = '<circle cx="160" cy="120" r="56" fill="rgba(255,255,255,.35)"/><rect y="120" width="320" height="60" fill="rgba(0,0,0,.25)"/>' +
      '<rect x="40" y="132" width="240" height="4" rx="2" fill="rgba(255,255,255,.25)"/><rect x="80" y="146" width="160" height="4" rx="2" fill="rgba(255,255,255,.2)"/>';
  } else if (kind === 'circles') {
    shapes = '<circle cx="90" cy="70" r="48" fill="rgba(255,255,255,.18)"/><circle cx="210" cy="110" r="64" fill="rgba(0,0,0,.2)"/><circle cx="250" cy="50" r="20" fill="rgba(255,255,255,.3)"/>';
  } else {
    shapes = [30, 70, 110, 150, 190, 230, 270].map((x, i) => `<rect x="${x}" y="${150 - (40 + ((i * 37) % 80))}" width="22" height="${40 + ((i * 37) % 80)}" rx="4" fill="rgba(255,255,255,${0.15 + (i % 3) * 0.08})"/>`).join('');
  }
  return svgUri(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180">' +
    `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs>` +
    `<rect width="320" height="180" fill="url(#g)"/>${shapes}</svg>`
  );
}

// ---------------------------------------------------------------- catalog subset (same texts as engine/errors_catalog.json)
const ERRORS = {
  http_403: {
    action: 'update_retry', auto: 'update_retry',
    es: { title: 'El sitio bloqueó la descarga', message: 'Suele pasar cuando YouTube cambia algo. Actualizamos el motor y lo intentamos de nuevo.' },
    en: { title: 'The site blocked the download', message: 'This usually happens when YouTube changes something. We update the engine and try again.' },
    details: 'ERROR: unable to download video data: HTTP Error 403: Forbidden',
  },
  bot_check: {
    action: 'retry_later', auto: 'quiet_mode',
    es: { title: 'YouTube pide comprobar que no eres un robot', message: 'Hubo muchas descargas seguidas desde tu conexión, o estás usando una VPN o red compartida.' },
    en: { title: "YouTube wants to check you're not a robot", message: "There were many downloads in a row from your connection, or you're on a VPN or shared network." },
    details: "ERROR: [youtube] dQw4w9WgXcQ: Sign in to confirm you're not a bot. Use --cookies-from-browser or --cookies for the authentication.",
  },
  age_restricted: {
    action: 'cookies', auto: null,
    es: { title: 'Video para mayores de 18', message: 'YouTube solo lo muestra a cuentas verificadas.' },
    en: { title: 'Age-restricted video', message: 'YouTube only shows it to verified accounts.' },
    details: 'ERROR: [youtube] xY12abCdEfG: Sign in to confirm your age. This video may be inappropriate for some users.',
  },
  private: {
    action: 'cookies', auto: null,
    es: { title: 'Video privado', message: 'Solo pueden verlo las cuentas invitadas por su autor.' },
    en: { title: 'Private video', message: 'Only accounts invited by the author can watch it.' },
    details: 'ERROR: [youtube] AbCdEfGhIjK: Private video. Sign in if you\'ve been granted access to this video',
  },
  disk_full: {
    action: 'choose_folder', auto: null,
    es: { title: 'Tu disco está lleno', message: 'No queda espacio para guardar el archivo.' },
    en: { title: 'Your disk is full', message: "There's no space left to save the file." },
    details: 'ERROR: unable to write data: [Errno 28] No space left on device',
  },
  unsupported_url: {
    action: 'none', auto: null,
    es: { title: 'Ese enlace no es compatible', message: 'Cópialo desde la barra de direcciones o el botón Compartir.' },
    en: { title: "That link isn't supported", message: 'Copy it from the address bar or the Share button.' },
    details: 'ERROR: Unsupported URL: https://example.com/nosoportado',
  },
  unavailable: {
    action: 'open_browser', auto: null,
    es: { title: 'Video no disponible', message: 'El sitio dice que este video no está disponible.' },
    en: { title: 'Video unavailable', message: "The site says this video isn't available." },
    details: 'ERROR: [youtube] ZzZzZzZzZzZ: Video unavailable',
  },
};

function errorView(code, lang) {
  const e = ERRORS[code];
  const l = lang === 'en' ? 'en' : 'es';
  return {
    code, action: e.action, auto: e.auto, title: e[l].title, message: e[l].message, details: e.details,
    texts: { es: e.es, en: e.en },
  };
}

// ---------------------------------------------------------------- sample media
const SAMPLE_URL = 'https://www.youtube.com/watch?v=aqz-KE-bpKQ';
const SAMPLE_TITLE = 'Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film';

function opt(key, kind, container, height, fps, vcodec, acodec, size, extra = {}) {
  return { key, kind, container, height, fps, vcodec, acodec, hdr: false, size, size_approx: true, available: true, ...extra };
}

function sampleMedia(url, token) {
  return {
    token, id: 'aqz-KE-bpKQ', title: SAMPLE_TITLE, uploader: 'Blender', duration: 635, thumbnail: BUNNY,
    url, site: 'YouTube', is_youtube: true, is_live: false, age_limit: 0, is_playlist: false,
    degraded: false, warnings: [],
    options: [
      opt('recommended', 'video', 'mp4', 1080, 60, 'h264', 'aac', 268 * MB),
      opt('max', 'video', 'mkv', 2160, 60, 'vp9', 'opus', 1.37 * GB),
      opt('mp3', 'audio', 'mp3', null, null, null, 'mp3', 10 * MB),
    ],
    more: [
      opt('h1440', 'video', 'mkv', 1440, 60, 'vp9', 'opus', 702 * MB),
      opt('h1080', 'video', 'mp4', 1080, 60, 'h264', 'aac', 268 * MB),
      opt('h720', 'video', 'mp4', 720, 60, 'h264', 'aac', 141 * MB),
      opt('h480', 'video', 'mp4', 480, 30, 'h264', 'aac', 58 * MB),
      opt('h360', 'video', 'mp4', 360, 30, 'h264', 'aac', 36 * MB),
      opt('m4a', 'audio', 'm4a', null, null, null, 'aac', 9.8 * MB),
    ],
  };
}

const LIST_TITLES = [
  ['Big Buck Bunny', 635], ['Sintel', 888], ['Tears of Steel', 734], ['Cosmos Laundromat', 730],
  ['Spring', 464], ['Agent 327: Operation Barbershop', 231], ['Caminandes 3: Llamigos', 150], ['Hero', 236],
  ['Coffee Run', 184], ['Sprite Fright', 629], ['Charge', 237], ['Wing It!', 223],
];

function playlistMedia(url, token) {
  const entries = LIST_TITLES.map(([title, duration], i) => ({ id: `bl${i}`, title, duration }));
  return {
    token, id: 'PLblender', title: 'Blender Open Movies', uploader: 'Blender', duration: null, thumbnail: artThumb(3),
    url, site: 'YouTube', is_youtube: true, is_live: false, age_limit: 0, is_playlist: true,
    count: entries.length, total_duration: entries.reduce((s, e) => s + e.duration, 0), entries,
    degraded: false, warnings: [],
    options: [
      opt('recommended', 'video', 'mp4', 1080, 60, 'h264', 'aac', null, { size_approx: false }),
      opt('max', 'video', 'mkv', 2160, 60, 'vp9', 'opus', null, { size_approx: false }),
      opt('mp3', 'audio', 'mp3', null, null, null, 'mp3', null, { size_approx: false }),
    ],
    more: [
      opt('h720', 'video', 'mp4', 720, 30, 'h264', 'aac', null, { size_approx: false }),
      opt('h480', 'video', 'mp4', 480, 30, 'h264', 'aac', null, { size_approx: false }),
      opt('m4a', 'audio', 'm4a', null, null, null, 'aac', null, { size_approx: false }),
    ],
  };
}

// ---------------------------------------------------------------- state
const DEFAULT_SETTINGS = {
  version: 1,
  language: 'auto', theme: THEME || 'system', reduce_motion: false,
  download_dir: 'C:\\Users\\Ana\\Downloads\\K-Snag', favorite_preset: 'recommended', quick_download: false,
  filename_style: 'title_id', max_concurrent: 2,
  engine_channel: 'nightly', auto_update_engine: true, max_codec: 'vp9',
  clipboard_suggest: true, notify_on_finish: true, open_folder_on_finish: false,
  cookies_file: '', accepted_terms: true, first_run_done: true, check_app_updates: true,
};
const CHOICES = {
  language: ['auto', 'es', 'en'], theme: ['system', 'light', 'dark'], favorite_preset: ['recommended', 'max', 'mp3', 'm4a'],
  filename_style: ['title_id', 'legacy_k', 'simple'], engine_channel: ['nightly', 'stable'], max_codec: ['vp9', 'av1'],
};

const SIZES = { ytdlp: 3 * MB, ffmpeg: 115 * MB, deno: 43 * MB };
const VERSIONS = { ytdlp: '2026.10.08.231507', ffmpeg: '9.0.2', deno: '2.9.7' };

function component(name, ready) {
  return {
    state: ready ? 'ready' : 'missing', version: ready ? VERSIONS[name] : null, source: ready ? 'managed' : null,
    progress: null, done: 0, total: 0, error: null, approx_size: SIZES[name],
  };
}

function engineView(ready) {
  return {
    ready, busy: false, channel: 'nightly',
    components: { ytdlp: component('ytdlp', ready), ffmpeg: component('ffmpeg', ready), deno: component('deno', ready) },
  };
}

const UPDATE = { version: '2.0.1', notes_url: 'https://github.com/Kasaco223/K-Snag/releases/tag/v2.0.1' };
const NOTICE = {
  es: 'YouTube está haciendo cambios hoy. Si una descarga falla, la app actualizará el motor sola.',
  en: 'YouTube is rolling out changes today. If a download fails, the app will update its engine automatically.',
};

function systemLang() {
  if (LANG) return LANG;
  return (navigator.language || 'es').toLowerCase().startsWith('es') ? 'es' : 'en';
}

// ---------------------------------------------------------------- jobs
const VIDEO_TITLES = [
  ['Sintel - Open Movie by Blender', 'Blender'],
  ['Lofi para estudiar y concentrarse · 1 hora', 'Café Lo-Fi'],
  ['Tears of Steel (4K)', 'Blender'],
  ['Cosmos Laundromat - First Cycle', 'Blender'],
  ['Spring - Blender Open Movie', 'Blender'],
  ['Cómo hacer pan de masa madre en casa', 'Cocina con Vale'],
  ['Atardecer en Cartagena · timelapse', 'Viajes en 4K'],
  ['Clase 12: funciones y bucles', 'Programa Fácil'],
];

function baseJob(over) {
  return {
    id: newId(), title: SAMPLE_TITLE, uploader: 'Blender', thumbnail: BUNNY, site: 'YouTube',
    option: 'recommended', container: 'mp4', height: 1080,
    state: 'queued', step: null, postprocessor: null,
    progress: null, downloaded: 0, total: 0, speed: null, eta: null,
    file_name: null, file_exists: false, error: null, auto: null, notes: [],
    created_at: nowSec(), finished_at: null,
    ...over,
  };
}

function containerFor(option) {
  if (option === 'mp3') return 'mp3';
  if (option === 'm4a') return 'm4a';
  if (option === 'max') return 'mkv';
  const h = /^h(\d+)$/.exec(option);
  if (h) return Number(h[1]) > 1080 ? 'mkv' : 'mp4';
  return 'mp4';
}

function heightFor(option) {
  if (option === 'mp3' || option === 'm4a') return null;
  if (option === 'max') return 2160;
  const h = /^h(\d+)$/.exec(option);
  return h ? Number(h[1]) : 1080;
}

function planFor(option, totalBytes) {
  if (option === 'mp3' || option === 'm4a') {
    return [
      { dl: 'audio', bytes: totalBytes * 1.15 },
      { pp: 'ExtractAudio', ms: 1500 },
      { pp: 'EmbedThumbnail', ms: 600 },
      { pp: 'Metadata', ms: 400 },
      { pp: 'MoveFiles', ms: 300 },
    ];
  }
  const plan = [
    { dl: 'video', bytes: totalBytes * 0.9 },
    { dl: 'audio', bytes: totalBytes * 0.1 },
    { pp: 'Merger', ms: 1700 },
  ];
  if (containerFor(option) === 'mp4') plan.push({ pp: 'VideoRemuxer', ms: 500 });
  plan.push({ pp: 'EmbedThumbnail', ms: 600 }, { pp: 'Metadata', ms: 400 }, { pp: 'MoveFiles', ms: 300 });
  return plan;
}

function fileNameFor(job) {
  const id = job.title.length % 2 ? 'aqz-KE-bpKQ' : 'eRsGyueVLvQ';
  const base = job.title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 120);
  return `${base} [${id}].${job.container}`;
}

// ---------------------------------------------------------------- the mock
export function createMockApi(emit) {
  const lang0 = systemLang();
  const state = {
    version: '2.0.0', lang: lang0, platform: 'win32', settings: { ...DEFAULT_SETTINGS },
    engine: engineView(true), jobs: [], first_run: false,
    update: params.get('update') === '1' ? UPDATE : null,
    notice: params.get('notice') === '1' ? NOTICE : null,
    web_base: 'https://ksnagapp.vercel.app',
  };
  const jobs = new Map(); // id -> JobView
  const sims = new Map(); // id -> simulation
  const tokens = new Map(); // token -> media
  let history = [];
  let clipboard = params.get('clip') === '0' ? null : SAMPLE_URL;
  let probeCount = 0;
  let pendingProbe = null;
  let folderIdx = 0;
  let pumpTimer = 0;
  let engineTimer = 0;
  const engineSims = {};
  const failOnce = new Set(FAIL ? [FAIL] : []);
  let pumpStarted = false;

  const send = (evt) => setTimeout(() => emit(clone(evt)), 0);
  const ok = (extra = {}) => ({ ok: true, ...clone(extra) });
  const fail = (error) => ({ ok: false, error: clone(error) });
  const lang = () => (state.settings.language === 'auto' ? state.lang : state.settings.language);

  // -------- seed history
  const day = 86400;
  const t0 = nowSec();
  const historySeed = [
    ['Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film', 'Blender', 'recommended', 'mp4', 268 * MB, t0 - 1800, true, BUNNY],
    ['Lofi para estudiar y concentrarse · 1 hora', 'Café Lo-Fi', 'mp3', 'mp3', 86 * MB, t0 - 3 * 3600, true, artThumb(5)],
    ['Sintel - Open Movie by Blender', 'Blender', 'max', 'mkv', 1.9 * GB, t0 - day - 3600, true, artThumb(0)],
    ['Cómo hacer pan de masa madre en casa', 'Cocina con Vale', 'h720', 'mp4', 212 * MB, t0 - day - 7200, false, artThumb(2)],
    ['Atardecer en Cartagena · timelapse', 'Viajes en 4K', 'max', 'mkv', 640 * MB, t0 - 3 * day, true, artThumb(7)],
    ['Clase 12: funciones y bucles', 'Programa Fácil', 'recommended', 'mp4', 330 * MB, t0 - 4 * day, true, artThumb(1)],
    ['Tears of Steel (4K)', 'Blender', 'recommended', 'mp4', 402 * MB, t0 - 12 * day, true, artThumb(4)],
    ['Podcast: historias de la ciencia #48', 'Ciencia de Bolsillo', 'm4a', 'm4a', 54 * MB, t0 - 20 * day, true, artThumb(3)],
    ['Cosmos Laundromat - First Cycle', 'Blender', 'h1440', 'mkv', 820 * MB, t0 - 40 * day, true, artThumb(6)],
  ];
  history = historySeed.map(([title, uploader, option, container, size, finished, exists, thumb], i) => ({
    id: `h${i + 1}`, title, uploader, thumbnail: thumb, site: 'YouTube', option, container,
    file_name: `${title.slice(0, 80)} [${['aqz-KE-bpKQ', 'jfKfPfyJRdk', 'eRsGyueVLvQ', 'Xk9d2Lm0PqA'][i % 4]}].${container}`,
    file_exists: exists, size, finished_at: finished, url: SAMPLE_URL,
  }));

  // -------- scenario
  function addJob(over, sim) {
    const job = baseJob(over);
    jobs.set(job.id, job);
    if (sim) sims.set(job.id, sim);
    return job;
  }

  function simFor(option, bytes, extra = {}) {
    return { plan: planFor(option, bytes), idx: -1, left: 0, base: rand(16, 30) * MB, ...extra };
  }

  function setPhase(job, sim, idx) {
    sim.idx = idx;
    const phase = sim.plan[idx];
    if (!phase) return finishJob(job);
    if (phase.dl) {
      const dls = sim.plan.filter((p) => p.dl);
      job.state = 'downloading';
      job.step = { index: dls.indexOf(phase) + 1, total: dls.length, kind: phase.dl };
      job.postprocessor = null;
      job.downloaded = 0;
      job.total = Math.round(phase.bytes);
      job.progress = 0;
      job.speed = sim.base;
      job.eta = phase.bytes / sim.base;
    } else {
      job.state = 'processing';
      job.postprocessor = phase.pp;
      job.progress = null;
      job.speed = null;
      job.eta = null;
      sim.left = phase.ms;
    }
  }

  function finishJob(job) {
    const sim = sims.get(job.id);
    if (sim) {
      // The real backend reports the final file size once the file is moved into place.
      job.total = Math.round(sim.plan.reduce((sum, p) => sum + (p.dl ? p.bytes : 0), 0));
      job.downloaded = job.total;
    }
    job.state = 'done';
    job.step = null;
    job.postprocessor = null;
    job.progress = 1;
    job.speed = null;
    job.eta = null;
    job.file_name = fileNameFor(job);
    job.file_exists = true;
    job.finished_at = nowSec();
    sims.delete(job.id);
    history.unshift({
      id: job.id, title: job.title, uploader: job.uploader, thumbnail: job.thumbnail, site: job.site,
      option: job.option, container: job.container, file_name: job.file_name, file_exists: true,
      size: job.total || null, finished_at: job.finished_at, url: SAMPLE_URL,
    });
  }

  // Seeds for each scenario.
  function seedDownloads(kind) {
    const T = nowSec();
    if (kind === 'S4') {
      const j1 = addJob({ title: SAMPLE_TITLE, created_at: T - 20 }, simFor('recommended', 268 * MB, { base: 18.4 * MB }));
      setPhase(j1, sims.get(j1.id), 0);
      j1.downloaded = j1.total * 0.47; j1.progress = 0.47; j1.eta = (j1.total - j1.downloaded) / j1.speed;
      const [t2, u2] = VIDEO_TITLES[0];
      const j2 = addJob({ title: t2, uploader: u2, thumbnail: artThumb(0), option: 'max', container: 'mkv', height: 2160, created_at: T - 60 }, simFor('max', 1.12 * GB, { base: 24 * MB }));
      setPhase(j2, sims.get(j2.id), 1);
      j2.downloaded = j2.total * 0.62; j2.progress = 0.62; j2.eta = (j2.total - j2.downloaded) / j2.speed;
      const [t3, u3] = VIDEO_TITLES[1];
      const j3 = addJob({ title: t3, uploader: u3, thumbnail: artThumb(5), option: 'mp3', container: 'mp3', height: null, total: 86 * MB, created_at: T - 90 }, simFor('mp3', 86 * MB));
      setPhase(j3, sims.get(j3.id), 1);
      sims.get(j3.id).left = 6000;
      const [t4, u4] = VIDEO_TITLES[2];
      addJob({ title: t4, uploader: u4, thumbnail: artThumb(4), option: 'h720', container: 'mp4', height: 720, created_at: T - 10 }, simFor('h720', 180 * MB));
      const [t5, u5] = VIDEO_TITLES[3];
      addJob({ title: t5, uploader: u5, thumbnail: artThumb(6), state: 'done', progress: 1, total: 402 * MB, downloaded: 402 * MB, file_name: 'Cosmos Laundromat - First Cycle [Y-rmzh0PI3c].mp4', file_exists: true, created_at: T - 600, finished_at: T - 420, notes: ['fallback_quality'] });
      const [t6, u6] = VIDEO_TITLES[4];
      addJob({ title: t6, uploader: u6, thumbnail: artThumb(1), state: 'error', progress: 0.18, created_at: T - 900, error: errorView('http_403', lang0) });
    } else if (kind === 'S5') {
      const j1 = addJob({ title: SAMPLE_TITLE, created_at: T - 5, total: 268 * MB }, simFor('recommended', 268 * MB));
      setPhase(j1, sims.get(j1.id), 5);
      sims.get(j1.id).left = 1400;
      const [t2, u2] = VIDEO_TITLES[0];
      const j2 = addJob({ title: t2, uploader: u2, thumbnail: artThumb(0), option: 'max', container: 'mkv', height: 2160, created_at: T - 30 }, simFor('max', 1.12 * GB, { base: 21 * MB }));
      setPhase(j2, sims.get(j2.id), 0);
      j2.downloaded = j2.total * 0.22; j2.progress = 0.22;
      const [t3, u3] = VIDEO_TITLES[5];
      addJob({ title: t3, uploader: u3, thumbnail: artThumb(2), option: 'h720', container: 'mp4', height: 720, state: 'done', progress: 1, total: 212 * MB, file_name: 'Cómo hacer pan de masa madre en casa [Xk9d2Lm0PqA].mp4', file_exists: true, created_at: T - 400, finished_at: T - 300, notes: ['already_downloaded'] });
    } else if (kind === 'S6') {
      const [t1, u1] = VIDEO_TITLES[4];
      const j1 = addJob({ title: t1, uploader: u1, thumbnail: artThumb(1), created_at: T - 5 }, simFor('recommended', 300 * MB, { failCode: 'http_403' }));
      setPhase(j1, sims.get(j1.id), 0);
      j1.downloaded = j1.total * 0.32; j1.progress = 0.32;
      const s1 = sims.get(j1.id);
      s1.fixing = 'updating_engine'; s1.left = FREEZE ? 1e9 : 20000;
      j1.auto = 'updating_engine'; j1.speed = null; j1.eta = null;
      const [t2, u2] = VIDEO_TITLES[7];
      addJob({ title: t2, uploader: u2, thumbnail: artThumb(5), state: 'error', progress: 0.05, created_at: T - 60, error: errorView('bot_check', lang0) });
      addJob({ title: 'Trailer para mayores (versión sin censura)', uploader: 'Estudio Norte', thumbnail: artThumb(6), state: 'error', created_at: T - 120, error: errorView('age_restricted', lang0) });
      const [t4, u4] = VIDEO_TITLES[6];
      addJob({ title: t4, uploader: u4, thumbnail: artThumb(7), option: 'max', container: 'mkv', height: 2160, state: 'error', progress: 0.91, created_at: T - 300, error: errorView('disk_full', lang0) });
      addJob({ title: 'Clase 11: variables y tipos', uploader: 'Programa Fácil', thumbnail: artThumb(4), state: 'done', progress: 1, total: 188 * MB, file_name: 'Clase 11 variables y tipos [Qw3rTy12345].mp4', file_exists: true, height: 720, created_at: T - 700, finished_at: T - 640, notes: ['fallback_quality', 'cookies_used'] });
      addJob({ title: 'Directo: concierto en la plaza', uploader: 'Radio Andina', thumbnail: artThumb(3), state: 'canceled', progress: 0.4, created_at: T - 1200 });
    }
  }

  function applyScenario() {
    switch (SCENARIO) {
      case 'S0':
        state.first_run = true;
        state.settings.accepted_terms = false;
        state.settings.first_run_done = false;
        state.engine = engineView(false);
        clipboard = null;
        break;
      case 'S2':
      case 'S3':
        clipboard = null;
        break;
      case 'S4':
      case 'S5':
      case 'S6':
        seedDownloads(SCENARIO);
        clipboard = null;
        break;
      case 'S7':
      case 'S8':
      case 'S9':
        clipboard = null;
        break;
      default:
        break;
    }
    if (params.get('clip') === '1') clipboard = SAMPLE_URL;
  }
  applyScenario();

  function snapshot() {
    return { ...state, jobs: [...jobs.values()] };
  }

  // -------- job pump (~4 Hz, like the Python UI pump)
  function activeCount() {
    let n = 0;
    for (const j of jobs.values()) if (['probing', 'downloading', 'processing'].includes(j.state)) n++;
    return n;
  }

  function tick(dt) {
    const changed = new Set();
    const ordered = [...jobs.values()].sort((a, b) => a.created_at - b.created_at);
    for (const job of ordered) {
      const sim = sims.get(job.id);
      if (!sim) continue;
      if (job.state === 'queued') {
        if (activeCount() < state.settings.max_concurrent) {
          job.state = 'probing';
          sim.left = 700;
          changed.add(job);
        }
        continue;
      }
      if (sim.fixing) {
        sim.left -= dt;
        if (sim.left <= 0) {
          if (sim.fixing === 'updating_engine') {
            sim.fixing = 'retrying';
            job.auto = 'retrying';
            sim.left = 1800;
          } else {
            sim.fixing = null;
            job.auto = null;
            job.state = 'error';
            job.speed = null;
            job.eta = null;
            job.error = errorView(sim.failCode || 'http_403', lang());
            sims.delete(job.id);
          }
          changed.add(job);
        }
        continue;
      }
      if (job.state === 'probing') {
        sim.left -= dt;
        if (sim.left <= 0) setPhase(job, sim, 0);
        changed.add(job);
        continue;
      }
      const phase = sim.plan[sim.idx];
      if (!phase) continue;
      if (phase.dl) {
        const speed = Math.max(1 * MB, (job.speed || sim.base) * 0.7 + sim.base * rand(0.8, 1.2) * 0.3);
        job.speed = speed;
        job.downloaded = Math.min(job.total, job.downloaded + (speed * dt) / 1000);
        job.progress = job.total ? job.downloaded / job.total : null;
        job.eta = (job.total - job.downloaded) / speed;
        if (sim.failAt && sim.failAt.idx === sim.idx && job.progress >= sim.failAt.frac) {
          sim.failAt = null;
          sim.fixing = 'updating_engine';
          sim.left = 2600;
          job.auto = 'updating_engine';
          job.speed = null;
          job.eta = null;
        } else if (job.downloaded >= job.total) {
          setPhase(job, sim, sim.idx + 1);
        }
      } else {
        sim.left -= dt;
        if (sim.left <= 0) setPhase(job, sim, sim.idx + 1);
      }
      changed.add(job);
    }
    for (const job of changed) send({ type: 'job', job });
  }

  function startPump() {
    if (pumpStarted) return;
    pumpStarted = true;
    if (FREEZE) return;
    let last = performance.now();
    pumpTimer = setInterval(() => {
      const now = performance.now();
      const dt = Math.min(1000, now - last);
      last = now;
      tick(dt);
    }, 250);
  }

  // -------- engine simulation
  function engineChanged() {
    const c = state.engine.components;
    state.engine.ready = c.ytdlp.state === 'ready' && c.ffmpeg.state === 'ready';
    state.engine.busy = Object.values(c).some((x) => ['checking', 'downloading', 'installing'].includes(x.state));
    send({ type: 'engine', engine: state.engine });
  }

  function startComponent(name, { delay = 0, speed = 20 * MB, version = VERSIONS[name] } = {}) {
    const c = state.engine.components[name];
    engineSims[name] = { phase: 'wait', left: delay, speed, version };
    c.error = null;
    c.state = 'checking';
    c.progress = null;
    c.done = 0;
    c.total = 0;
    ensureEngineTimer();
  }

  function ensureEngineTimer() {
    if (engineTimer || FREEZE) return;
    let last = performance.now();
    engineTimer = setInterval(() => {
      const now = performance.now();
      const dt = now - last;
      last = now;
      let any = false;
      for (const [name, sim] of Object.entries(engineSims)) {
        const c = state.engine.components[name];
        any = true;
        if (sim.phase === 'wait') {
          sim.left -= dt;
          if (sim.left <= 0) {
            sim.phase = 'dl';
            c.state = 'downloading';
            c.total = c.approx_size;
            c.done = 0;
          }
        } else if (sim.phase === 'dl') {
          c.done = Math.min(c.total, c.done + (sim.speed * rand(0.8, 1.2) * dt) / 1000);
          c.progress = c.done / c.total;
          if (failOnce.has(name) && c.progress > 0.4) {
            failOnce.delete(name);
            c.state = 'error';
            c.error = lang() === 'en' ? 'The download was interrupted. Check your connection.' : 'La descarga se interrumpió. Revisa tu conexión.';
            c.progress = null;
            delete engineSims[name];
          } else if (c.done >= c.total) {
            sim.phase = 'install';
            sim.left = name === 'ffmpeg' ? 900 : 450;
            c.state = 'installing';
            c.progress = null;
          }
        } else if (sim.phase === 'install') {
          sim.left -= dt;
          if (sim.left <= 0) {
            c.state = 'ready';
            c.version = sim.version;
            c.source = 'managed';
            c.done = 0;
            c.total = 0;
            c.progress = null;
            delete engineSims[name];
          }
        }
      }
      engineChanged();
      if (!any) {
        clearInterval(engineTimer);
        engineTimer = 0;
      }
    }, 200);
  }

  // -------- helpers for actions
  function jobOrFail(id) {
    const job = jobs.get(id);
    return job ? null : fail({ code: 'not_found', action: 'none', auto: null, title: 'No encontrado', message: '', details: id, texts: {} });
  }

  function setSettingsPatch(patch) {
    for (const [key, value] of Object.entries(patch || {})) {
      if (!(key in DEFAULT_SETTINGS) || key === 'version') throw new Error(`Ajuste desconocido: ${key}`);
      const current = state.settings[key];
      if (typeof current !== typeof value) throw new Error(`${key}: tipo inválido`);
      if (CHOICES[key] && !CHOICES[key].includes(value)) throw new Error(`${key} no admite ${value}`);
      if (key === 'max_concurrent' && (value < 1 || value > 4)) throw new Error('max_concurrent fuera de rango');
    }
    Object.assign(state.settings, patch);
    if ('engine_channel' in patch) state.engine.channel = patch.engine_channel;
    send({ type: 'settings', settings: state.settings });
  }

  function settingsError(err) {
    return fail({ code: 'invalid_settings', action: 'none', auto: null, title: 'Ajuste inválido', message: String(err.message || err), details: '', texts: {} });
  }

  function createJobsFor(media, option, items) {
    const all = media.options.concat(media.more);
    const o = all.find((x) => x.key === option) || media.options[0];
    const failing = /error/i.test(media.url);
    const ids = [];
    const T = nowSec();
    const makeOne = (title, thumb, bytes, offset) => {
      const job = addJob({
        title, uploader: media.uploader, thumbnail: thumb, site: media.site, option,
        container: containerFor(option), height: heightFor(option), created_at: T + offset,
      }, simFor(option, bytes, failing ? { failAt: { idx: 0, frac: 0.3 }, failCode: 'http_403' } : {}));
      ids.push(job.id);
      send({ type: 'job', job });
    };
    if (media.is_playlist && items) {
      const chosen = items === 'all' ? media.entries : media.entries.filter((e) => items.includes(e.id));
      chosen.forEach((e, i) => makeOne(e.title, artThumb(i), (o.kind === 'audio' ? 9 : 160) * MB * (0.6 + (i % 4) * 0.25), i * 0.001));
    } else {
      makeOne(media.title, media.thumbnail, o.size || 120 * MB, 0);
    }
    return ids;
  }

  async function doProbe(url, playlist) {
    probeCount += 1;
    const myProbe = { canceled: false };
    pendingProbe = myProbe;
    const delay = SCENARIO === 'S2' && probeCount === 1 ? 1e9 : SCENARIO === 'S3' && probeCount === 1 ? 250 : rand(1200, 2000);
    const started = performance.now();
    while (performance.now() - started < delay) {
      if (myProbe.canceled) {
        return fail({ code: 'canceled', action: 'none', auto: null, title: '', message: '', details: '', texts: {} });
      }
      await sleep(100);
    }
    pendingProbe = null;
    const l = lang();
    if (/private|privado/i.test(url)) return fail(errorView('private', l));
    if (/bot/i.test(url)) return fail(errorView('bot_check', l));
    if (/nosoportado|unsupported|example\.com/i.test(url)) return fail(errorView('unsupported_url', l));
    if (/unavailable|nodisponible/i.test(url)) return fail(errorView('unavailable', l));
    const token = newId('t');
    let media = playlist && /list=/.test(url) ? playlistMedia(url, token) : sampleMedia(url, token);
    if (/lowq/i.test(url)) {
      media.degraded = true;
      media.options = media.options.map((o) => (o.kind === 'video' ? { ...o, height: 360, fps: 30, size: 22 * MB } : o));
      media.more = media.more.filter((o) => o.kind === 'audio' || o.height <= 360);
    }
    if (/live/i.test(url)) {
      media.is_live = true;
      media.duration = null;
      media.title = 'Concierto en vivo desde la plaza';
      media.options = media.options.map((o) => ({ ...o, size: null }));
    }
    if (/adult|18/i.test(url)) media.age_limit = 18;
    tokens.set(token, media);
    return ok({ media });
  }

  if (params.get('close') === '1') {
    setTimeout(() => send({ type: 'confirm_close', active: Math.max(1, activeCount()) }), 1500);
  }

  const api = {
    async ui_ready() {
      await sleep(120);
      startPump();
      if (SCENARIO === 'S0' && state.settings.accepted_terms && !state.engine.ready) startSetup();
      return ok({ state: snapshot() });
    },
    async get_state() {
      return ok({ state: snapshot() });
    },
    async accept_terms() {
      await sleep(150);
      state.settings.accepted_terms = true;
      send({ type: 'settings', settings: state.settings });
      return ok({ settings: state.settings });
    },
    async finish_onboarding(patch) {
      await sleep(250);
      try {
        setSettingsPatch({ ...(patch || {}) });
      } catch (err) {
        return settingsError(err);
      }
      state.settings.first_run_done = true;
      state.first_run = false;
      send({ type: 'settings', settings: state.settings });
      return ok({ settings: state.settings });
    },
    async engine_setup() {
      await sleep(100);
      startSetup();
      return ok();
    },
    async engine_update(force) {
      await sleep(150);
      if (engineSims.ytdlp) return ok();
      const next = force ? '2026.10.09.004512' : VERSIONS.ytdlp;
      startComponent('ytdlp', { delay: 600, speed: 2.5 * MB, version: next });
      engineChanged();
      return ok();
    },
    async engine_repair(component) {
      await sleep(150);
      const names = component ? [component] : ['ytdlp', 'ffmpeg'];
      names.forEach((n, i) => {
        if (state.engine.components[n]) startComponent(n, { delay: 300 + i * 300, speed: n === 'ytdlp' ? 2.5 * MB : 26 * MB });
      });
      engineChanged();
      return ok();
    },
    async probe(url, playlist) {
      return doProbe(String(url), Boolean(playlist));
    },
    async cancel_probe() {
      if (pendingProbe) pendingProbe.canceled = true;
      pendingProbe = null;
      return ok();
    },
    async enqueue(token, option, items) {
      await sleep(180);
      const media = tokens.get(token);
      if (!media) return fail({ code: 'expired', action: 'none', auto: null, title: '', message: '', details: 'token', texts: { es: { title: 'El análisis venció', message: 'Vuelve a pegar el enlace.' }, en: { title: 'The analysis expired', message: 'Paste the link again.' } } });
      return ok({ job_ids: createJobsFor(media, option, items) });
    },
    async quick_download(url) {
      const res = await doProbe(String(url), false);
      if (!res.ok) return res;
      return ok({ job_ids: createJobsFor(res.media, state.settings.favorite_preset, null) });
    },
    async cancel(id) {
      const missing = jobOrFail(id);
      if (missing) return missing;
      const job = jobs.get(id);
      sims.delete(id);
      Object.assign(job, { state: 'canceled', speed: null, eta: null, auto: null, postprocessor: null });
      send({ type: 'job', job });
      return ok();
    },
    async remove(id) {
      const missing = jobOrFail(id);
      if (missing) return missing;
      sims.delete(id);
      jobs.delete(id);
      send({ type: 'job_removed', id });
      return ok();
    },
    async retry(id, mode) {
      const missing = jobOrFail(id);
      if (missing) return missing;
      const job = jobs.get(id);
      const notes = new Set(job.notes || []);
      if (mode === 'with_cookies') notes.add('cookies_used');
      if (mode === 'best') notes.add('fallback_quality');
      Object.assign(job, { state: 'queued', error: null, auto: null, progress: null, downloaded: 0, total: 0, speed: null, eta: null, step: null, postprocessor: null, notes: [...notes] });
      sims.set(id, simFor(job.option, 180 * MB));
      send({ type: 'job', job });
      return ok();
    },
    async cancel_all() {
      for (const job of jobs.values()) {
        if (['queued', 'probing', 'downloading', 'processing'].includes(job.state)) {
          sims.delete(job.id);
          Object.assign(job, { state: 'canceled', speed: null, eta: null, auto: null });
          send({ type: 'job', job });
        }
      }
      return ok();
    },
    async clear_finished() {
      for (const job of [...jobs.values()]) {
        if (job.state === 'done' || job.state === 'canceled') {
          jobs.delete(job.id);
          send({ type: 'job_removed', id: job.id });
        }
      }
      return ok();
    },
    async open_file(id) {
      console.info('[mock] open_file', id);
      return ok();
    },
    async show_in_folder(id) {
      console.info('[mock] show_in_folder', id);
      return ok();
    },
    async open_source(id) {
      console.info('[mock] open_source', id);
      return ok();
    },
    async copy_details(id) {
      const job = jobs.get(id);
      const lines = [
        `K-Snag ${state.version} (win32)`,
        `Trabajo: ${id}`,
        job ? `Título: ${job.title}` : '',
        job ? `Opción: ${job.option} · Estado: ${job.state}` : '',
        job && job.error ? `Error: ${job.error.code}\n${job.error.details}` : '',
        `Motor: yt-dlp ${state.engine.components.ytdlp.version} · FFmpeg ${state.engine.components.ffmpeg.version} · Deno ${state.engine.components.deno.version}`,
      ].filter(Boolean);
      return ok({ text: lines.join('\n') });
    },
    async open_help(code) {
      console.info('[mock] open_help', code);
      return ok();
    },
    async open_page(key) {
      console.info('[mock] open_page', key);
      return ok();
    },
    async choose_folder() {
      await sleep(300);
      const folders = ['C:\\Users\\Ana\\Videos\\K-Snag', 'D:\\Descargas\\Videos', 'C:\\Users\\Ana\\Downloads\\K-Snag'];
      state.settings.download_dir = folders[folderIdx++ % folders.length];
      send({ type: 'settings', settings: state.settings });
      return ok({ settings: state.settings });
    },
    async choose_cookies_file() {
      await sleep(300);
      state.settings.cookies_file = 'C:\\Users\\Ana\\Documents\\cookies.txt';
      send({ type: 'settings', settings: state.settings });
      return ok({ settings: state.settings });
    },
    async clear_cookies_file() {
      state.settings.cookies_file = '';
      send({ type: 'settings', settings: state.settings });
      return ok({ settings: state.settings });
    },
    async save_settings(patch) {
      try {
        setSettingsPatch(patch);
      } catch (err) {
        return settingsError(err);
      }
      return ok({ settings: state.settings });
    },
    async read_clipboard() {
      return ok({ url: clipboard });
    },
    async history(query) {
      await sleep(80);
      const q = String(query || '').trim().toLowerCase();
      const items = q ? history.filter((it) => `${it.title} ${it.uploader}`.toLowerCase().includes(q)) : history;
      return ok({ items });
    },
    async history_remove(id) {
      history = history.filter((it) => it.id !== id);
      return ok();
    },
    async history_clear() {
      history = [];
      return ok();
    },
    async check_app_update() {
      await sleep(700);
      return ok({ update: params.get('update') === '1' ? UPDATE : null });
    },
    async apply_app_update() {
      await sleep(900);
      return ok();
    },
    async confirm_close() {
      console.info('[mock] confirm_close: la app se cerraría aquí');
      return ok();
    },
  };

  function startSetup() {
    const c = state.engine.components;
    if (c.ytdlp.state !== 'ready' && !engineSims.ytdlp) startComponent('ytdlp', { delay: 400, speed: 2 * MB });
    if (c.ffmpeg.state !== 'ready' && !engineSims.ffmpeg) startComponent('ffmpeg', { delay: 1200, speed: 21 * MB });
    if (c.deno.state !== 'ready' && !engineSims.deno) startComponent('deno', { delay: 1800, speed: 11 * MB });
    engineChanged();
  }

  // Debug hooks for manual testing in the browser console.
  window.__kdMock = {
    emit: (evt) => send(evt),
    confirmClose: () => send({ type: 'confirm_close', active: Math.max(1, activeCount()) }),
    incoming: (url) => send({ type: 'incoming_url', url: url || SAMPLE_URL }),
    update: () => { state.update = UPDATE; send({ type: 'update', update: UPDATE }); },
    notice: () => { state.notice = NOTICE; send({ type: 'notice', notice: NOTICE }); },
    setClipboard: (url) => { clipboard = url; },
    state: () => clone(snapshot()),
  };

  return api;
}

export function scenarioUi() {
  const s = SCENARIO;
  const out = { scenario: s, route: null, probeUrl: null, autoProbe: false };
  if (s === 'S2') Object.assign(out, { probeUrl: SAMPLE_URL, autoProbe: true });
  if (s === 'S3') Object.assign(out, { probeUrl: params.get('list') === '1' ? `${SAMPLE_URL}&list=PLblenderOpenMovies` : SAMPLE_URL, autoProbe: true });
  if (s === 'S7') out.route = 'history';
  if (s === 'S8') out.route = 'settings';
  if (s === 'S9') out.route = 'help';
  return out;
}
