// "Ya tengo la app": abre un enlace en K-Snag con el protocolo kdownloader://
// y el marcador para arrastrar a la barra de favoritos.

import { el, icon } from './site.js';

const DEEP_LINK_PREFIX = 'kdownloader://download?url=';
const CHECK_AFTER_MS = 1500;
const MAX_URL_LENGTH = 2048;
const EXAMPLE_URL = 'https://www.youtube.com/watch?v=aqz-KE-bpKQ';

/** Valida lo que pegó el usuario. Acepta "youtu.be/abc" agregando https://. */
export function normalizeUrl(raw) {
  let text = String(raw ?? '').trim();
  if (!text) return { error: 'empty' };
  if (!/^[a-z][a-z\d+.-]*:/i.test(text)) text = `https://${text}`;
  if (text.length > MAX_URL_LENGTH) return { error: 'invalid' };
  let url;
  try {
    url = new URL(text);
  } catch {
    return { error: 'invalid' };
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return { error: 'invalid' };
  if (!url.hostname.includes('.') || url.username || url.password) return { error: 'invalid' };
  return { url: url.href };
}

export function buildDeepLink(url) {
  return DEEP_LINK_PREFIX + encodeURIComponent(url);
}

/**
 * Navega al protocolo y, pasado ~1,5 s, revisa si la página sigue visible y con foco.
 * Si el navegador mostró su diálogo "¿Abrir K-Snag?" o la app se abrió, la ventana
 * pierde el foco; si nada pasó, lo más probable es que la app no esté instalada.
 */
function launch(deepLink, onDone) {
  let left = false;
  const markLeft = () => {
    left = true;
  };
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') left = true;
  };
  window.addEventListener('blur', markLeft);
  window.addEventListener('pagehide', markLeft);
  document.addEventListener('visibilitychange', onVisibility);

  window.location.href = deepLink;

  window.setTimeout(() => {
    window.removeEventListener('blur', markLeft);
    window.removeEventListener('pagehide', markLeft);
    document.removeEventListener('visibilitychange', onVisibility);
    const stillHere = !left && document.visibilityState === 'visible' && document.hasFocus();
    onDone(!stillHere);
  }, CHECK_AFTER_MS);
}

function statusLine(iconNode, text) {
  return el('p', { class: 'status-line' }, iconNode, el('span', { text }));
}

function notOpenedCallout() {
  return el(
    'div',
    { class: 'callout warning' },
    icon('alert'),
    el(
      'div',
      {},
      el('strong', { text: '¿No se abrió? Instala K-Snag primero.' }),
      el('p', { text: 'Si ya la instalaste y tu navegador te preguntó, elige «Abrir K-Snag».' }),
      el(
        'p',
        { class: 'callout-actions' },
        el('a', { href: '/descargar', text: 'Descargar para Windows' }),
        el('a', { href: '#instalar', text: 'Cómo instalarla' }),
      ),
    ),
  );
}

export function initOpenForm(form) {
  if (!form) return;
  const input = form.querySelector('input');
  const field = form.querySelector('.open-field');
  const status = document.getElementById(form.getAttribute('data-status') || 'open-status');
  const button = form.querySelector('button[type="submit"]');
  let busy = false;

  const setInvalid = (invalid) => {
    field?.classList.toggle('is-invalid', invalid);
    if (invalid) input.setAttribute('aria-invalid', 'true');
    else input.removeAttribute('aria-invalid');
  };

  input.addEventListener('input', () => {
    setInvalid(false);
    if (status?.querySelector('.status-error')) status.replaceChildren();
  });

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    if (busy) return;
    const result = normalizeUrl(input.value);
    if (result.error) {
      setInvalid(true);
      const message =
        result.error === 'empty'
          ? 'Pega primero el enlace de un video.'
          : 'Ese enlace no parece válido. Cópialo desde la barra de direcciones o el botón Compartir.';
      status?.replaceChildren(el('p', { class: 'status-line status-error', text: message }));
      input.focus();
      return;
    }

    busy = true;
    button?.setAttribute('aria-busy', 'true');
    input.value = result.url;
    const spinner = el('span', { class: 'spinner', 'aria-hidden': 'true' });
    status?.replaceChildren(statusLine(spinner, 'Abriendo K-Snag… Si tu navegador pregunta, elige «Abrir».'));

    launch(buildDeepLink(result.url), (opened) => {
      busy = false;
      button?.removeAttribute('aria-busy');
      if (!status) return;
      if (opened) {
        status.replaceChildren(statusLine(icon('circle-check'), 'Listo. Sigue en K-Snag para elegir la calidad.'));
      } else {
        status.replaceChildren(notOpenedCallout());
      }
    });
  });
}

/** Llena la caja con un video de licencia libre (Big Buck Bunny, Blender Foundation, CC BY). */
export function initExampleButton(button, input) {
  if (!button || !input) return;
  button.addEventListener('click', () => {
    input.value = EXAMPLE_URL;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.focus();
  });
}

export function initBookmarklet(link, hint) {
  if (!link) return;
  let timer = 0;
  link.addEventListener('click', (event) => {
    // Aquí no hace nada: hay que arrastrarlo a la barra de favoritos.
    event.preventDefault();
    if (hint) hint.textContent = 'Arrástralo a tu barra de favoritos; aquí no hace falta hacer clic.';
    link.classList.remove('is-nudged');
    void link.offsetWidth; // reinicia la animación
    link.classList.add('is-nudged');
    window.clearTimeout(timer);
    timer = window.setTimeout(() => {
      if (hint) hint.textContent = '';
    }, 6000);
  });
  link.addEventListener('dragstart', () => {
    if (hint) hint.textContent = 'Suéltalo en tu barra de favoritos.';
  });
  link.addEventListener('dragend', () => {
    if (hint) hint.textContent = '';
  });
}
