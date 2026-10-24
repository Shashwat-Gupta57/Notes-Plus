// Mind maps, drawn the apps' way (MindMapCanvas): the same geometry, so a map looks the same
// on the phone, the PC and here. Ideas wrap at 180 (sub-text at 220) unless the map's wrap is
// off; branches curve from the centre; first-level ideas take turns through the palette.

export const MAX_TEXT = 180, MAX_SUB = 220, PAD_X = 14, NODE_H = 40, ROOT_H = 52, COLUMN = 230, ROW = 64, SUB_GAP = 2;
const PIC_W = 160, PIC_H = 120;
export const PALETTE = ['tile-blue', 'tile-green', 'tile-orange', 'tile-purple', 'tile-red', 'tile-teal', 'tile-gray'];

const css = name => getComputedStyle(document.documentElement).getPropertyValue('--' + name).trim();
const fontOf = (root, size) => `${root ? 'bold ' : ''}${size}px Roboto, system-ui, sans-serif`;

let measureCtx = null;
function measure(text, font) {
  if (!measureCtx) measureCtx = document.createElement('canvas').getContext('2d');
  measureCtx.font = font;
  return measureCtx.measureText(text).width;
}

const label = n => n.text || (n.parent < 0 ? 'Central idea' : 'Idea');

/** Breaks text into lines no wider than `max` (at spaces; long words split). */
function wrapText(text, font, max) {
  const out = [];
  for (const para of text.split('\n')) {
    if (max === Infinity || measure(para, font) <= max) { out.push(para); continue; }
    let line = '';
    for (const word of para.split(' ')) {
      const tried = line ? line + ' ' + word : word;
      if (measure(tried, font) <= max) { line = tried; continue; }
      if (line) out.push(line);
      let rest = word;
      while (measure(rest, font) > max && rest.length > 1) {
        let cut = rest.length - 1;
        while (cut > 1 && measure(rest.slice(0, cut), font) > max) cut--;
        out.push(rest.slice(0, cut));
        rest = rest.slice(cut);
      }
      line = rest;
    }
    out.push(line);
  }
  return out;
}

/**
 * A view of one map: layout caches, colours and folds. `pictures` maps an image name to a
 * loaded HTMLImageElement (or nothing yet).
 */
export class MapView {
  constructor(map, pictures = new Map()) {
    this.map = map;
    this.pictures = pictures;
    this.blocks = new Map();
    this.refresh();
  }

  refresh() {
    this.byId = new Map(this.map.nodes.map(n => [n.id, n]));
    this.recolor();
    this.recomputeHidden();
  }

  root() {
    return this.map.nodes.find(n => n.parent < 0) || this.map.nodes[0] || null;
  }

  /** The central ideas: each the top of its own tree, never anyone's idea (connections only). */
  roots() {
    return this.map.nodes.filter(n => n.parent < 0);
  }

  /** The central idea at the top of `n`'s tree. */
  rootOf(n) {
    let at = n;
    for (let guard = 0; at && at.parent >= 0 && guard < 10000; guard++) at = this.byId.get(at.parent);
    return at || this.root();
  }

  children(n) {
    return this.map.nodes.filter(k => k.parent === n.id);
  }

  recolor() {
    this.colorOf = new Map();
    const centres = new Set(this.roots().map(r => r.id));
    if (!centres.size) return;
    let i = 0;
    for (const n of this.map.nodes) {
      if (centres.has(n.parent)) {
        this.colorOf.set(n.id, n.color > 0 ? PALETTE[n.color - 1] : PALETTE[i % (PALETTE.length - 1)]);
        i++;
      }
    }
    let grew = true;
    while (grew) {
      grew = false;
      for (const n of this.map.nodes) {
        if (this.colorOf.has(n.id) || n.parent < 0) continue;
        const inherited = this.colorOf.get(n.parent);
        if (!inherited) continue;
        this.colorOf.set(n.id, n.color > 0 ? PALETTE[n.color - 1] : inherited);
        grew = true;
      }
    }
  }

  recomputeHidden() {
    this.hidden = new Set();
    this.hiddenCount = new Map();
    for (const n of this.map.nodes) {
      if (!n.collapsed) continue;
      const queue = [n.id];
      let count = 0;
      for (let q = 0; q < queue.length; q++) {
        for (const k of this.map.nodes) {
          if (k.parent === queue[q]) {
            this.hidden.add(k.id);
            queue.push(k.id);
            count++;
          }
        }
      }
      this.hiddenCount.set(n.id, count);
    }
  }

