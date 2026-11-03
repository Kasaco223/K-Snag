// Guía de instalación de la página de inicio (#instalar): pestañas Windows / Android, animaciones al hacer
// scroll con GSAP + ScrollTrigger (js/vendor/, copiados del paquete oficial de npm) y el aviso después de
// descargar con el botón de donar.
//
// Sin JavaScript, sin GSAP o con «reducir movimiento» la guía queda completa y quieta: el HTML trae los dos
// sistemas uno debajo del otro y cada pantalla marca con un anillo lo que hay que tocar.
//
// Las pantallas de ejemplo (.scr) se animan con atributos:
//   data-tap="n"  lo que el puntero toca en el paso n (1, 2…);
//   data-in="n"   aparece después del toque n (0 = al empezar);
//   data-out="n"  desaparece después del toque n;
//   data-on="n"   recibe .is-on después del toque n (casillas, interruptores);
//   .scr-fill     barra que se llena cuando aparece su contenedor.
import { prefersReducedMotion } from './site.js';
import { isInAppBrowser, suggestedAbi } from './platform.js';
import { formatSize, loadAndroidRelease } from './android-release.js';

const OS_LIST = ['windows', 'android'];

/** Posición de `node` dentro de `root` sin contar transformaciones (offsetLeft/Top). */
function offsetIn(node, root) {
  let x = 0;
  let y = 0;
  for (let n = node; n && n !== root; n = n.offsetParent) {
    x += n.offsetLeft;
    y += n.offsetTop;
  }
  return { x, y };
}

function centerOf(node, root) {
  const { x, y } = offsetIn(node, root);
  return { x: x + node.offsetWidth / 2, y: y + node.offsetHeight / 2 };
}

function cursorSvg() {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', 'M3 2v18.5l5.2-4.9 3.6 7.9 3.3-1.5-3.6-7.7H19Z');
  path.setAttribute('fill', '#fff');
  path.setAttribute('stroke', '#111');
  path.setAttribute('stroke-width', '1.4');
  path.setAttribute('stroke-linejoin', 'round');
  svg.append(path);
  return svg;
}

