/* ============================================
   NOTES+ — THE NEW DIMENSION — SITE SCRIPT
   Navbar, scroll reveal, portal particles, the
   "Achievement get!" toast and the screenshot viewer.
   ============================================ */

const REDUCED_MOTION = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

document.addEventListener('DOMContentLoaded', () => {
  initNavbar();
  initScrollReveal();
  initParticles();
  initAchievement();
  initScreenshots();
});

/* ---------- Navbar ---------- */
function initNavbar() {
  const navbar = document.querySelector('.navbar');
  const hamburger = document.querySelector('.hamburger');
  const navLinks = document.querySelector('.nav-links');
  const overlay = document.querySelector('.nav-overlay');
  if (!navbar) return;

  const onScroll = () => navbar.classList.toggle('scrolled', window.scrollY > 30);
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  const close = () => {
    hamburger?.classList.remove('active');
    hamburger?.setAttribute('aria-expanded', 'false');
    navLinks?.classList.remove('open');
    overlay?.classList.remove('visible');
    document.body.style.overflow = '';
  };
  hamburger?.addEventListener('click', () => {
    const open = hamburger.classList.toggle('active');
    hamburger.setAttribute('aria-expanded', String(open));
    navLinks?.classList.toggle('open', open);
    overlay?.classList.toggle('visible', open);
    document.body.style.overflow = open ? 'hidden' : '';
  });
  overlay?.addEventListener('click', close);
  navLinks?.querySelectorAll('a').forEach(a => a.addEventListener('click', close));
  document.addEventListener('keydown', e => { if (e.key === 'Escape') close(); });

  const page = window.location.pathname.split('/').pop() || 'index.html';
  navLinks?.querySelectorAll('a').forEach(a => {
    if (a.getAttribute('href') === page) a.classList.add('active');
  });
}

/* ---------- Scroll reveal ---------- */
function initScrollReveal() {
  const items = document.querySelectorAll('.reveal');
  if (!items.length) return;
  if (REDUCED_MOTION || !('IntersectionObserver' in window)) {
    items.forEach(el => el.classList.add('visible'));
    return;
  }
  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('visible');
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
  items.forEach(el => observer.observe(el));
}

/* ---------- Portal particles drifting up the page ---------- */
function initParticles() {
  if (REDUCED_MOTION) return;
  const layer = document.createElement('div');
  layer.className = 'particles';
  layer.setAttribute('aria-hidden', 'true');
  const count = window.innerWidth < 720 ? 14 : 26;
  for (let i = 0; i < count; i++) {
    const p = document.createElement('i');
    const size = Math.random() < 0.3 ? 4 : 6;
    p.style.left = `${Math.random() * 100}%`;
    p.style.width = p.style.height = `${size}px`;
    p.style.animationDuration = `${9 + Math.random() * 12}s`;
    p.style.animationDelay = `${-Math.random() * 20}s`;
    if (Math.random() < 0.25) p.style.background = '#ff8cf2';
    layer.appendChild(p);
  }
  document.body.prepend(layer);
}

/* ---------- "Achievement get!" once per visit ---------- */
function initAchievement() {
  const text = document.body.dataset.achievement;
  if (!text) return;
  try {
    if (sessionStorage.getItem('np-achievement') === '1') return;
    sessionStorage.setItem('np-achievement', '1');
  } catch (e) { /* storage blocked: show it anyway */ }

  const toast = document.createElement('div');
  toast.className = 'achievement';
  toast.setAttribute('role', 'status');
  const icon = document.createElement('div');
  icon.className = 'icon';
  icon.textContent = 'N+';
  const words = document.createElement('div');
  const title = document.createElement('div');
  title.className = 'title';
  title.textContent = 'Achievement get!';
  const body = document.createElement('div');
  body.className = 'body';
  body.textContent = text;
  words.append(title, body);
  toast.append(icon, words);
  document.body.appendChild(toast);
  setTimeout(() => toast.classList.add('show'), 900);
  setTimeout(() => toast.classList.remove('show'), 5200);
}

/* ---------- Screenshots: missing ones say so; any can open full size ---------- */
function initScreenshots() {
  document.querySelectorAll('.phone img').forEach(img => {
    // A new screenshot not uploaded yet falls back to an older one, then to "coming soon".
    const mark = () => {
      const fallback = img.dataset.fallback;
      if (fallback && !img.dataset.fellBack) {
        img.dataset.fellBack = '1';
        img.src = fallback;
        return;
      }
      (img.closest('.shot') || img.closest('.phone'))?.classList.add('missing');
    };
    if (img.complete && img.naturalWidth === 0) mark();
    img.addEventListener('error', mark);
  });

  const shots = Array.from(document.querySelectorAll('.shot'));
  const box = document.getElementById('lightbox');
  if (!shots.length || !box) return;
  const img = box.querySelector('img');
  const caption = box.querySelector('figcaption');
  let list = [];
  let index = 0;

  const show = i => {
    if (!list.length) return;
    index = (i + list.length) % list.length;
    const source = list[index].querySelector('img');
    img.src = source.src;
    img.alt = source.alt;
    caption.textContent = list[index].querySelector('p')?.textContent || '';
  };
  const open = shot => {
    if (shot.classList.contains('missing')) return;
    list = shots.filter(s => !s.classList.contains('missing'));
    show(list.indexOf(shot));
    box.classList.add('open');
    document.body.style.overflow = 'hidden';
    box.querySelector('[data-close]').focus();
  };
  const close = () => {
    box.classList.remove('open');
    document.body.style.overflow = '';
  };

  shots.forEach(shot => {
    shot.tabIndex = 0;
    shot.setAttribute('role', 'button');
    shot.addEventListener('click', () => open(shot));
    shot.addEventListener('keydown', e => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        open(shot);
      }
    });
  });
  box.querySelector('[data-prev]').addEventListener('click', () => show(index - 1));
  box.querySelector('[data-next]').addEventListener('click', () => show(index + 1));
  box.querySelector('[data-close]').addEventListener('click', close);
  box.addEventListener('click', e => { if (e.target === box) close(); });
  document.addEventListener('keydown', e => {
    if (!box.classList.contains('open')) return;
    if (e.key === 'Escape') close();
    if (e.key === 'ArrowLeft') show(index - 1);
    if (e.key === 'ArrowRight') show(index + 1);
  });
}
