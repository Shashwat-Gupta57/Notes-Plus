// Animated background paths: bundles of thin lines that slowly draw themselves along their curve.
// Ported from the "Background Paths" React component (same path maths, same 36-line fans, same
// motion) to a plain canvas, since this site has no build step and no React. Used as:
//
//   <section data-paths="flow" data-paths-color="currentColor" data-paths-mirror> … </section>
//
//   data-paths         flow (the original fan) | waves | orbit | ribbon
//   data-paths-color   any CSS colour, or two separated by a comma for a gradient
//   data-paths-count   lines per bundle (default 36)
//   data-paths-mirror  flow only: a second fan mirrored, as in the original
//   data-paths-opacity overall strength, 0–1 (default 1)
//
// The lines sit behind the element's content and never take clicks. They stand still for anyone
// who asked for less motion, and pause while off screen.

(function () {
  const NS = 'http://www.w3.org/2000/svg';
  const W = 696, H = 316;

  /** The original component's fan: position 1 or -1. */
  function flow(n, position) {
    return Array.from({ length: n }, (_, i) => {
      const a = 380 - i * 5 * position, b = 189 + i * 6, c = 312 - i * 5 * position, d = 216 - i * 6;
      const e = 152 - i * 5 * position, f = 343 - i * 6, g = 616 - i * 5 * position, h = 470 - i * 6;
      const k = 684 - i * 5 * position, l = 875 - i * 6;
      return `M-${a} -${b}C-${a} -${b} -${c} ${d} ${e} ${f}C${g} ${h} ${k} ${l} ${k} ${l}`;
    });
  }

  /** Stacked waves across the width, each a little taller and further along. */
  function waves(n) {
    return Array.from({ length: n }, (_, i) => {
      const y = 10 + (i * (H - 20)) / Math.max(1, n - 1), amp = 18 + (i % 7) * 4, shift = i * 9;
      let d = `M-60 ${y.toFixed(1)}`;
      for (let x = -60; x < W + 60; x += 120) {
        const s = (Math.floor((x + shift) / 120) % 2 ? 1 : -1) * amp;
        d += ` C${x + 40} ${(y + s).toFixed(1)} ${x + 80} ${(y - s).toFixed(1)} ${x + 120} ${y.toFixed(1)}`;
      }
      return d;
    });
  }

  /** Tilted rings around a point right of centre, widening outwards. */
  function orbit(n) {
    const cx = W * 0.72, cy = H * 0.5;
    return Array.from({ length: n }, (_, i) => {
      const rx = 24 + i * 11, ry = 10 + i * 5.2;
      return `M${cx - rx} ${cy} A${rx} ${ry} -14 1 1 ${cx + rx} ${cy} A${rx} ${ry} -14 1 1 ${cx - rx} ${cy}`;
    });
  }

  /** One broad band sweeping from the top left to the bottom right, in an S. */
  function ribbon(n) {
    return Array.from({ length: n }, (_, i) => {
      const o = (i * (H + 160)) / n - 160;
      return `M-80 ${o.toFixed(1)}C${W * 0.35} ${(o - 90).toFixed(1)} ${W * 0.55} ${(o + 260).toFixed(1)} ${W + 80} ${(o + 170).toFixed(1)}`;
    });
  }

  const SHAPES = { flow, waves, orbit, ribbon };
  const still = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const FPS = 30;

  /** Each path's length in viewBox units (canvas dashes need real lengths), and points along it. */
  function lengths(ds) {
    const svg = document.createElementNS(NS, 'svg');
    svg.style.cssText = 'position:absolute;width:0;height:0;visibility:hidden';
    document.body.append(svg);
    const out = ds.map(d => {
      const p = document.createElementNS(NS, 'path');
      p.setAttribute('d', d);
      svg.append(p);
      const len = p.getTotalLength();
      const pts = [];
      for (let k = 0; k <= 48; k++) { const q = p.getPointAtLength((len * k) / 48); pts.push([q.x, q.y]); }
      return { len, pts };
    });
    svg.remove();
    return out;
  }

  /**
   * Drawn on a canvas rather than as animated SVG: 70-odd lines re-dashed every frame cost a full
   * SVG repaint each time, which stalls the page on phones. One canvas at 30 fps is cheap.
   */
  function draw(host) {
    if (host.querySelector(':scope > .np-paths')) return;
    const shape = SHAPES[host.dataset.paths] ? host.dataset.paths : 'flow';
    const n = Math.max(4, Math.min(60, parseInt(host.dataset.pathsCount, 10) || 36));
    const colors = (host.dataset.pathsColor || 'currentColor').split(',').map(s => s.trim()).filter(Boolean);
    const layer = document.createElement('div');
    layer.className = 'np-paths';
    layer.setAttribute('aria-hidden', 'true');
    if (host.dataset.pathsOpacity) layer.style.opacity = host.dataset.pathsOpacity;
    const canvas = document.createElement('canvas');
    layer.append(canvas);
    host.classList.add('has-paths');
    host.prepend(layer);

    const bundles = shape === 'flow' ? (host.hasAttribute('data-paths-mirror') ? [1, -1] : [1]) : [1];
    const lines = [];
    bundles.forEach((position, b) => {
      const ds = shape === 'flow' ? flow(n, position) : SHAPES[shape](n);
      const lens = lengths(ds);
      ds.forEach((d, i) => {
        lines.push({
          path: new Path2D(d), len: lens[i].len, pts: lens[i].pts, from: 0, width: 0.5 + i * 0.03, alpha: 0.1 + i * (0.9 / n),
          // Seconds for the snake to run the whole path once (the original's lines drifted over 20–30 s).
          dur: 9 + Math.random() * 6,
          // Sliding in from the edge where it starts, one after another, the mirrored fan a beat later.
          enter: b * 0.3 + i * 0.045 + Math.random() * 0.25,
        });
      });
    });

    const ctx = canvas.getContext('2d');
    let w = 0, h = 0, dpr = 1, stroke = null, born = null, running = false, last = 0, visible = true;
    const SNAKE = 0.32; // the bright segment's length, as a share of its path
    // The fan reaches far outside its box and shows through (as the original's 'meet'); the others fill it.
    const fit = () => {
      const r = layer.getBoundingClientRect();
      dpr = Math.min(2, window.devicePixelRatio || 1);
      w = Math.max(1, Math.round(r.width)); h = Math.max(1, Math.round(r.height));
      canvas.width = w * dpr; canvas.height = h * dpr;
      canvas.style.width = w + 'px'; canvas.style.height = h + 'px';
      // On a tall, narrow screen the fan is sized by height instead, so it still crosses it.
      const k = shape === 'flow' ? Math.max(Math.min(w / W, h / H), h / (H * 2.2)) : Math.max(w / W, h / H);
      const ox = (w - W * k) / 2, oy = (h - H * k) / 2;
      ctx.setTransform(dpr * k, 0, 0, dpr * k, dpr * ox, dpr * oy);
      // Where each path first comes on screen: its snake starts just outside that edge.
      for (const l of lines) {
        const at = l.pts.findIndex(([x, y]) => x * k + ox >= 0 && x * k + ox <= w && y * k + oy >= 0 && y * k + oy <= h);
        l.from = at <= 0 ? 0 : (l.len * (at - 1)) / 48;
      }
      const css = getComputedStyle(layer).color;
      const resolve = c => (c === 'currentColor' ? css : c);
      if (colors.length > 1) {
        stroke = ctx.createLinearGradient(0, 0, W, H);
        colors.forEach((c, i) => stroke.addColorStop(i / (colors.length - 1), resolve(c)));
      } else {
        stroke = resolve(colors[0]);
      }
      paint(performance.now());
    };
    const paint = now => {
      ctx.save();
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.restore();
      ctx.strokeStyle = stroke;
      const t = born === null ? 0 : (now - born) / 1000;
      for (const l of lines) {
        ctx.lineWidth = l.width;
        const seg = l.len * SNAKE, run = l.len + seg;
        if (still) {
          ctx.globalAlpha = l.alpha * 0.45;
          ctx.setLineDash([]);
          ctx.stroke(l.path);
          continue;
        }
        const since = t - l.enter;
        if (since <= 0) continue;
        // A snake: a bright segment whose head runs along the path at an even pace from just before
        // its start, so it slides in; when it has left the far end, a fresh one slides in again.
        const travelled = l.from + (since / l.dur) * run;
        const first = travelled < run;
        const head = travelled % run;
        // It leaves the path behind it, faint: during the first run up to its head, then whole.
        ctx.globalAlpha = l.alpha * 0.22;
        if (first) {
          ctx.setLineDash([Math.max(0, Math.min(l.len, head)), l.len * 2]);
          ctx.lineDashOffset = 0;
        } else {
          ctx.setLineDash([]);
        }
        ctx.stroke(l.path);
        // The snake itself in three parts, dim at the tail and bright at the head, breathing slowly.
        const breathe = 0.75 + 0.25 * Math.sin(since * 0.6 + l.enter * 7);
        for (let k = 0; k < 3; k++) {
          const from = head - seg + (seg * k) / 3, part = seg / 3;
          ctx.globalAlpha = l.alpha * breathe * (0.25 + 0.3 * k);
          ctx.setLineDash([part, l.len * 3]);
          ctx.lineDashOffset = -from;
          ctx.stroke(l.path);
        }
      }
    };
    const tick = now => {
      if (!running) return;
      if (now - last >= 1000 / FPS - 2) { last = now; paint(now); }
      requestAnimationFrame(tick);
    };
    const play = () => {
      const want = visible && !still && document.visibilityState === 'visible';
      if (want && born === null) born = performance.now(); // The arrival plays when first seen.
      if (want && !running) { running = true; requestAnimationFrame(tick); } else if (!want) running = false;
    };
    fit();
    new ResizeObserver(fit).observe(layer);
    // The theme toggle changes the line colour: follow it.
    new MutationObserver(fit).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class'] });
    document.addEventListener('visibilitychange', play);
    if ('IntersectionObserver' in window) new IntersectionObserver(es => { visible = es[0].isIntersecting; play(); }).observe(layer);
    else play();
  }

  /** The headline's words rise in one after another (the component's title animation). */
  function rise(title) {
    if (still || title.dataset.risen) return;
    title.dataset.risen = '1';
    let k = 0;
    const walk = node => {
      for (const child of [...node.childNodes]) {
        if (child.nodeType === 3) {
          const parts = child.textContent.split(/(\s+)/);
          const frag = document.createDocumentFragment();
          for (const part of parts) {
            if (!part) continue;
            if (/^\s+$/.test(part)) { frag.append(part); continue; }
            const w = document.createElement('span');
            w.className = 'np-rise';
            w.style.animationDelay = `${(k++ * 0.07).toFixed(2)}s`;
            w.textContent = part;
            frag.append(w);
          }
          child.replaceWith(frag);
        } else if (child.nodeType === 1 && child.tagName !== 'BR') {
          // A styled run (the gradient line) rises as one, so its gradient stays whole.
          child.classList.add('np-rise');
          child.style.animationDelay = `${(k++ * 0.07).toFixed(2)}s`;
        }
      }
    };
    walk(title);
  }

  function init() {
    document.querySelectorAll('[data-paths]').forEach(draw);
    document.querySelectorAll('[data-rise]').forEach(rise);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