/** Línea de tiempo que repite la secuencia de toques de una pantalla. Empieza en pausa. */
function screenTimeline(gsap, scr) {
  const phone = scr.classList.contains('scr-phone');
  const pointer = document.createElement('span');
  pointer.className = phone ? 'scr-pointer scr-finger' : 'scr-pointer scr-cursor';
  if (!phone) pointer.append(cursorSvg());
  const ripple = document.createElement('span');
  ripple.className = 'scr-ripple';
  scr.append(ripple, pointer);

  const all = (selector) => [...scr.querySelectorAll(selector)];
  const at = (attr, n) => all(`[${attr}="${n}"]`);
  const taps = all('[data-tap]').sort((a, b) => Number(a.dataset.tap) - Number(b.dataset.tap));
  const fillsIn = (nodes) => nodes.flatMap((node) => [...node.querySelectorAll('.scr-fill')]);
  // El dedo o el puntero descansan abajo a la derecha, fuera del contenido.
  const rest = () => ({ x: scr.offsetWidth * (phone ? 0.78 : 0.86), y: scr.offsetHeight * (phone ? 0.86 : 0.9) });

  const intro = at('data-in', 0);
  const later = all('[data-in]').filter((node) => node.dataset.in !== '0');
  const outs = all('[data-out]');
  const fills = all('.scr-fill');
  const reset = () => {
    for (const node of all('[data-on]')) node.classList.remove('is-on');
    for (const node of taps) node.classList.remove('is-target');
  };
  const tl = gsap.timeline({
    paused: true,
    repeat: -1,
    repeatDelay: 1.6,
    defaults: { ease: 'power2.out' },
    onRepeat: reset,
  });
  // Estado inicial, ya aplicado antes de que la pantalla entre en vista (si no, se vería resuelta).
  const start = (set) => {
    if (later.length) set(later, { autoAlpha: 0, y: 0 });
    if (outs.length) set(outs, { autoAlpha: 1, y: 0 });
    if (fills.length) set(fills, { scaleX: 0 });
    set(pointer, { autoAlpha: 0, scale: 1, x: () => rest().x, y: () => rest().y });
  };
  reset();
  start((targets, vars) => gsap.set(targets, vars));
  if (intro.length) gsap.set(intro, { autoAlpha: 0 });
  start((targets, vars) => tl.set(targets, vars, 0));

  if (intro.length) {
    tl.fromTo(intro, { autoAlpha: 0, y: 14 }, { autoAlpha: 1, y: 0, duration: 0.45, stagger: 0.45 }, 0.2);
    const introFills = fillsIn(intro);
    if (introFills.length) tl.to(introFills, { scaleX: 1, duration: 1.2, ease: 'power1.inOut' });
  }

  for (const target of taps) {
    const n = target.dataset.tap;
    tl.to(pointer, { autoAlpha: 1, duration: 0.25 }, '+=0.35');
    tl.call(() => target.classList.add('is-target'), null, '<');
    tl.to(pointer, {
      x: () => centerOf(target, scr).x,
      y: () => centerOf(target, scr).y,
      duration: 0.8,
      ease: 'power3.inOut',
    });
    // Pulsación: el puntero se achica, el botón se hunde y sale una onda.
    tl.to(pointer, { scale: 0.8, duration: 0.1, ease: 'power1.in' }, '+=0.1');
    tl.to(target, { scale: 0.95, duration: 0.1, ease: 'power1.in' }, '<');
    tl.fromTo(
      ripple,
      { x: () => centerOf(target, scr).x, y: () => centerOf(target, scr).y, scale: 0.2, autoAlpha: 0.8 },
      { scale: 1.9, autoAlpha: 0, duration: 0.55, immediateRender: false },
      '<',
    );
    tl.to([pointer, target], { scale: 1, duration: 0.2 }, '<0.1');
    tl.call(
      () => {
        target.classList.remove('is-target');
        for (const node of at('data-on', n)) node.classList.add('is-on');
      },
      null,
      '<',
    );
    const leaving = at('data-out', n);
    const coming = at('data-in', n);
    if (leaving.length) tl.to(leaving, { autoAlpha: 0, y: -10, duration: 0.3 }, '<0.1');
    if (coming.length) {
      tl.fromTo(coming, { autoAlpha: 0, y: 12 }, { autoAlpha: 1, y: 0, duration: 0.4, stagger: 0.12 }, leaving.length ? '<0.15' : '<0.1');
      const comingFills = fillsIn(coming);
      if (comingFills.length) tl.to(comingFills, { scaleX: 1, duration: 1.4, ease: 'power1.inOut' });
    }
  }
  tl.to(pointer, { autoAlpha: 0, duration: 0.3 }, '+=1');
  tl.to({}, { duration: 0.8 });

  return {
    tl,
    destroy() {
      tl.kill();
      reset();
      pointer.remove();
      ripple.remove();
    },
  };
}

