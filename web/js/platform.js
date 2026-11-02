// Plataforma de quien visita, solo para elegir qué descarga ofrecer primero. Nunca lanza.
// El HTML ya trae la opción para Windows y enlaces a /android: esto solo los reordena.

/** "android" | "ios" | "windows" | "other" */
export function detectPlatform(nav = globalThis.navigator) {
  try {
    const ua = String(nav?.userAgent ?? '');
    const platform = String(nav?.userAgentData?.platform ?? nav?.platform ?? '');
    if (/android/i.test(platform) || /\bAndroid\b/.test(ua)) return 'android';
    // iPadOS se presenta como "MacIntel", pero con pantalla táctil.
    if (/\b(iPhone|iPad|iPod)\b/.test(ua) || (platform === 'MacIntel' && Number(nav?.maxTouchPoints) > 1)) return 'ios';
    if (/^win/i.test(platform) || /\bWindows NT\b/.test(ua)) return 'windows';
  } catch {
    /* navegador raro: se queda lo que trae el HTML */
  }
  return 'other';
}

/** Navegadores dentro de apps (Instagram, Facebook, TikTok…), que a veces no dejan descargar archivos. */
export function isInAppBrowser(nav = globalThis.navigator) {
  const ua = String(nav?.userAgent ?? '');
  return /\b(FBAN|FBAV|FB_IAB|Instagram|musical_ly|BytedanceWebview|TikTok|Line\/|Snapchat)\b/i.test(ua);
}

/**
 * ABI sugerida para Android. Chrome casi nunca la revela, así que por defecto es arm64-v8a
 * (casi todos los celulares desde 2017). Solo cambia si el navegador dice que es de 32 bits.
 */
export async function suggestedAbi(nav = globalThis.navigator) {
  try {
    const hints = await nav?.userAgentData?.getHighEntropyValues?.(['bitness', 'architecture']);
    if (hints?.architecture === 'x86') return 'x86_64';
    if (hints?.bitness === '32') return 'armeabi-v7a';
  } catch {
    /* sin pistas */
  }
  return 'arm64-v8a';
}
