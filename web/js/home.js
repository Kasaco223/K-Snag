// Página de inicio.
import { initSite } from './site.js';
import { renderReleaseLabel } from './release.js';
import { initBookmarklet, initExampleButton, initOpenForm } from './open-app.js';
import { detectPlatform } from './platform.js';
import { initTour } from './tour.js';

const $ = (id) => document.getElementById(id);

/** El botón del hero lleva a la guía de tu sistema; en iPhone se explica que no se puede. */
function initPlatformHero(platform) {
  document.documentElement.dataset.platform = platform;
  if (platform === 'ios') {
    $('hero-ios')?.removeAttribute('hidden');
    return;
  }
  const label = $('cta-install-label');
  if (label && platform === 'android') label.textContent = 'Instalar en Android';
  else if (label && platform === 'windows') label.textContent = 'Instalar en Windows';
}

initSite();
const platform = detectPlatform();
initPlatformHero(platform);
initTour(platform);
initOpenForm($('open-form'));
initExampleButton($('open-example'), $('open-url'));
initBookmarklet($('bookmarklet'), $('bookmarklet-hint'));
renderReleaseLabel($('release-label'));