  /** Text and sub-text broken into lines, at scale 1. */
  block(n) {
    const root = n.parent < 0;
    const key = label(n) + '\0' + n.sub + '\0' + this.map.wrap + root;
    const cached = this.blocks.get(n.id);
    if (cached && cached.key === key) return cached;
    const tf = fontOf(root, root ? 18 : 15), sf = fontOf(false, root ? 13.5 : 12.5);
    const title = wrapText(label(n), tf, this.map.wrap ? MAX_TEXT : Infinity);
    const tw = Math.max(...title.map(l => measure(l, tf)), 0);
    const sub = n.sub ? wrapText(n.sub, sf, this.map.wrap ? Math.max(MAX_SUB, tw) : Infinity) : [];
    const sw = Math.max(...sub.map(l => measure(l, sf)), 0);
    const titleLine = (root ? 18 : 15) * 1.3, subLine = (root ? 13.5 : 12.5) * 1.3;
    const b = {
      key, title, sub, titleLine, subLine, tf, sf,
      w: Math.max(tw, sw),
      h: title.length * titleLine + (sub.length ? SUB_GAP + sub.length * subLine : 0),
    };
    this.blocks.set(n.id, b);
    return b;
  }

  /** The idea's box at scale 1: {x, y, w, h (centre-based), textH, picW, picH}. */
  geometry(n) {
    const root = n.parent < 0;
    const b = this.block(n);
    let w = b.w + PAD_X * 2;
    const line = root ? ROOT_H : NODE_H;
    const textH = Math.max(line, b.h + (root ? 26 : 20));
    let h = textH, picW = 0, picH = 0;
    if (n.image) {
      const img = this.pictures.get(n.image);
      const iw = img && img.naturalWidth ? img.naturalWidth : PIC_W, ih = img && img.naturalHeight ? img.naturalHeight : PIC_H * 0.66;
      const f = Math.min(1, Math.min(PIC_W / Math.max(1, iw), PIC_H / Math.max(1, ih)));
      picW = iw * f;
      picH = ih * f;
      w = Math.max(w, picW + 16);
      h = textH + picH + 8;
    }
    return { x: n.x, y: n.y, w, h, textH, picW, picH, block: b };
  }

  side(n) {
    const root = this.rootOf(n);
    const s = root ? Math.sign(n.x - root.x) : 1;
    return s === 0 ? 1 : s;
  }

