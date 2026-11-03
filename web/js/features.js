// Animaciones de «Cómo funciona» y de las tarjetas de «Funciones» (GSAP + ScrollTrigger de js/vendor/).
// Explican con movimiento lo que antes eran párrafos. Sin GSAP o con «reducir movimiento» no se anima nada y
// el CSS muestra cada tarjeta en su estado final (barras llenas, mensaje en español, sin anuncios…).
// Cada animación corre solo mientras su tarjeta está a la vista.
import { prefersReducedMotion } from './site.js';
import { screenTimeline } from './tour.js';

function whileVisible(ScrollTrigger, trigger, animation) {
  ScrollTrigger.create({
    trigger,
    start: 'top 92%',
    end: 'bottom 8%',
    onToggle: (self) => (self.isActive ? animation.play() : animation.pause()),
  });
}

/** Escena de «Cómo funciona»: celular en Android, PC en lo demás, con los pasos 1-2-3 al lado. */
function initHow(gsap, ScrollTrigger, platform) {
  const root = document.getElementById('como-funciona');
  if (!root) return;
  const os = platform === 'android' ? 'android' : 'windows';
  const scenes = [...root.querySelectorAll('.how-scene')];
  for (const scene of scenes) scene.hidden = scene.dataset.howOs !== os;
  const scr = scenes.find((scene) => !scene.hidden);
  if (!scr) return;
  const steps = [...root.querySelectorAll('.how-step')];
  const setStep = (n) => {
    for (const node of steps) node.classList.toggle('is-current', node.dataset.how === String(n));
  };
  const screen = screenTimeline(gsap, scr, { onStep: setStep });
  whileVisible(ScrollTrigger, scr, screen.tl);
  ScrollTrigger.addEventListener('refreshInit', () => screen.tl.invalidate());

  gsap.from(steps, {
    autoAlpha: 0,
    y: 24,
    duration: 0.6,
    stagger: 0.12,
    ease: 'power3.out',
    scrollTrigger: { trigger: root.querySelector('.how-grid'), start: 'top 80%', once: true },
  });
  gsap.from(scr, {
    autoAlpha: 0,
    y: 48,
    scale: 0.95,
    duration: 0.8,
    ease: 'power3.out',
    scrollTrigger: { trigger: scr, start: 'top 88%', once: true },
  });
}

