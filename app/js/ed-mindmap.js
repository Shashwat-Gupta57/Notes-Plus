// The mind map editor (MindMapActivity / MindMapEditor): drag to look around, wheel or pinch to
// zoom, drag an idea to move it (a folded one carries its branch), double-click to rename,
// right-click (or press and hold) for everything else. Tab adds an idea, Enter a sibling, F2
// renames, Delete removes a branch.

import { h, icon, toast, prompt, choices, menu, button, dialog } from './ui.js';
import { mindMap, mindMapJson, mediaName } from './model.js';
import { MapView, COLUMN, ROW, PALETTE } from './mindmap-draw.js';
import * as db from './db.js';

export function mindMapEditor(ctx) {
  const n0 = ctx.note;
  const map = mindMap(n0.data);
  if (!map.nodes.length) map.nodes.push({ id: 1, parent: -1, text: '', x: 0, y: 0, color: 0, collapsed: false, link: 0, linkUuid: null, image: null, sub: '' });
  const pictures = new Map();
  const view = new MapView(map, pictures);
  let scale = 1, tx = 0, ty = 0;
  let selected = -1, connectFrom = null;
  let width = 0, height = 0, ratio = 1;

  const title = h('input.map-title', { type: 'text', placeholder: 'Mind map', readOnly: ctx.readOnly, maxlength: 500 });
  title.value = n0.title || '';
  title.addEventListener('input', () => ctx.changed());
  const canvas = h('canvas.map-canvas', { tabindex: 0 });
  const hint = h('div.map-hint', ctx.readOnly ? 'Drag to look around · scroll to zoom'
    : 'Tab child · Enter sibling · Shift+Enter subtext · Space fold · Ctrl+Z undo · Double-click rename');
  const add = ctx.readOnly ? null : h('button.fab', { type: 'button', onClick: () => addChild(selectedNode() || view.root()) }, icon('add', 22, 'var(--on-brand)'), 'Add idea');
  const tidyBtn = ctx.readOnly ? null : h('button.mini-fab', { type: 'button', title: 'Tidy', onClick: () => tidy() }, icon('tidy', 22));
  const fitBtn = h('button.mini-fab', { type: 'button', title: 'Fit (double-click empty space)', onClick: () => fit(true) }, icon('center', 22));
  const stage = h('div.map-stage', canvas, h('div.map-fabs', fitBtn, tidyBtn, add), hint);
  const node = h('div.map-page', h('div.map-head', title), stage);

  // Pictures in ideas, from this browser's files.
  async function loadPictures() {
    for (const n of map.nodes) {
      if (!n.image || pictures.has(n.image)) continue;
      pictures.set(n.image, null);
      const u = await ctx.fileUrl(n.image);
      if (!u) continue;
      const img = new Image();
      img.onload = () => { pictures.set(n.image, img); view.blocks.clear(); draw(); };
      img.src = u;
    }
  }

  // ------------------------------------------------------------------ drawing
  function resize() {
    const r = stage.getBoundingClientRect();
    ratio = window.devicePixelRatio || 1;
    width = r.width;
    height = r.height;
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    canvas.style.width = width + 'px';
    canvas.style.height = height + 'px';
    draw();
  }

  function draw() {
    const g = canvas.getContext('2d');
    g.setTransform(ratio, 0, 0, ratio, 0, 0);
    g.clearRect(0, 0, width, height);
    // A quiet dot grid that moves with the map.
    const step = 24 * scale;
    if (step >= 14) {
      g.fillStyle = getComputedStyle(document.documentElement).getPropertyValue('--divider');
      for (let x = ((tx % step) + step) % step; x < width; x += step) {
        for (let y = ((ty % step) + step) % step; y < height; y += step) g.fillRect(x - 1, y - 1, 2, 2);
      }
    }
    view.draw(g, scale, tx, ty, { selected, connectFrom, surface: getComputedStyle(node).getPropertyValue('--card-surface').trim() || undefined });
  }

  function fit(animate) {
    if (!width) return;
    const b = view.contentBounds(), pad = 56;
    const w = b.maxX - b.minX, hh = b.maxY - b.minY;
    const s = Math.min(1.2, (width - pad * 2) / Math.max(w, 1), (height - pad * 2) / Math.max(hh, 1));
    const to = { s, x: width / 2 - (b.minX + w / 2) * s, y: height / 2 - (b.minY + hh / 2) * s };
    if (!animate) { scale = to.s; tx = to.x; ty = to.y; draw(); return; }
    const from = { s: scale, x: tx, y: ty };
    animateTo(t => { scale = from.s + (to.s - from.s) * t; tx = from.x + (to.x - from.x) * t; ty = from.y + (to.y - from.y) * t; });
  }

  function animateTo(step, ms = 320, done) {
    const start = performance.now();
    const frame = now => {
      const t = Math.min(1, (now - start) / ms), e = 1 - Math.pow(1 - t, 3);
      step(e);
      draw();
      if (t < 1) requestAnimationFrame(frame); else done && done();
    };
    requestAnimationFrame(frame);
  }

  const toMap = (sx, sy) => ({ x: (sx - tx) / scale, y: (sy - ty) / scale });

  function ensureVisible(n) {
    const g = view.geometry(n), m = 32;
    const l = n.x * scale + tx - g.w * scale / 2, r = n.x * scale + tx + g.w * scale / 2;
    const t = n.y * scale + ty - g.h * scale / 2, b = n.y * scale + ty + g.h * scale / 2;
    if (r > width - m) tx -= r - width + m;
    if (l < m) tx += m - l;
    if (b > height - m) ty -= b - height + m;
    if (t < m) ty += m - t;
  }

  // ------------------------------------------------------------- model edits
  const selectedNode = () => view.byId.get(selected) || null;
  const nextId = () => map.nodes.reduce((m, n) => Math.max(m, n.id), 0) + 1;
  const blank = (id, parent, x, y) => ({ id, parent, text: '', x, y, color: 0, collapsed: false, link: 0, linkUuid: null, image: null, sub: '' });

  function changedStructure() {
    view.refresh();
    draw();
    ctx.changed();
  }

  function select(n) {
    selected = n ? n.id : -1;
    draw();
  }

  function addChildNode(parent) {
    const root = view.rootOf(parent);
    let side;
    if (parent.parent < 0) {
      let right = 0, left = 0;
      for (const n of map.nodes) if (n.parent === parent.id) (n.x >= parent.x ? right++ : left++);
      side = right <= left ? 1 : -1;
    } else {
      side = Math.sign(parent.x - (root ? root.x : 0)) || 1;
    }
    let y = parent.y, any = false;
    for (const n of map.nodes) {
      if (n.parent === parent.id && Math.sign(n.x - parent.x) === side) {
        const below = n.y + Math.max(ROW, view.geometry(n).h / 2 + 44);
        y = any ? Math.max(y, below) : below;
        any = true;
      }
    }
    const gap = Math.max(COLUMN, view.geometry(parent).w / 2 + 110);
    const child = blank(nextId(), parent.id, parent.x + side * gap, y);
    map.nodes.push(child);
    view.refresh();
    select(child);
    ensureVisible(child);
    return child;
  }

  function addChild(parent) {
    if (!parent) return;
    if (parent.collapsed) { parent.collapsed = false; view.refresh(); }
    name(addChildNode(parent), true);
  }

  function shiftBranch(n, dx) {
    const queue = [n.id];
    n.x += dx;
    for (let q = 0; q < queue.length; q++) for (const k of map.nodes) if (k.parent === queue[q]) { k.x += dx; queue.push(k.id); }
  }

  function insertBehind(n) {
    if (n.parent < 0) return null;
    const side = view.side(n);
    const mid = blank(nextId(), n.parent, n.x, n.y);
    shiftBranch(n, side * COLUMN);
    n.parent = mid.id;
    map.nodes.push(mid);
    view.refresh();
    select(mid);
    ensureVisible(mid);
    return mid;
  }

  function unwrapNode(mid) {
    const side = view.side(mid);
    for (const k of [...map.nodes]) if (k.parent === mid.id) { k.parent = mid.parent; shiftBranch(k, -side * COLUMN); }
    map.nodes.splice(map.nodes.indexOf(mid), 1);
    map.links = map.links.filter(l => l[0] !== mid.id && l[1] !== mid.id);
    selected = -1;
    changedStructure();
  }

  /** A central idea can go while another stays (its tree goes with it). */
  const canRemove = n => n.parent >= 0 || view.roots().length > 1;

  /** Another centre, below everything there is: its own tree, connected to the others only by connections. */
  function addRoot() {
    let bottom = 0, any = false;
    for (const n of map.nodes) {
      const b = n.y + view.geometry(n).h / 2;
      bottom = any ? Math.max(bottom, b) : b;
      any = true;
    }
    const first = view.root();
    const r = blank(nextId(), -1, first ? first.x : 0, any ? bottom + 160 : 0);
    map.nodes.push(r);
    view.refresh();
    select(r);
    ensureVisible(r);
    name(r, true);
  }

  function removeBranch(n) {
    if (!canRemove(n)) return;
    const doomed = new Set([n.id]);
    let grew = true;
    while (grew) {
      grew = false;
      for (const k of map.nodes) if (!doomed.has(k.id) && doomed.has(k.parent)) { doomed.add(k.id); grew = true; }
    }
    map.nodes = map.nodes.filter(k => !doomed.has(k.id));
    view.map = map;
    map.links = map.links.filter(l => !doomed.has(l[0]) && !doomed.has(l[1]));
    selected = -1;
    changedStructure();
  }

  /** Names an idea; a new one gets "Next idea", so a brainstorm never leaves the keyboard. */
  function name(n, isNew, behind = false) {
    const root = n.parent < 0;
    let next = false;
    const d = prompt({
      title: root ? 'Central idea' : isNew ? 'New idea' : 'Rename',
      hint: root ? 'What is this map about?' : 'An idea',
      value: n.text,
      ok: 'Done',
      allowEmpty: !isNew,
      onOk: v => { n.text = v.trim(); changedStructure(); select(n); if (next) { const p = view.byId.get(n.parent); if (p) name(addChildNode(p), true); } },
      onCancel: () => {
        if (!isNew || (root && !canRemove(n)) || n.text) return;
        if (behind) unwrapNode(n); else removeBranch(n);
      },
    });
    if (isNew && !root) {
      const row = d.box.querySelector('.dialog-buttons');
      row.prepend(h('button.btn.text', { type: 'button', onClick: () => { next = true; const f = d.box.querySelector('input'); if (f.value.trim()) { d.close(); n.text = f.value.trim(); changedStructure(); const p = view.byId.get(n.parent); if (p) name(addChildNode(p), true); } } }, 'Next idea'));
    }
  }

  function editSub(n) {
    prompt({
      title: 'Sub-text', hint: 'More about this idea', value: n.sub, multiline: true, allowEmpty: true, ok: 'Done',
      onOk: v => { n.sub = v.trim(); changedStructure(); select(n); },
    });
  }

  function pickColor(n) {
    const names = [n.parent < 0 ? 'Yellow (default)' : 'Same as its branch', 'Blue', 'Green', 'Orange', 'Purple', 'Red', 'Teal', 'Grey'];
    choices('Colour', names.map((label, i) => ({ text: (i === n.color ? '✓  ' : '') + label, onClick: () => { n.color = i; changedStructure(); } })));
  }

  async function pickNoteToLink(n) {
    const notes = (await db.allNotes()).filter(x => !x.deleted && x.uuid !== ctx.note.uuid);
    if (!notes.length) { toast('No other notes to link to'); return; }
    notes.sort((a, b) => b.timestamp - a.timestamp);
    choices('Link to a note', notes.slice(0, 60).map(x => ({ text: x.title || (x.content || '').split('\n')[0].slice(0, 60) || 'Untitled', onClick: () => { n.linkUuid = x.uuid; n.link = 0; changedStructure(); } })));
  }

  async function openLinked(n) {
    if (!n.linkUuid) { toast('That note is on another device'); return; }
    const target = await db.getNote(n.linkUuid);
    if (!target || target.deleted) { toast('That note is gone'); return; }
    await ctx.save();
    location.hash = '#/n/' + target.uuid;
  }

  function addPicture(n) {
    const input = h('input', { type: 'file', accept: 'image/*' });
    input.addEventListener('change', async () => {
      const f = input.files[0];
      if (!f) return;
      const name = mediaName('IMG', ((f.type.split('/')[1] || 'png').replace('jpeg', 'jpg')));
      await ctx.putFile(name, f);
      n.image = name;
      view.blocks.clear();
      await loadPictures();
      changedStructure();
    });
    input.click();
  }

  function options(n, anchor) {
    const root = n.parent < 0;
    const items = [
      { text: 'Rename', onClick: () => name(n, false) },
      { text: 'Add idea', onClick: () => addChild(n) },
      !root && { text: 'Add idea behind', onClick: () => { const m = insertBehind(n); if (m) name(m, true, true); } },
      { text: n.sub ? 'Edit sub-text' : 'Add sub-text', onClick: () => editSub(n) },
      { text: 'Colour', onClick: () => pickColor(n) },
      view.children(n).length && { text: n.collapsed ? 'Unfold branch' : 'Fold branch', onClick: () => { n.collapsed = !n.collapsed; changedStructure(); } },
      (n.linkUuid || n.link) ? { text: 'Open linked note', onClick: () => openLinked(n) } : { text: 'Link to a note…', onClick: () => pickNoteToLink(n) },
      (n.linkUuid || n.link) && { text: 'Remove note link', onClick: () => { n.link = 0; n.linkUuid = null; changedStructure(); } },
      { text: n.image ? 'Change picture…' : 'Add a picture…', onClick: () => addPicture(n) },
      n.image && { text: 'Remove picture', onClick: () => { n.image = null; view.blocks.clear(); changedStructure(); } },
      { text: 'Connect to another idea…', onClick: () => { connectFrom = n; draw(); toast('Click the idea to connect to'); } },
      map.links.some(l => l[0] === n.id || l[1] === n.id) && { text: 'Remove its connections', onClick: () => { map.links = map.links.filter(l => l[0] !== n.id && l[1] !== n.id); changedStructure(); } },
      { text: 'Add another central idea', onClick: () => addRoot() },
      canRemove(n) && { text: root ? 'Delete this central idea and its ideas' : 'Delete branch', danger: true, onClick: () => removeBranch(n) },
    ];
    menu(anchor, items);
  }

  function tidy() {
    const target = view.tidyTargets();
    const start = new Map(map.nodes.map(n => [n.id, [n.x, n.y]]));
    animateTo(t => {
      for (const n of map.nodes) {
        const s = start.get(n.id), e = target.get(n.id);
        if (!s || !e) continue;
        n.x = s[0] + (e[0] - s[0]) * t;
        n.y = s[1] + (e[1] - s[1]) * t;
      }
    }, 420, () => { ctx.changed(); fit(true); });
  }

  // ------------------------------------------------------------------- input
  const pointers = new Map();
  let dragging = null, panning = false, moved = false, last = null, pinch = null, holdTimer = null;

  function moveWithHidden(n, dx, dy) {
    n.x += dx;
    n.y += dy;
    if (!n.collapsed) return;
    const queue = [n.id];
    for (let q = 0; q < queue.length; q++) for (const k of map.nodes) if (k.parent === queue[q]) { k.x += dx; k.y += dy; queue.push(k.id); }
  }

  const local = e => { const r = canvas.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };

  canvas.addEventListener('pointerdown', e => {
    canvas.focus();
    canvas.setPointerCapture(e.pointerId);
    const p = local(e);
    pointers.set(e.pointerId, p);
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), s: scale, cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2 };
      dragging = null;
      panning = false;
      clearTimeout(holdTimer);
      return;
    }
    if (e.button !== 0 && e.pointerType === 'mouse') return;
    const m = toMap(p.x, p.y);
    const hit = view.hit(m.x, m.y);
    dragging = !ctx.readOnly && hit ? hit : null;
    panning = !dragging;
    moved = false;
    last = p;
    if (hit && e.pointerType !== 'mouse') holdTimer = setTimeout(() => { if (!moved && !ctx.readOnly) { select(hit); dragging = null; options(hit, fakeAnchor(e)); } }, 520);
  });

  canvas.addEventListener('pointermove', e => {
    if (!pointers.has(e.pointerId)) return;
    const p = local(e);
    pointers.set(e.pointerId, p);
    if (pinch && pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      zoomAt(pinch.cx, pinch.cy, (pinch.s * Math.hypot(a.x - b.x, a.y - b.y) / pinch.d) / scale);
      return;
    }
    if (!last) return;
    const dx = p.x - last.x, dy = p.y - last.y;
    if (Math.abs(dx) + Math.abs(dy) > 2) { moved = true; clearTimeout(holdTimer); }
    last = p;
    if (dragging) moveWithHidden(dragging, dx / scale, dy / scale);
    else if (panning) { tx += dx; ty += dy; }
    draw();
  });

  const end = e => {
    pointers.delete(e.pointerId);
    clearTimeout(holdTimer);
    if (pointers.size < 2) pinch = null;
    if (dragging && moved) ctx.changed();
    if (!moved && last && e.type === 'pointerup') {
      const m = toMap(last.x, last.y);
      const badge = view.hitBadge(m.x, m.y, scale);
      const linkHit = view.hitLink(m.x, m.y, scale);
      if (badge) { badge.collapsed = !badge.collapsed; changedStructure(); }
      else if (linkHit) openLinked(linkHit);
      else {
        const hit = view.hit(m.x, m.y);
        if (connectFrom) {
          if (hit && hit !== connectFrom && !map.links.some(l => (l[0] === connectFrom.id && l[1] === hit.id) || (l[1] === connectFrom.id && l[0] === hit.id))) {
            map.links.push([connectFrom.id, hit.id]);
            ctx.changed();
          }
          connectFrom = null;
          view.refresh();
          draw();
        } else select(hit);
      }
    }
    dragging = null;
    panning = false;
    last = null;
  };
  canvas.addEventListener('pointerup', end);
  canvas.addEventListener('pointercancel', end);

  canvas.addEventListener('dblclick', e => {
    const p = local(e), m = toMap(p.x, p.y), hit = view.hit(m.x, m.y);
    if (hit && !ctx.readOnly) name(hit, false); else fit(true);
  });

  canvas.addEventListener('contextmenu', e => {
    e.preventDefault();
    if (ctx.readOnly) return;
    const p = local(e), m = toMap(p.x, p.y), hit = view.hit(m.x, m.y);
    if (hit) { select(hit); options(hit, fakeAnchor(e)); }
  });

  function zoomAt(x, y, f) {
    const next = Math.max(0.25, Math.min(3, scale * f));
    const k = next / scale;
    tx = x - (x - tx) * k;
    ty = y - (y - ty) * k;
    scale = next;
    draw();
  }

  canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const p = local(e);
    if (e.ctrlKey || e.metaKey || Math.abs(e.deltaY) > 0 && !e.shiftKey && e.deltaMode === 0 && Math.abs(e.deltaX) < 1) zoomAt(p.x, p.y, Math.exp(-e.deltaY * 0.0015));
    else { tx -= e.deltaX; ty -= e.deltaY; draw(); }
  }, { passive: false });

  function fakeAnchor(e) {
    return { getBoundingClientRect: () => ({ right: e.clientX + 4, bottom: e.clientY, left: e.clientX, top: e.clientY }) };
  }

  const ro = new ResizeObserver(() => requestAnimationFrame(() => { const first = !width; resize(); if (first) fit(false); }));
  ro.observe(stage);
  const redraw = () => draw();
  const scheme = window.matchMedia('(prefers-color-scheme: dark)');
  scheme.addEventListener('change', redraw);
  loadPictures();

  return {
    node,
    collect(note) {
      note.title = title.value.trim();
      note.data = mindMapJson(map);
      note.content = map.nodes.flatMap(n => [n.text, n.sub]).filter(Boolean).join('\n');
      return !!(note.content.trim() || note.title);
    },
    wrap: () => map.wrap !== false,
    setWrap(w) { map.wrap = w; view.blocks.clear(); draw(); },
    focus(isNew) {
      canvas.focus();
      if (isNew && !ctx.readOnly) setTimeout(() => name(view.root(), true), 120);
    },
    onKey(e) {
      if (ctx.readOnly || document.activeElement === title) return false;
      const s = selectedNode();
      if (e.key === 'Tab') { e.preventDefault(); addChild(s || view.root()); return true; }
      if (e.key === 'Enter' && s) { e.preventDefault(); const p = view.byId.get(s.parent); if (p) addChild(p); else name(s, false); return true; }
      if (e.key === 'F2' && s) { e.preventDefault(); name(s, false); return true; }
      if ((e.key === 'Delete' || e.key === 'Backspace') && s && canRemove(s)) { e.preventDefault(); removeBranch(s); return true; }
      if ((e.ctrlKey || e.metaKey) && (e.key === '=' || e.key === '+')) { e.preventDefault(); zoomAt(width / 2, height / 2, 1.2); return true; }
      if ((e.ctrlKey || e.metaKey) && e.key === '-') { e.preventDefault(); zoomAt(width / 2, height / 2, 1 / 1.2); return true; }
      if ((e.ctrlKey || e.metaKey) && e.key === '0') { e.preventDefault(); fit(true); return true; }
      return false;
    },
    destroy() { ro.disconnect(); scheme.removeEventListener('change', redraw); },
  };
}

export { PALETTE, button, dialog };
