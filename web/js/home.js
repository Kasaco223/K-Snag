// Página de inicio.
import { initSite } from './site.js';
import { renderReleaseLabel } from './release.js';
import { initBookmarklet, initExampleButton, initOpenForm } from './open-app.js';
import { detectPlatform } from './platform.js';
import { initTour } from './tour.js';
import { initFeatures } from './features.js';

const $ = (id) => document.getElementById(id);

/** El enlace del hero lleva a la guía de tu sistema (el botón de descarga está al final de ella). */
function initPlatformHero(platform) {
  document.documentElement.dataset.platform = platform;
  if (platform === 'ios') {
    $('hero-ios')?.removeAttribute('hidden');
    return;
  }
  const label = $('cta-install-label');
  if (label && platform === 'android') label.textContent = 'Mira cómo instalarla en Android';
  else if (label && platform === 'windows') label.textContent = 'Mira cómo instalarla en Windows';
}

initSite();
const platform = detectPlatform();
initPlatformHero(platform);
initTour(platform);
initFeatures(platform);
initOpenForm($('open-form'));
initExampleButton($('open-example'), $('open-url'));
initBookmarklet($('bookmarklet'), $('bookmarklet-hint'));
renderReleaseLabel($('release-label'));