  /** Bounds of what is visible: {minX, minY, maxX, maxY}. */
  contentBounds() {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const n of this.map.nodes) {
      if (this.hidden.has(n.id)) continue;
      const g = this.geometry(n);
      minX = Math.min(minX, g.x - g.w / 2);
      maxX = Math.max(maxX, g.x + g.w / 2);
      minY = Math.min(minY, g.y - g.h / 2);
      maxY = Math.max(maxY, g.y + g.h / 2);
    }
    if (minX === Infinity) return { minX: -100, minY: -50, maxX: 100, maxY: 50 };
    return { minX, minY, maxX, maxY };
  }

  /** The idea under a point in map units, or null. */
  hit(x, y) {
    for (let i = this.map.nodes.length - 1; i >= 0; i--) {
      const n = this.map.nodes[i];
      if (this.hidden.has(n.id)) continue;
      const g = this.geometry(n);
      if (Math.abs(x - g.x) <= g.w / 2 && Math.abs(y - g.y) <= g.h / 2) return n;
    }
    return null;
  }

  /** The fold badge under a point, if any (its idea). */
  hitBadge(x, y, scale) {
    const r = 14 / Math.max(scale, 0.7);
    for (const n of this.map.nodes) {
      if (this.hidden.has(n.id) || !n.collapsed) continue;
      const g = this.geometry(n);
      const bx = this.side(n) > 0 ? g.x + g.w / 2 + r : g.x - g.w / 2 - r;
      if (Math.hypot(x - bx, y - g.y) < r * 1.3) return n;
    }
    return null;
  }

  /** The link badge (top-right corner) under a point, if any. */
  hitLink(x, y, scale) {
    const r = 14 / Math.max(scale, 0.7);
    for (const n of this.map.nodes) {
      if (this.hidden.has(n.id) || !(n.linkUuid || n.link)) continue;
      const g = this.geometry(n);
      if (Math.hypot(x - (g.x + g.w / 2), y - (g.y - g.h / 2)) < r * 1.3) return n;
    }
    return null;
  }

  // ------------------------------------------------------------------- drawing

  /** Draws the map with a camera: screen = map * s + (ox, oy). */
  draw(g, s, ox, oy, opts = {}) {
    const { selected = -1, connectFrom = null } = opts;
    const surface = opts.surface || css('surface'), ink = css('text'), muted = css('secondary'), accent = css('accent');
    const X = x => x * s + ox, Y = y => y * s + oy;

    // Branches.
    g.lineCap = 'round';
    g.lineWidth = Math.max(1.5, 2.5 * s);
    for (const n of this.map.nodes) {
      if (this.hidden.has(n.id)) continue;
      const p = this.byId.get(n.parent);
      if (!p) continue;
      const px = X(p.x), py = Y(p.y), nx = X(n.x), ny = Y(n.y), mid = (px + nx) / 2;
      g.strokeStyle = css(this.colorOf.get(n.id) || 'secondary');
      g.globalAlpha = 0.75;
      g.beginPath();
      g.moveTo(px, py);
      g.bezierCurveTo(mid, py, mid, ny, nx, ny);
      g.stroke();
    }
    g.globalAlpha = 1;

    // Cross-links, dashed and arched.
    g.setLineDash([8 * Math.max(s, 0.5), 7 * Math.max(s, 0.5)]);
    g.lineWidth = Math.max(1.2, 2 * s);
    g.strokeStyle = accent;
    g.globalAlpha = 0.67;
    for (const [a1, b1] of this.map.links) {
      const a = this.byId.get(a1), b = this.byId.get(b1);
      if (!a || !b || this.hidden.has(a.id) || this.hidden.has(b.id)) continue;
      const ax = X(a.x), ay = Y(a.y), bx = X(b.x), by = Y(b.y), mx = (ax + bx) / 2, my = (ay + by) / 2, dx = bx - ax, dy = by - ay;
      g.beginPath();
      g.moveTo(ax, ay);
      g.quadraticCurveTo(mx - dy * 0.25, my + dx * 0.25, bx, by);
      g.stroke();
    }
    g.setLineDash([]);
    g.globalAlpha = 1;

    // Ideas.
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    for (const n of this.map.nodes) {
      if (this.hidden.has(n.id)) continue;
      const geo = this.geometry(n), b = geo.block, root = n.parent < 0;
      const w = geo.w * s, h = geo.h * s, l = X(n.x) - w / 2, t = Y(n.y) - h / 2;
      const radius = n.image ? Math.min(h / 2, 16 * s) : h / 2;
      const color = css(this.colorOf.get(n.id) || 'secondary');
      g.beginPath();
      roundRect(g, l, t, w, h, radius);
      if (root) {
        g.fillStyle = n.color > 0 ? css(PALETTE[n.color - 1]) : css('brand');
        g.fill();
      } else {
        g.fillStyle = surface;
        g.fill();
        g.strokeStyle = color;
        g.lineWidth = Math.max(1, 2 * s);
        g.stroke();
      }
      if (n.id === selected || n === connectFrom) {
        g.strokeStyle = n === connectFrom ? css('tile-orange') : accent;
        g.lineWidth = 3;
        g.beginPath();
        roundRect(g, l - 4, t - 4, w + 8, h + 8, radius + 4);
        g.stroke();
      }
      // Picture above the words.
      if (n.image) {
        const img = this.pictures.get(n.image);
        const pw = geo.picW * s, ph = geo.picH * s, px = X(n.x) - pw / 2, py = t + 8 * s;
        if (img && img.complete && img.naturalWidth) {
          g.save();
          g.beginPath();
          roundRect(g, px, py, pw, ph, 10 * s);
          g.clip();
          g.drawImage(img, px, py, pw, ph);
          g.restore();
        } else {
          g.fillStyle = css('placeholder');
          g.beginPath();
          roundRect(g, px, py, pw, ph, 10 * s);
          g.fill();
        }
      }
      const textMid = t + h - geo.textH * s / 2;
      let y = textMid - b.h * s / 2;
      g.font = fontOf(root, (root ? 18 : 15) * s);
      g.fillStyle = root ? '#000' : n.text ? ink : muted;
      for (const line of b.title) {
        g.fillText(line, X(n.x), y + b.titleLine * s / 2);
        y += b.titleLine * s;
      }
      if (b.sub.length) {
        y += SUB_GAP * s;
        g.font = fontOf(false, (root ? 13.5 : 12.5) * s);
        g.fillStyle = root ? 'rgba(0,0,0,0.7)' : muted;
        for (const line of b.sub) {
          g.fillText(line, X(n.x), y + b.subLine * s / 2);
          y += b.subLine * s;
        }
      }
      // Badges: "+N" on a folded idea (outward), an arrow on one linked to a note.
      const r = 11 * Math.max(s, 0.6);
      if (n.collapsed) {
        const bx = this.side(n) > 0 ? l + w + r * 1.2 : l - r * 1.2;
        g.fillStyle = color;
        g.beginPath();
        g.arc(bx, Y(n.y), r, 0, Math.PI * 2);
        g.fill();
        g.fillStyle = '#fff';
        g.font = `bold ${r * 1.05}px Roboto, sans-serif`;
        g.fillText('+' + (this.hiddenCount.get(n.id) || 0), bx, Y(n.y));
      }
      if (n.linkUuid || n.link) {
        g.fillStyle = accent;
        g.beginPath();
        g.arc(l + w, t, r * 0.9, 0, Math.PI * 2);
        g.fill();
        g.fillStyle = '#fff';
        g.font = `bold ${r}px Roboto, sans-serif`;
        g.fillText('↗', l + w, t);
      }
    }
  }

  // -------------------------------------------------------------------- tidy

  /** Target positions of a balanced tree: first-level ideas alternate right and left. */
  tidyTargets() {
    const target = new Map();
    let top = 0;
    // Each centre's tree on its own, the trees stacked downwards.
    for (const root of this.roots()) {
      const right = [], left = [];
      let i = 0;
      for (const n of this.map.nodes) if (n.parent === root.id) (i++ % 2 === 0 ? right : left).push(n);
      const sum = col => col.reduce((s, n) => s + this.span(n), 0);
      const height = Math.max(this.geometry(root).h, sum(right), sum(left));
      const cy = top + height / 2;
      target.set(root.id, [0, cy]);
      const rootW = this.geometry(root).w;
      this.placeColumn(right, 1, 0, rootW, cy, target);
      this.placeColumn(left, -1, 0, rootW, cy, target);
      top += height + 120;
    }
    return target;
  }

  placeColumn(column, side, parentX, parentW, centerY, target) {
    let total = 0;
    for (const n of column) total += this.span(n);
    let y = centerY - total / 2;
    for (const n of column) {
      const span = this.span(n), cy = y + span / 2, w = this.geometry(n).w;
      const x = parentX + side * Math.max(COLUMN, parentW / 2 + w / 2 + 48);
      target.set(n.id, [x, cy]);
      this.placeColumn(this.children(n), side, x, w, cy, target);
      y += span;
    }
  }

  span(n) {
    const own = this.geometry(n).h + ROW - NODE_H;
    if (n.collapsed) return own;
    let sum = 0;
    for (const k of this.children(n)) sum += this.span(k);
    return Math.max(own, sum);
  }
}

