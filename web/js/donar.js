// /donar: pestañas «Desde Colombia» / «Desde otro país» y botón para copiar la llave Bre-B.
// Sin JavaScript se ven las dos secciones completas, una debajo de la otra.
import { initSite, storage } from './site.js';

initSite();

const KEY = 'kd-donate-tab';

/** Colombia si el navegador lo sugiere (zona horaria o idioma es-CO); nunca se consulta la ubicación. */
function guessColombia() {
  try {
    if (Intl.DateTimeFormat().resolvedOptions().timeZone === 'America/Bogota') return true;
  } catch {
    /* sin Intl: se mira solo el idioma */
  }
  return (navigator.languages || [navigator.language || '']).some((l) => /-co$/i.test(l));
}

function initTabs() {
  const list = document.querySelector('.donate-tabs');
  if (!list) return;
  const tabs = [...list.querySelectorAll('[role="tab"]')];
  const panels = tabs.map((t) => document.getElementById(t.getAttribute('aria-controls')));

  const select = (index, focus = false) => {
    tabs.forEach((tab, i) => {
      const on = i === index;
      tab.setAttribute('aria-selected', String(on));
      tab.tabIndex = on ? 0 : -1;
      panels[i].hidden = !on;
    });
    if (focus) tabs[index].focus();
    storage.set(KEY, tabs[index].id);
  };

  tabs.forEach((tab, i) => {
    tab.addEventListener('click', () => select(i));
    tab.addEventListener('keydown', (e) => {
      const last = tabs.length - 1;
      let next = null;
      if (e.key === 'ArrowRight') next = i === last ? 0 : i + 1;
      else if (e.key === 'ArrowLeft') next = i === 0 ? last : i - 1;
      else if (e.key === 'Home') next = 0;
      else if (e.key === 'End') next = last;
      if (next !== null) {
        e.preventDefault();
        select(next, true);
      }
    });
  });

  // Con pestañas, el título de cada sección sobra (lo dice la pestaña).
  document.querySelectorAll('.donate-panel-title').forEach((h) => { h.hidden = true; });
  list.hidden = false;
  const saved = tabs.findIndex((t) => t.id === storage.get(KEY));
  select(saved >= 0 ? saved : (guessColombia() ? 0 : 1));
}

function initCopy() {
  document.querySelectorAll('[data-copy]').forEach((button) => {
    const status = button.closest('.donate-card')?.querySelector('.donate-copied');
    const label = button.querySelector('span');
    button.addEventListener('click', async () => {
      let ok = false;
      try {
        await navigator.clipboard.writeText(button.dataset.copy);
        ok = true;
      } catch {
        // Sin portapapeles: se selecciona la llave para copiarla a mano.
        const code = button.parentElement.querySelector('code');
        if (code) window.getSelection()?.selectAllChildren(code);
      }
      if (label) label.textContent = ok ? '¡Copiada!' : 'Selecciónala y cópiala';
      if (status) status.textContent = ok ? 'Llave copiada. Pégala en la app de tu banco.' : '';
      window.clearTimeout(button._timer);
      button._timer = window.setTimeout(() => {
        if (label) label.textContent = button.dataset.copyLabel;
        if (status) status.textContent = '';
      }, 2500);
    });
  });
}

initTabs();
initCopy();
