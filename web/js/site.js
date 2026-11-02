// Comportamiento común a todas las páginas.

/** Acceso a localStorage que nunca lanza (modo privado, almacenamiento bloqueado, etc.). */
export const storage = {
  get(key) {
    try {
      return window.localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key, value) {
    try {
      window.localStorage.setItem(key, value);
    } catch {
      /* sin almacenamiento: no pasa nada */
    }
  },
};

/** Crea un ícono del sprite de Lucide (/assets/icons.svg). */
export function icon(name, extraClass = '') {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('class', `icon ${extraClass}`.trim());
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS(ns, 'use');
  use.setAttribute('href', `/assets/icons.svg#i-${name}`);
  svg.append(use);
  return svg;
}

/** Atajo para crear elementos con texto seguro (siempre textContent, nunca innerHTML). */
export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value == null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key in node && typeof value !== 'string') node[key] = value;
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children) {
    if (child == null || child === false) continue;
    node.append(typeof child === 'string' ? document.createTextNode(child) : child);
  }
  return node;
}

export function prefersReducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function initHeader() {
  const header = document.querySelector('.site-header');
  if (!header) return;
  let ticking = false;
  const update = () => {
    header.classList.toggle('is-scrolled', window.scrollY > 8);
    ticking = false;
  };
  update();
  window.addEventListener(
    'scroll',
    () => {
      if (!ticking) {
        ticking = true;
        window.requestAnimationFrame(update);
      }
    },
    { passive: true },
  );
}

/**
 * Después de tocar un enlace de descarga (Windows o Android), un aviso corto abajo: la descarga empezó y, si la
 * app le sirve, puede apoyarla. No tapa nada, se cierra solo y aparece una vez por página.
 */
function initDownloadThanks() {
  if (window.location.pathname.startsWith('/donar')) return;
  let shown = false;
  document.addEventListener('click', (event) => {
    const link = event.target instanceof Element ? event.target.closest('a[href]') : null;
    // Los botones de la guía de inicio muestran su propio aviso (data-no-thanks).
    if (!link || shown || link.hasAttribute('data-no-thanks')) return;
    const path = new URL(link.href, window.location.href).pathname;
    if (!/^\/(android\/)?descargar(\/|$)/.test(path)) return;
    shown = true;
    // Después del clic, para no demorar la descarga.
    window.setTimeout(() => showDownloadThanks(path.startsWith('/android')), 600);
  });
}

function showDownloadThanks(android) {
  const close = el('button', { type: 'button', class: 'toast-close', 'aria-label': 'Cerrar' }, icon('x', 'icon-sm'));
  const toast = el(
    'div',
    { class: 'download-toast', role: 'status' },
    el('span', { class: 'toast-icon' }, icon('circle-check')),
    el(
      'div',
      { class: 'toast-text' },
      el('strong', { text: android ? 'Tu APK se está descargando' : 'Tu descarga empezó' }),
      el('span', { text: 'Si K-Snag te sirve, puedes apoyarlo con lo que quieras.' }),
    ),
    el('a', { class: 'btn btn-secondary btn-sm', href: '/donar' }, icon('heart', 'icon-sm'), 'Donar'),
    close,
  );
  let timer = 0;
  const hide = () => {
    window.clearTimeout(timer);
    toast.classList.remove('is-visible');
    window.setTimeout(() => toast.remove(), prefersReducedMotion() ? 0 : 250);
  };
  close.addEventListener('click', hide);
  document.body.append(toast);
  window.requestAnimationFrame(() => toast.classList.add('is-visible'));
  timer = window.setTimeout(hide, 15000);
  toast.addEventListener('mouseenter', () => window.clearTimeout(timer));
  toast.addEventListener('focusin', () => window.clearTimeout(timer));
}

export function initSite() {
  initHeader();
  initDownloadThanks();
}
