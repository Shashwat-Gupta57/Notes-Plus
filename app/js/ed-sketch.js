// Sketches (SketchActivity / SketchEditor): pen, marker and eraser in the apps' inks and sizes,
// undo and redo. Strokes are the apps' vectors — [{c: ARGB, w, t: 0 pen | 1 marker | 2 eraser,
// p: [x, y, …]}] — kept in the note's `sketch` (which syncs), so a sketch made here reopens
// editable on the phone and the PC.

import { h, icon, iconButton } from './ui.js';

export const INKS = [0xFF1C1C1E, 0xFF8E8E93, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF007AFF, 0xFFAF52DE];
const SIZES = [3, 7, 16];
export const PEN = 0, MARKER = 1, ERASER = 2;
const PAPER = '#FFFFFF';

const argb = (c, alpha) => {
  const u = c >>> 0;
  return `rgba(${(u >> 16) & 255},${(u >> 8) & 255},${u & 255},${alpha})`;
};

export function parseStrokes(json) {
  try {
    const a = JSON.parse(json || '[]');
    return Array.isArray(a) ? a.filter(s => s && Array.isArray(s.p)).map(s => ({ c: Number(s.c) || INKS[0], w: Number(s.w) || 3, t: Number(s.t) || 0, p: s.p.map(Number) })) : [];
  } catch {
    return [];
  }
}

export function drawStroke(g, s, scale = 1) {
  const p = s.p;
  if (p.length < 2) return;
  g.lineCap = 'round';
  g.lineJoin = 'round';
  g.strokeStyle = s.t === ERASER ? PAPER : argb(s.c, s.t === MARKER ? 110 / 255 : 1);
  g.lineWidth = (s.t === MARKER ? s.w * 2.4 : s.t === ERASER ? s.w * 3 : s.w) * scale;
  g.beginPath();
  g.moveTo(p[0] * scale, p[1] * scale);
  if (p.length === 2) g.lineTo(p[0] * scale + 0.1, p[1] * scale);
  for (let i = 2; i + 1 < p.length; i += 2) {
    const px = p[i - 2] * scale, py = p[i - 1] * scale, x = p[i] * scale, y = p[i + 1] * scale;
    g.quadraticCurveTo(px, py, (px + x) / 2, (py + y) / 2);
  }
  g.stroke();
}

/** The ink's bounds, for a card preview cropped to the drawing. */
export function inkBounds(strokes) {
  let l = Infinity, t = Infinity, r = -Infinity, b = -Infinity;
  for (const s of strokes) {
    if (s.t === ERASER) continue;
    for (let i = 0; i + 1 < s.p.length; i += 2) {
      l = Math.min(l, s.p[i]); r = Math.max(r, s.p[i]);
      t = Math.min(t, s.p[i + 1]); b = Math.max(b, s.p[i + 1]);
    }
  }
  return l === Infinity ? null : { l: l - 12, t: t - 12, r: r + 12, b: b + 12 };
}

/** Draws strokes fitted into a canvas (the card preview). */
export function drawSketchPreview(canvas, json) {
  const strokes = parseStrokes(json);
  const ratio = window.devicePixelRatio || 1, cw = canvas.clientWidth || 300;
  const box = inkBounds(strokes);
  const g = canvas.getContext('2d');
  if (!box) { canvas.height = 0; return false; }
  const s = Math.min(2, cw / (box.r - box.l));
  const ch = Math.min(320, Math.max(60, (box.b - box.t) * s));
  canvas.style.height = ch + 'px';
  canvas.width = Math.round(cw * ratio);
  canvas.height = Math.round(ch * ratio);
  g.setTransform(ratio, 0, 0, ratio, 0, 0);
  g.fillStyle = PAPER;
  g.fillRect(0, 0, cw, ch);
  g.translate((cw - (box.r - box.l) * s) / 2 - box.l * s, -box.t * s);
  for (const st of strokes) drawStroke(g, st, s);
  return true;
}

