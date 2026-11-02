// Página de inicio.
import { el, initSite } from './site.js';
import { renderReleaseLabel } from './release.js';
import { initBookmarklet, initExampleButton, initOpenForm } from './open-app.js';
import { detectPlatform } from './platform.js';

const $ = (id) => document.getElementById(id);

/** En Android, la descarga principal es la de Android (lleva a /android); en iPhone se explica que no se puede. */
function initPlatformCta(platform) {
  document.documentElement.dataset.platform = platform;
  if (platform === 'ios') {
    $('hero-ios')?.removeAttribute('hidden');
    return;
  }
  if (platform !== 'android') return;
  const android = $('cta-android');
  const windows = $('cta-download');
  if (android && windows) {
    android.classList.replace('btn-secondary', 'btn-primary');
    windows.classList.replace('btn-primary', 'btn-secondary');
    windows.before(android); // primero el de Android
  }
  $('hero-note')?.replaceChildren(
    'Después de descargarla, Android te pedirá permiso para instalarla. ',
    el('a', { href: '/android#instalar', text: 'Mira los pasos' }),
    '.',
  );
}

initSite();
initPlatformCta(detectPlatform());
initOpenForm($('open-form'));
initExampleButton($('open-example'), $('open-url'));
initBookmarklet($('bookmarklet'), $('bookmarklet-hint'));
renderReleaseLabel($('release-label'));