/** Animaciones de un panel (Windows o Android). Devuelve la función que las deshace. */
function animatePanel(gsap, ScrollTrigger, panel) {
  const screens = [];
  const ctx = gsap.context(() => {
    const track = panel.querySelector('.tour-track');
    const fill = panel.querySelector('.tour-rail-fill');
    if (track && fill) {
      gsap.fromTo(
        fill,
        { scaleY: 0 },
        { scaleY: 1, ease: 'none', scrollTrigger: { trigger: track, start: 'top 60%', end: 'bottom 60%', scrub: 0.5 } },
      );
    }

    for (const step of panel.querySelectorAll('.tour-step')) {
      const text = step.querySelector('.tour-text');
      const visual = step.querySelector('.tour-visual');
      const enter = { trigger: step, start: 'top 82%', once: true };
      if (text) gsap.from(text.children, { autoAlpha: 0, y: 28, duration: 0.6, stagger: 0.08, ease: 'power3.out', scrollTrigger: enter });
      if (visual) gsap.from(visual, { autoAlpha: 0, y: 56, scale: 0.94, duration: 0.85, ease: 'power3.out', scrollTrigger: { ...enter } });
      // El número se enciende al llegar al paso y se queda encendido.
      ScrollTrigger.create({
        trigger: step,
        start: 'top 62%',
        onEnter: () => step.classList.add('is-lit'),
        onLeaveBack: () => step.classList.remove('is-lit'),
      });

      const scr = step.querySelector('.scr');
      if (!scr) continue;
      const screen = screenTimeline(gsap, scr);
      screens.push(screen);
      // La secuencia solo corre mientras la pantalla se ve.
      ScrollTrigger.create({
        trigger: scr,
        start: 'top 88%',
        end: 'bottom 12%',
        onToggle: (self) => (self.isActive ? screen.tl.play() : screen.tl.pause()),
      });
    }

    const cta = panel.querySelector('.tour-cta');
    if (cta) {
      gsap.from(cta.children, {
        autoAlpha: 0,
        y: 24,
        duration: 0.6,
        stagger: 0.1,
        ease: 'power3.out',
        scrollTrigger: { trigger: cta, start: 'top 85%', once: true },
      });
      const button = cta.querySelector('[data-tour-download]');
      if (button) {
        // Un brillo suave que invita a tocar, solo mientras el botón está a la vista.
        const glow = gsap.to(button, {
          boxShadow: '0 0 0 10px rgb(99 102 241 / 0), 0 18px 40px -12px rgb(99 102 241 / 0.85)',
          duration: 1.2,
          repeat: -1,
          yoyo: true,
          ease: 'sine.inOut',
          paused: true,
        });
        ScrollTrigger.create({
          trigger: button,
          start: 'top 90%',
          end: 'bottom 10%',
          onToggle: (self) => (self.isActive ? glow.play() : glow.pause()),
        });
      }
    }
  }, panel);

  // Si cambia el tamaño, los toques se recalculan sobre la nueva posición de los botones.
  const invalidate = () => {
    for (const screen of screens) screen.tl.invalidate();
  };
  ScrollTrigger.addEventListener('refreshInit', invalidate);

  return () => {
    ScrollTrigger.removeEventListener('refreshInit', invalidate);
    for (const screen of screens) screen.destroy();
    ctx.revert();
  };
}

/** Después de tocar Descargar: la tarjeta cambia por "¡Tu descarga empezó!" con el botón de donar. */
function initAfterDownload(panel, gsap) {
  const cta = panel.querySelector('.tour-cta');
  const after = panel.querySelector('.tour-after');
  const button = panel.querySelector('[data-tour-download]');
  if (!cta || !after || !button) return;
  button.addEventListener('click', () => {
    // La descarga ya salió (el enlace sigue su curso); el cambio va un instante después.
    window.setTimeout(() => {
      if (!after.hidden) return;
      const animate = gsap && !prefersReducedMotion();
      const show = () => {
        cta.hidden = true;
        after.hidden = false;
        after.focus({ preventScroll: true });
        if (!animate) return;
        const tl = gsap.timeline({ defaults: { ease: 'power3.out' } });
        tl.from(after, { autoAlpha: 0, y: 20, scale: 0.97, duration: 0.5 })
          .from(after.querySelector('.tour-after-icon'), { scale: 0, rotation: -90, duration: 0.6, ease: 'back.out(2)' }, '<0.1')
          .from(after.querySelectorAll(':scope > :not(.tour-after-icon)'), { autoAlpha: 0, y: 14, duration: 0.45, stagger: 0.08 }, '<0.15')
          .fromTo(
            after.querySelector('.tour-after-donate .btn'),
            { scale: 1 },
            { scale: 1.06, duration: 0.25, repeat: 3, yoyo: true, ease: 'sine.inOut' },
            '+=0.2',
          );
      };
      if (animate) gsap.to(cta, { autoAlpha: 0, y: -12, duration: 0.3, ease: 'power2.in', onComplete: show, clearProps: 'all' });
      else show();
    }, 500);
  });
}