function roundRect(g, x, y, w, h, r) {
  r = Math.max(0, Math.min(r, w / 2, h / 2));
  g.moveTo(x + r, y);
  g.arcTo(x + w, y, x + w, y + h, r);
  g.arcTo(x + w, y + h, x, y + h, r);
  g.arcTo(x, y + h, x, y, r);
  g.arcTo(x, y, x + w, y, r);
  g.closePath();
}

/** Draws a whole map fitted into a canvas (the card preview). */
export function drawPreview(canvas, map, pictures) {
  const view = new MapView(map, pictures);
  const ratio = window.devicePixelRatio || 1;
  const cw = canvas.clientWidth || 300, ch = canvas.clientHeight || 140;
  canvas.width = Math.round(cw * ratio);
  canvas.height = Math.round(ch * ratio);
  const g = canvas.getContext('2d');
  g.setTransform(ratio, 0, 0, ratio, 0, 0);
  g.clearRect(0, 0, cw, ch);
  const b = view.contentBounds(), pad = 10;
  const w = b.maxX - b.minX, h = b.maxY - b.minY;
  const s = Math.min(1, (cw - pad * 2) / Math.max(w, 1), (ch - pad * 2) / Math.max(h, 1));
  const ox = cw / 2 - (b.minX + w / 2) * s, oy = ch / 2 - (b.minY + h / 2) * s;
  view.draw(g, s, ox, oy, { surface: getComputedStyle(canvas).getPropertyValue('--card-surface').trim() || undefined });
}
