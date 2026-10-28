// Voxel ASCII art: an image rebuilt as a grid of small isometric blocks, sized by how much ink each
// cell holds, edges emphasised, tinted, vignetted, and flickering. A Canvas2D take on 21st.dev's
// "spider" ASCII preset (voxel mode, 6 px cells, contrast 115, edge emphasis 92, invert, tint
// #1e6fff at 48% multiply, solid background at 88%, vignette 38, flicker animation). Used as:
//
//   <div data-ascii data-ascii-src="res/notes_icon.png" data-ascii-tint="#1e6fff"
//        data-ascii-cell="6" data-ascii-fit="right"></div>
//
//   data-ascii-src     the picture (same origin)
//   data-ascii-tint    the tint colour (multiplied in at data-ascii-tint-opacity, default 48)
//   data-ascii-cell    cell size in px (default 6)
//   data-ascii-fit     where the emblem sits in its section: right (default), left, center
//   data-ascii-size    the emblem's largest size in px (default 420; never more than ~42% of
//                      the section's width or 85% of its height)
//   data-ascii-y       top: beside the section's heading instead of its middle
//   data-ascii-scale   the picture's size inside the emblem (default 0.9)
//   data-ascii-bg      a solid background colour drawn at 88% (default none: see-through)
//   data-ascii-inline  the art is the element's own content, exactly data-ascii-size px square
//                      (the dock's home button), not a backdrop of its section
//
// The blocks are drawn once into a cached image; each frame only copies it and dims a few random
// blocks (the flicker), at 12 fps, paused off screen, still for reduced motion.