/** Versión, tamaño y enlace directo del APK, de /data/android.json (si falla, queda /android/descargar). */
async function fillAndroid(panel, platform) {
  if (platform === 'android' && isInAppBrowser()) {
    for (const node of panel.querySelectorAll('[data-inapp-tip]')) node.hidden = false;
  }
  const [release, abi] = await Promise.all([loadAndroidRelease(), platform === 'android' ? suggestedAbi() : 'arm64-v8a']);
  if (!release) return;
  const apk = release.apks[abi] ?? release.apks['arm64-v8a'];
  for (const link of panel.querySelectorAll('#cta-android, a[data-apk]')) link.href = apk.url;
  const meta = panel.querySelector('[data-android-meta]');
  if (meta) {
    const size = formatSize(apk.size);
    meta.textContent = `Versión ${release.version}${size ? ` · ${size}` : ''} · Android 8 o superior`;
  }
}

export function initTour(platform) {
  const root = document.getElementById('instalar');
  if (!root) return;
  const panels = Object.fromEntries(OS_LIST.map((os) => [os, root.querySelector(`.tour-panel[data-os="${os}"]`)]));
  const tabs = Object.fromEntries(OS_LIST.map((os) => [os, document.getElementById(`tab-${os}`)]));
  if (!panels.windows || !panels.android || !tabs.windows || !tabs.android) return;

  for (const node of root.querySelectorAll('[data-show-on]')) {
    node.hidden = !node.dataset.showOn.split(/\s+/).includes(platform);
  }

  const { gsap, ScrollTrigger } = window;
  const canAnimate = Boolean(gsap && ScrollTrigger) && !prefersReducedMotion();
  if (canAnimate) {
    gsap.registerPlugin(ScrollTrigger);
    document.documentElement.classList.add('tour-anim');
  }

  let undo = null;
  const select = (os, { focus = false } = {}) => {
    for (const other of OS_LIST) {
      const active = other === os;
      tabs[other].setAttribute('aria-selected', String(active));
      tabs[other].tabIndex = active ? 0 : -1;
      panels[other].hidden = !active;
    }
    if (focus) tabs[os].focus();
    if (!canAnimate) return;
    undo?.();
    undo = animatePanel(gsap, ScrollTrigger, panels[os]);
    ScrollTrigger.refresh();
  };

  // Con JavaScript, un sistema a la vez y pestañas para cambiar.
  root.classList.add('has-tabs');
  root.querySelector('.tour-switch').hidden = false;
  for (const os of OS_LIST) {
    panels[os].setAttribute('role', 'tabpanel');
    panels[os].setAttribute('aria-labelledby', `tab-${os}`);
    tabs[os].addEventListener('click', () => select(os));
    tabs[os].addEventListener('keydown', (event) => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault();
      select(os === 'windows' ? 'android' : 'windows', { focus: true });
    });
  }
  // Enlaces a un sistema en concreto (#instalar-android, #and-3…) abren su pestaña.
  const fromHash = () => {
    const target = window.location.hash ? document.getElementById(decodeURIComponent(window.location.hash.slice(1))) : null;
    return OS_LIST.find((os) => target && panels[os].contains(target)) ?? null;
  };
  window.addEventListener('hashchange', () => {
    const os = fromHash();
    if (os && panels[os].hidden) {
      select(os);
      document.getElementById(decodeURIComponent(window.location.hash.slice(1)))?.scrollIntoView();
    }
  });
  select(fromHash() ?? (platform === 'android' ? 'android' : 'windows'));

  for (const os of OS_LIST) initAfterDownload(panels[os], canAnimate ? gsap : null);
  fillAndroid(panels.android, platform);

  // Parallax suave de la maqueta del hero en pantallas grandes.
  if (canAnimate) {
    gsap.matchMedia().add('(min-width: 1024px)', () => {
      gsap.to('.mock-wrap', {
        yPercent: -10,
        ease: 'none',
        scrollTrigger: { trigger: '.hero', start: 'top top', end: 'bottom top', scrub: true },
      });
    });
  }
}

