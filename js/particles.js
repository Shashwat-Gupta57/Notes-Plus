// Floating 3D particles: a slowly turning cloud of points seen in perspective, nearer ones larger
// and brighter, leaning a little towards the pointer. A dependency-free take on the "Floating 3D
// Particles" component (same knobs). Used as:
//
//   <section data-particles data-particles-quantity="1000" data-particles-depth="0.8"
//            data-particles-opacity="0.55"> … </section>
//
//   data-particles-color  "dark,light": the dot colour per site theme (default #c4b5fd,#6d28d9)
//   data-particles-quantity  how many points (default 1000; fewer on small screens)
//   data-particles-depth     0–1, how deep the cloud is (stronger perspective)
//   data-particles-opacity   0–1, overall strength
//
// Drawn behind the element's content at 30 fps, paused off screen, still for reduced motion.

(function () {
  const still = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const FPS = 30;

  function cloud(host) {
    if (host.querySelector(':scope > .np-particles')) return;
    const [dark, light] = (host.dataset.particlesColor || '#c4b5fd,#6d28d9').split(',').map(s => s.trim());
    const depth = Math.max(0, Math.min(1, parseFloat(host.dataset.particlesDepth || '0.8')));
    const small = window.matchMedia('(max-width: 640px)').matches;
    const quantity = Math.round(Math.min(3000, parseInt(host.dataset.particlesQuantity, 10) || 1000) * (small ? 0.45 : 1));

    const layer = document.createElement('div');
    layer.className = 'np-particles';
    layer.setAttribute('aria-hidden', 'true');
    layer.style.opacity = host.dataset.particlesOpacity || '0.55';
    const canvas = document.createElement('canvas');
    layer.append(canvas);
    host.classList.add('has-particles');
    host.prepend(layer);

    // Points spread through a flattened sphere, each with its own slow drift.
    const pts = Array.from({ length: quantity }, () => {
      const u = Math.random() * 2 - 1, a = Math.random() * Math.PI * 2, r = Math.cbrt(Math.random());
      const s = Math.sqrt(1 - u * u);
      return { x: r * s * Math.cos(a) * 1.6, y: r * u * 0.9, z: r * s * Math.sin(a), ph: Math.random() * Math.PI * 2, sp: 0.3 + Math.random() * 0.7 };
    });

    const ctx = canvas.getContext('2d');
    let w = 1, h = 1, dpr = 1, color = dark, born = performance.now(), running = false, last = 0, visible = true;
    let px = 0, py = 0, tx = 0, ty = 0;
    const theme = () => (document.documentElement.getAttribute('data-theme') === 'light' ? light : dark);
    const fit = () => {
      const r = layer.getBoundingClientRect();
      dpr = Math.min(2, window.devicePixelRatio || 1);
      w = Math.max(1, Math.round(r.width)); h = Math.max(1, Math.round(r.height));
      canvas.width = w * dpr; canvas.height = h * dpr;
      canvas.style.width = w + 'px'; canvas.style.height = h + 'px';
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      color = theme();
      paint(performance.now());
    };
    const paint = now => {
      const t = (now - born) / 1000;
      ctx.clearRect(0, 0, w, h);
      ctx.fillStyle = color;
      px += (tx - px) * 0.05; py += (ty - py) * 0.05;
      const rot = (still ? 0.6 : t * 0.05) + px * 0.35, tilt = 0.25 + py * 0.2;
      const cr = Math.cos(rot), sr = Math.sin(rot), ct = Math.cos(tilt), st = Math.sin(tilt);
      const scale = Math.min(w, h * 1.8) * 0.42, cam = 2.6 - depth * 1.4;
      for (const p of pts) {
        const bob = still ? 0 : Math.sin(t * p.sp + p.ph) * 0.03;
        // Turn about the vertical axis, then tip towards the viewer.
        const x1 = p.x * cr - p.z * sr, z1 = p.x * sr + p.z * cr;
        const y1 = (p.y + bob) * ct - z1 * st, z2 = (p.y + bob) * st + z1 * ct;
        const f = cam / (cam + z2); // perspective: near points bigger
        const sx = w / 2 + x1 * scale * f, sy = h / 2 + y1 * scale * f;
        if (sx < -4 || sy < -4 || sx > w + 4 || sy > h + 4) continue;
        const size = Math.min(1.5, 0.35 + 0.7 * f * f); // a radius: near points a little larger
        ctx.globalAlpha = Math.max(0.08, Math.min(1, 0.25 + (1 - z2) * 0.38));
        ctx.beginPath();
        ctx.arc(sx, sy, size, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalAlpha = 1;
    };
    const tick = now => {
      if (!running) return;
      if (now - last >= 1000 / FPS - 2) { last = now; paint(now); }
      requestAnimationFrame(tick);
    };
    const play = () => {
      const want = visible && !still && document.visibilityState === 'visible';
      if (want && !running) { running = true; requestAnimationFrame(tick); } else if (!want) running = false;
    };
    host.addEventListener('pointermove', e => {
      const r = host.getBoundingClientRect();
      tx = (e.clientX - r.left) / r.width - 0.5; ty = (e.clientY - r.top) / r.height - 0.5;
    });
    host.addEventListener('pointerleave', () => { tx = 0; ty = 0; });
    fit();
    new ResizeObserver(fit).observe(layer);
    new MutationObserver(fit).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    document.addEventListener('visibilitychange', play);
    if ('IntersectionObserver' in window) new IntersectionObserver(es => { visible = es[0].isIntersecting; play(); }).observe(layer);
    else play();
  }

  const init = () => document.querySelectorAll('[data-particles]').forEach(cloud);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