export function sketchEditor(ctx) {
  const strokes = parseStrokes(ctx.note.sketch);
  const undone = [];
  let tool = PEN, color = INKS[0], size = SIZES[0], current = null, revision = 0;
  try { revision = JSON.parse(ctx.note.data || '{}').rev || 0; } catch { revision = 0; }

  const title = h('input.map-title', { type: 'text', placeholder: 'Sketch', readOnly: ctx.readOnly, maxlength: 500 });
  title.value = ctx.note.title || '';
  title.addEventListener('input', () => ctx.changed());
  const canvas = h('canvas.sketch-canvas');
  const sheet = h('div.sketch-sheet', canvas);
  let ratio = 1, W = 0, H = 0;

  function resize() {
    const r = sheet.getBoundingClientRect();
    ratio = window.devicePixelRatio || 1;
    W = r.width;
    H = Math.max(r.height, 400);
    canvas.width = Math.round(W * ratio);
    canvas.height = Math.round(H * ratio);
    canvas.style.width = W + 'px';
    canvas.style.height = H + 'px';
    redraw();
  }

  function redraw() {
    const g = canvas.getContext('2d');
    g.setTransform(ratio, 0, 0, ratio, 0, 0);
    g.fillStyle = PAPER;
    g.fillRect(0, 0, W, H);
    for (const s of strokes) drawStroke(g, s);
    if (current) drawStroke(g, current);
  }

  const pos = e => { const r = canvas.getBoundingClientRect(); return [Math.round((e.clientX - r.left) * 10) / 10, Math.round((e.clientY - r.top) * 10) / 10]; };
  if (!ctx.readOnly) {
    canvas.addEventListener('pointerdown', e => {
      if (e.button !== 0 && e.pointerType === 'mouse') return;
      canvas.setPointerCapture(e.pointerId);
      current = { c: color, w: size, t: tool, p: pos(e) };
      redraw();
    });
    canvas.addEventListener('pointermove', e => {
      if (!current) return;
      const events = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
      for (const ev of events) current.p.push(...pos(ev));
      redraw();
    });
    const finish = () => {
      if (!current) return;
      strokes.push(current);
      current = null;
      undone.length = 0;
      revision++;
      redraw();
      paintTools();
      ctx.changed();
    };
    canvas.addEventListener('pointerup', finish);
    canvas.addEventListener('pointercancel', finish);
  }

  const undoBtn = iconButton('undo', 'Undo (Ctrl+Z)', () => undo());
  const redoBtn = iconButton('redo', 'Redo (Ctrl+Y)', () => redo());
  function undo() { if (strokes.length) { undone.push(strokes.pop()); revision++; redraw(); paintTools(); ctx.changed(); } }
  function redo() { if (undone.length) { strokes.push(undone.pop()); revision++; redraw(); paintTools(); ctx.changed(); } }

  const toolBtns = [['brush', PEN, 'Pen (P)'], ['marker', MARKER, 'Marker (M)'], ['eraser', ERASER, 'Eraser (E)']]
    .map(([ic, t, tip]) => { const b = iconButton(ic, tip, () => { tool = t; paintTools(); }); b._tool = t; return b; });
  const inkBtns = INKS.map(c => { const b = h('button.ink', { type: 'button', title: 'Ink', style: { background: argb(c, 1) }, onClick: () => { color = c; if (tool === ERASER) tool = PEN; paintTools(); } }); b._c = c; return b; });
  const sizeBtns = SIZES.map(s => { const b = h('button.size', { type: 'button', title: 'Size', onClick: () => { size = s; paintTools(); } }, h('span', { style: { width: (s + 3) + 'px', height: (s + 3) + 'px' } })); b._s = s; return b; });
  function paintTools() {
    toolBtns.forEach(b => b.classList.toggle('on', b._tool === tool));
    inkBtns.forEach(b => b.classList.toggle('on', b._c === color && tool !== ERASER));
    sizeBtns.forEach(b => b.classList.toggle('on', b._s === size));
    undoBtn.disabled = !strokes.length;
    redoBtn.disabled = !undone.length;
  }
  paintTools();
  const tools = ctx.readOnly ? null : h('div.sketch-tools', ...toolBtns, h('span.sep'), ...inkBtns, h('span.sep'), ...sizeBtns, h('span.sep'), undoBtn, redoBtn);

  const ro = new ResizeObserver(() => requestAnimationFrame(resize));
  ro.observe(sheet);

  return {
    node: h('div.sketch-page', h('div.map-head', title), tools, sheet),
    collect(note) {
      note.title = title.value.trim();
      note.sketch = strokes.length ? JSON.stringify(strokes) : null;
      note.data = JSON.stringify({ rev: revision, strokes: strokes.length });
      note.content = note.title || 'Sketch';
      return strokes.length > 0 || !!note.title;
    },
    onKey(e) {
      if (ctx.readOnly || document.activeElement === title) return false;
      const k = e.key.toLowerCase();
      if ((e.ctrlKey || e.metaKey) && k === 'z' && !e.shiftKey) { e.preventDefault(); undo(); return true; }
      if ((e.ctrlKey || e.metaKey) && (k === 'y' || (k === 'z' && e.shiftKey))) { e.preventDefault(); redo(); return true; }
      if (!e.ctrlKey && !e.metaKey && k === 'p') { tool = PEN; paintTools(); return true; }
      if (!e.ctrlKey && !e.metaKey && k === 'm') { tool = MARKER; paintTools(); return true; }
      if (!e.ctrlKey && !e.metaKey && k === 'e') { tool = ERASER; paintTools(); return true; }
      return false;
    },
    destroy() { ro.disconnect(); },
  };
}

export { icon };
