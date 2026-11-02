// Página /android: completa versión, tamaños, huellas SHA-256 y enlaces directos a los APK.
// Sin JavaScript (o si /data/android.json falla) quedan los enlaces /android/descargar/... del HTML.
import { initSite } from './site.js';
import { detectPlatform, isInAppBrowser, suggestedAbi } from './platform.js';
import { ABIS, formatFingerprint, formatSize, loadAndroidRelease } from './android-release.js';

const all = (selector) => document.querySelectorAll(selector);

async function main() {
  initSite();
  const platform = detectPlatform();
  // Bloques que solo tienen sentido en cierta plataforma: data-show-on="ios", "android", "windows other"…
  for (const node of all('[data-show-on]')) {
    node.hidden = !node.dataset.showOn.split(/\s+/).includes(platform);
  }
  if (platform === 'android' && isInAppBrowser()) {
    for (const node of all('[data-inapp-tip]')) node.hidden = false;
  }

  const [release, abi] = await Promise.all([loadAndroidRelease(), platform === 'android' ? suggestedAbi() : 'arm64-v8a']);
  if (!release) return;

  for (const node of all('[data-android-version]')) node.textContent = release.version;
  for (const a of ABIS) {
    const apk = release.apks[a];
    for (const link of all(`a[data-apk="${a}"]`)) {
      if (apk) link.href = apk.url;
      else link.closest('[data-apk-row]')?.setAttribute('hidden', '');
    }
    for (const node of all(`[data-apk-size="${a}"]`)) node.textContent = apk ? formatSize(apk.size) : '';
    for (const node of all(`[data-apk-sha="${a}"]`)) node.textContent = apk ? apk.sha256 : '';
  }
  // El botón principal ofrece la ABI sugerida (casi siempre arm64-v8a).
  const mainLink = document.getElementById('apk-main');
  const chosen = release.apks[abi] ?? release.apks['arm64-v8a'];
  if (mainLink) mainLink.href = chosen.url;
  const size = document.getElementById('apk-main-size');
  if (size) size.textContent = formatSize(chosen.size);
  for (const node of all('[data-android-cert]')) node.textContent = formatFingerprint(release.certSha256);
  if (release.notesUrl) {
    for (const link of all('a[data-android-notes]')) link.href = release.notesUrl;
  }
}

main();