// Cada tarjeta: (gsap, tarjeta) => línea de tiempo en pausa que se repite.
const TILES = {
  quality(gsap, tile) {
    const bars = tile.querySelectorAll('.fxq-bar i');
    const labels = tile.querySelectorAll('.fxq-bar em');
    const badge = tile.querySelector('.fxq-badge');
    const top = tile.querySelector('.fxq-bar.b5 i');
    const tl = gsap.timeline({ paused: true, repeat: -1, repeatDelay: 0.6 });
    tl.set(bars, { scaleY: 0 }, 0)
      .set([labels, badge], { autoAlpha: 0 }, 0)
      .to(bars, { scaleY: 1, duration: 0.5, stagger: 0.18, ease: 'back.out(1.7)' }, 0.2)
      .fromTo(labels, { autoAlpha: 0, y: 6 }, { autoAlpha: 1, y: 0, duration: 0.3, stagger: 0.18 }, 0.3)
      .fromTo(badge, { autoAlpha: 0, scale: 0.6 }, { autoAlpha: 1, scale: 1, duration: 0.45, ease: 'back.out(2.2)' })
      .to(top, { filter: 'brightness(1.4)', duration: 0.3, yoyo: true, repeat: 3 }, '<')
      .to({}, { duration: 1.6 })
      .to(bars, { scaleY: 0, duration: 0.35, stagger: 0.05, ease: 'power2.in' })
      .to([labels, badge], { autoAlpha: 0, duration: 0.25 }, '<');
    return tl;
  },

  audio(gsap, tile) {
    const bars = tile.querySelectorAll('.fxa i');
    // repeatRefresh: cada vuelta elige alturas nuevas, como un ecualizador.
    const tl = gsap.timeline({ paused: true, repeat: -1, repeatRefresh: true });
    tl.to(bars, { scaleY: () => gsap.utils.random(0.18, 1), duration: 0.3, ease: 'sine.inOut', stagger: 0.035 });
    return tl;
  },

  list(gsap, tile) {
    const rows = tile.querySelectorAll('.fxl-row');
    const checks = tile.querySelectorAll('.fxl-row .icon');
    const tl = gsap.timeline({ paused: true, repeat: -1, repeatDelay: 0.4 });
    tl.set(rows, { autoAlpha: 0, x: -24 }, 0)
      .set(checks, { scale: 0 }, 0)
      .to(rows, { autoAlpha: 1, x: 0, duration: 0.4, stagger: 0.22, ease: 'power3.out' }, 0.2)
      .to(checks, { scale: 1, duration: 0.35, stagger: 0.3, ease: 'back.out(2.4)' }, '<0.5')
      .to({}, { duration: 1.6 })
      .to(rows, { autoAlpha: 0, y: -8, duration: 0.3, stagger: 0.05 })
      .set(rows, { y: 0 });
    return tl;
  },

  update(gsap, tile) {
    const icon = tile.querySelector('.fxu-icon .icon');
    const old = tile.querySelector('.fxu-old');
    const fresh = tile.querySelector('.fxu-new');
    const tl = gsap.timeline({ paused: true, repeat: -1, repeatDelay: 0.6 });
    tl.set(old, { autoAlpha: 1, y: 0 }, 0)
      .set(fresh, { autoAlpha: 0, y: 12 }, 0)
      .set(icon, { rotation: 0 }, 0)
      .to(icon, { rotation: 360, duration: 1.1, ease: 'power2.inOut' }, 0.6)
      .to(old, { autoAlpha: 0, y: -12, duration: 0.3 }, '-=0.3')
      .to(fresh, { autoAlpha: 1, y: 0, duration: 0.35, ease: 'back.out(2)' }, '<0.1')
      .to({}, { duration: 2 });
    return tl;
  },

  errors(gsap, tile) {
    const raw = tile.querySelector('.fxe-raw');
    const nice = tile.querySelector('.fxe-nice');
    const ok = tile.querySelector('.fxe-ok');
    const spin = tile.querySelector('.fxe-spin');
    const tl = gsap.timeline({ paused: true, repeat: -1, repeatDelay: 0.4 });
    tl.set(raw, { autoAlpha: 1, y: 0 }, 0)
      .set([nice, ok], { autoAlpha: 0, y: 10 }, 0)
      .set(spin, { rotation: 0 }, 0)
      .to(raw, { x: 4, duration: 0.06, repeat: 5, yoyo: true }, 0.8)
      .to(raw, { autoAlpha: 0, y: -10, duration: 0.3 }, '+=0.4')
      .to(nice, { autoAlpha: 1, y: 0, duration: 0.35 }, '<0.1')
      .to(spin, { rotation: 720, duration: 1.6, ease: 'none' }, '<')
      .to(nice, { autoAlpha: 0, y: -10, duration: 0.3 })
      .to(ok, { autoAlpha: 1, y: 0, duration: 0.35, ease: 'back.out(2)' }, '<0.1')
      .to({}, { duration: 1.8 });
    return tl;
  },

  private(gsap, tile) {
    const ads = tile.querySelectorAll('.fxp-ad');
    const shield = tile.querySelector('.fxp-shield .icon');
    const tl = gsap.timeline({ paused: true, repeat: -1, repeatDelay: 0.5 });
    tl.set(ads, { autoAlpha: 1, scale: 1 }, 0)
      .set(shield, { autoAlpha: 0, scale: 0.4 }, 0)
      .fromTo(ads, { y: 12, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.35, stagger: 0.15 }, 0.2)
      .to(shield, { autoAlpha: 1, scale: 1, duration: 0.5, ease: 'back.out(2.2)' }, '+=0.5')
      .to(ads, { autoAlpha: 0.15, scale: 0.85, duration: 0.3, stagger: 0.12 }, '<0.15')
      .to({}, { duration: 1.8 });
    return tl;
  },

  code(gsap, tile) {
    const lines = tile.querySelectorAll('.fxc-line');
    const tl = gsap.timeline({ paused: true, repeat: -1, repeatDelay: 0.5 });
    tl.set(lines, { scaleX: 0, autoAlpha: 1 }, 0)
      .to(lines, { scaleX: 1, duration: 0.45, stagger: 0.3, ease: 'power2.out' }, 0.2)
      .to({}, { duration: 1.8 })
      .to(lines, { autoAlpha: 0, duration: 0.3 });
    return tl;
  },

  sites(gsap, tile) {
    // Cinta que pasa sola: se duplican los nombres para que la vuelta no se note.
    const track = tile.querySelector('.fxs-track');
    if (!track) return null;
    for (const node of [...track.children]) {
      const copy = node.cloneNode(true);
      copy.setAttribute('aria-hidden', 'true');
      track.append(copy);
    }
    return gsap.to(track, { xPercent: -50, ease: 'none', duration: 32, repeat: -1, paused: true });
  },
};

function initTiles(gsap, ScrollTrigger) {
  const tiles = [...document.querySelectorAll('.fx[data-fx]')];
  if (!tiles.length) return;
  gsap.set(tiles, { autoAlpha: 0, y: 32 });
  ScrollTrigger.batch(tiles, {
    start: 'top 90%',
    once: true,
    onEnter: (batch) =>
      gsap.to(batch, { autoAlpha: 1, y: 0, duration: 0.6, stagger: 0.1, ease: 'power3.out', overwrite: true }),
  });
  for (const tile of tiles) {
    const build = TILES[tile.dataset.fx];
    const animation = build?.(gsap, tile);
    if (animation) whileVisible(ScrollTrigger, tile, animation);
  }
}

export function initFeatures(platform) {
  const { gsap, ScrollTrigger } = window;
  if (!gsap || !ScrollTrigger || prefersReducedMotion()) return;
  gsap.registerPlugin(ScrollTrigger);
  document.documentElement.classList.add('fx-anim');
  initHow(gsap, ScrollTrigger, platform);
  initTiles(gsap, ScrollTrigger);
}