(function () {
  const still = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const FPS = 12;
  const P = { contrast: 1.15, edge: 0.92, tintOpacity: 0.48, vignette: 0.38, flicker: 0.6, bgOpacity: 0.88 };

  const hex = c => {
    const m = /^#?([0-9a-f]{6})$/i.exec(c || '');
    const n = m ? parseInt(m[1], 16) : 0x1e6fff;
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  };

  function art(host) {
    if (host.querySelector(':scope > .np-ascii')) return;
    const cell = Math.max(3, parseInt(host.dataset.asciiCell, 10) || 6);
    const inline = host.hasAttribute('data-ascii-inline');
    const fit = host.dataset.asciiFit || 'right';
    const maxSize = parseInt(host.dataset.asciiSize, 10) || 420;
    const scale = parseFloat(host.dataset.asciiScale || '0.9');
    const tint = hex(host.dataset.asciiTint);
    const tintOp = (parseFloat(host.dataset.asciiTintOpacity) || P.tintOpacity * 100) / 100;
    const bg = host.dataset.asciiBg || null;

    const layer = document.createElement('div');
    layer.className = inline ? 'np-ascii-inline' : 'np-ascii np-ascii-' + fit + (host.dataset.asciiY === 'top' ? ' np-ascii-top' : '');
    layer.setAttribute('aria-hidden', 'true');
    const canvas = document.createElement('canvas');
    layer.append(canvas);
    if (!inline) host.classList.add('has-ascii');
    host.prepend(layer);
    const ctx = canvas.getContext('2d');

    const img = new Image();
    img.src = host.dataset.asciiSrc;
    let cells = [], cols = 0, rows = 0, w = 1, h = 1, dpr = 1, running = false, last = 0, visible = true;
    const base = document.createElement('canvas'), bctx = base.getContext('2d');

    /** Samples the picture into cells: ink (0–1, edges emphasised) and colour (tinted). */
    const sample = () => {
      if (!img.complete || !img.naturalWidth) return;
      // The emblem: a square no bigger than its cap, beside the section's content.
      const hr = host.getBoundingClientRect();
      const size = inline ? maxSize : Math.max(80, Math.round(Math.min(maxSize, hr.height * 0.85, hr.width * (fit === 'center' ? 0.8 : 0.42))));
      layer.style.width = layer.style.height = size + 'px';
      dpr = Math.min(2, window.devicePixelRatio || 1);
      w = h = size;
      canvas.width = base.width = w * dpr; canvas.height = base.height = h * dpr;
      canvas.style.width = w + 'px'; canvas.style.height = h + 'px';
      cols = Math.ceil(w / cell); rows = Math.ceil(h / cell);
      // Step 1: the picture into a grid-sized canvas (one pixel per cell = the cell's average).
      const off = document.createElement('canvas');
      off.width = cols; off.height = rows;
      const o = off.getContext('2d', { willReadFrequently: true });
      const side = rows * scale, ratio = img.naturalWidth / img.naturalHeight;
      const dw = side * ratio, dh = side;
      const dx = (cols - dw) / 2;
      o.drawImage(img, dx, (rows - dh) / 2, dw, dh);
      const px = o.getImageData(0, 0, cols, rows).data;
      // Step 2: ink per cell. Inverted luminance, with colour counting as ink too (so a yellow
      // logo on white reads as solid), weighted by alpha, then contrast.
      const ink = new Float32Array(cols * rows);
      for (let i = 0; i < cols * rows; i++) {
        const R = px[i * 4], G = px[i * 4 + 1], B = px[i * 4 + 2], A = px[i * 4 + 3] / 255;
        const lum = (0.2126 * R + 0.7152 * G + 0.0722 * B) / 255;
        const max = Math.max(R, G, B), min = Math.min(R, G, B), sat = max ? (max - min) / max : 0;
        const v = Math.max(1 - lum, sat) * A;
        ink[i] = Math.min(1, Math.max(0, (v - 0.5) * P.contrast + 0.5 - (A < 0.05 ? 1 : 0)));
      }
      // Step 3: edge emphasis (Sobel on the ink).
      cells = [];
      for (let y = 0; y < rows; y++) for (let x = 0; x < cols; x++) {
        const at = (xx, yy) => ink[Math.min(rows - 1, Math.max(0, yy)) * cols + Math.min(cols - 1, Math.max(0, xx))];
        const gx = -at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1) + at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1);
        const gy = -at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1) + at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1);
        const v = Math.min(1, ink[y * cols + x] + Math.min(1, Math.hypot(gx, gy) / 4) * P.edge * 0.5);
        if (v < 0.06) continue;
        const i = (y * cols + x) * 4;
        // Step 4: the tint, multiplied in.
        const mix = k => Math.round(px[i + k] * (1 - tintOp) + (px[i + k] * tint[k] / 255) * tintOp);
        const lift = 0.55 + 0.45 * v; // brighter where there's more ink
        cells.push({ x, y, v, r: Math.min(255, mix(0) * lift + 40), g: Math.min(255, mix(1) * lift + 40), b: Math.min(255, mix(2) * lift + 60), ph: Math.random() * 6.28 });
      }
      bake();
      paint(performance.now());
    };

    /** One block: a top face, a left face and a right face, lit from the top left. */
    const cube = (ctx, cx, cy, s, r, g, b, a) => {
      const hw = s / 2, q = s / 4;
      ctx.globalAlpha = a;
      ctx.fillStyle = `rgb(${Math.min(255, r * 1.25)},${Math.min(255, g * 1.25)},${Math.min(255, b * 1.25)})`;
      ctx.beginPath(); ctx.moveTo(cx, cy - q); ctx.lineTo(cx + hw, cy); ctx.lineTo(cx, cy + q); ctx.lineTo(cx - hw, cy); ctx.fill();
      ctx.fillStyle = `rgb(${r * 0.75},${g * 0.75},${b * 0.75})`;
      ctx.beginPath(); ctx.moveTo(cx - hw, cy); ctx.lineTo(cx, cy + q); ctx.lineTo(cx, cy + q + hw); ctx.lineTo(cx - hw, cy + hw); ctx.fill();
      ctx.fillStyle = `rgb(${r * 0.5},${g * 0.5},${b * 0.5})`;
      ctx.beginPath(); ctx.moveTo(cx + hw, cy); ctx.lineTo(cx, cy + q); ctx.lineTo(cx, cy + q + hw); ctx.lineTo(cx + hw, cy + hw); ctx.fill();
    };

    /** Every block, drawn once into the cached image, with the vignette. */
    const bake = () => {
      bctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      bctx.clearRect(0, 0, w, h);
      if (bg) { bctx.globalAlpha = P.bgOpacity; bctx.fillStyle = bg; bctx.fillRect(0, 0, w, h); }
      for (const c of cells) {
        const s = cell * (0.45 + 0.55 * c.v);
        cube(bctx, c.x * cell + cell / 2, c.y * cell + cell / 2 - s / 4, s, c.r, c.g, c.b, Math.max(0.05, c.v));
      }
      bctx.globalAlpha = 1;
      bctx.globalCompositeOperation = 'destination-out';
      const vg = bctx.createRadialGradient(w / 2, h / 2, w * 0.28, w / 2, h / 2, w * 0.72);
      vg.addColorStop(0, 'rgba(0,0,0,0)');
      vg.addColorStop(1, `rgba(0,0,0,${Math.min(1, P.vignette * 2.4)})`);
      bctx.fillStyle = vg;
      bctx.fillRect(0, 0, w, h);
      bctx.globalCompositeOperation = 'source-over';
    };

    /** A frame: the cached blocks, with a few of them flickering dim. */
    const paint = () => {
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(base, 0, 0);
      if (still || !cells.length) return;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.globalCompositeOperation = 'destination-out';
      const n = Math.ceil(cells.length * 0.03 * P.flicker * 1.6);
      for (let k = 0; k < n; k++) {
        const c = cells[(Math.random() * cells.length) | 0];
        ctx.globalAlpha = 0.25 + Math.random() * 0.5;
        ctx.fillRect(c.x * cell, c.y * cell - cell * 0.25, cell, cell * 1.25);
      }
      ctx.globalAlpha = 1;
      ctx.globalCompositeOperation = 'source-over';
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
    img.onload = sample;
    new ResizeObserver(sample).observe(host);
    document.addEventListener('visibilitychange', play);
    if ('IntersectionObserver' in window) new IntersectionObserver(es => { visible = es[0].isIntersecting; play(); }).observe(layer);
    else play();
  }

  const init = () => document.querySelectorAll('[data-ascii]').forEach(art);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
