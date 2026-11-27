// Ultimate notes (the apps' UltimateBlocks): one page where words, checklists, mind maps, voice
// recordings, days and pictures flow together as elements of the same canvas, with no frames between
// them. Every element can sit inside another: indented under a list item or words (its `indent`), or
// inside a mind map's idea (the idea's `blocks`, opened in a panel that is itself such a page, to any
// depth). "/" on an empty line, the bar at the bottom, a list item's ⊕ and an idea's menu put things in;
// the handle that shows beside an element on hover moves it (drag up/down, and right/left to nest it).
// Each element is the real editor of its type, given a small context of its own. Kept as
// `{"blocks": [...]}` (model.js), with every element's words in `content`.

import { h, icon, confirm, menu } from './ui.js';
import { richBody, formatBar, listEditor, titleField, footer, editedLine, pickFiles } from './ed-text.js';
import { mindMapEditor } from './ed-mindmap.js';
import { audioEditor } from './ed-audio.js';
import { blocks as parseBlocks, blocksJson, blocksContent, blockId, mapHeight, indentOf, insideSummary,
  MAP_H, MAP_MIN_H, MAP_MAX_H, MOODS, dayStart, LIST, MINDMAP, AUDIO } from './model.js';

const KINDS = {
  text: { label: 'Text', hint: 'Just words', icon: 'noteText', accent: 'tile-orange' },
  list: { label: 'Checklist', hint: 'Things to tick off', icon: 'noteList', accent: 'tile-green' },
  mindmap: { label: 'Mind map', hint: 'Ideas and how they connect', icon: 'noteMindmap', accent: 'tile-blue' },
  audio: { label: 'Voice', hint: 'Record and play back', icon: 'noteAudio', accent: 'tile-teal' },
  day: { label: 'Day', hint: 'A date and a mood', icon: 'noteJournal', accent: 'tile-purple' },
};
const STEP = 28; // px per level of nesting
const anchorAt = r => ({ getBoundingClientRect: () => r });
const summaryOf = list => insideSummary(parseBlocks(JSON.stringify({ blocks: list || [] })));

export function ultimateEditor(ctx) {
  const n = ctx.note;
  // Shared by the page and every panel opened from it.
  const shared = { activeText: null, onCaret: () => {}, maps: new Set(), panels: [] };
  const toolbar = formatBar(ctx, {
    editor: () => (shared.activeText || page.firstText()).body.editor(),
    onCaret: f => { shared.onCaret = f; },
  });
  const page = column(ctx, parseBlocks(n.data), shared, { root: true, content: n.content || '' });
  const title = titleField(ctx, 'Title', () => page.focusFirst());
  const foot = footer(ctx, () => editedLine(ctx.note));
  const bar = ctx.readOnly ? null : addBar(kind => page.insertKind(kind), () => page.addPictures());
  const node = h('div.page.ult-page', title, toolbar, page.node, foot, bar);

  return {
    node,
    collect(note) {
      note.title = title.value.trim();
      const out = page.value();
      note.data = blocksJson(out);
      note.content = blocksContent(out);
      return !!(note.title || note.content.trim() || out.some(b => b.kind !== 'text'));
    },
    focus(isNew) { if (isNew) title.focus(); },
    onKey(e) {
      for (const m of shared.maps) if (m.el.contains(document.activeElement)) return m.impl.onKey(e);
      return false;
    },
    destroy() {
      while (shared.panels.length) shared.panels[shared.panels.length - 1].close();
      page.destroy();
    },
  };
}

/** The floating bar of things to add; it never takes the caret away from the words. */
function addBar(onKind, onPicture) {
  const button = (label, ic, accent, onClick) => h('button', {
    type: 'button', title: 'Add ' + label.toLowerCase(),
    onmousedown: e => e.preventDefault(), onClick,
  }, icon(ic, 20, `var(--${accent})`), h('span', label));
  return h('div.ult-bar', { role: 'toolbar', 'aria-label': 'Add to this note' },
    ...Object.entries(KINDS).map(([kind, k]) => button(k.label, k.icon, k.accent, () => onKind(kind))),
    button('Picture', 'photo', 'tile-red', onPicture));
}

/** "What goes here?": the kinds, then `onPick(kind)` ('picture' too, when offered). */
function kindMenu(anchor, onPick, withPicture = true) {
  const items = Object.entries(KINDS).map(([kind, k]) => ({ text: `${k.label}  ·  ${k.hint}`, onClick: () => onPick(kind) }));
  if (withPicture) items.push({ text: 'Picture  ·  From your files', onClick: () => onPick('picture') });
  menu(anchor, items);
}

/**
 * A page of elements: the note itself, or what's inside an idea. {node, value(), insertKind(kind),
 * addPictures(), firstText(), focusFirst(), destroy()}.
 */
function column(ctx, stored, shared, opts = {}) {
  const views = [];
  const node = h('div.ult');
  let focused = null;

  if (opts.root && (!stored.length || stored[0].kind !== 'text')) {
    stored.unshift({ id: blockId(), kind: 'text', md: stored.length ? '' : opts.content || '' });
  }
  if (!stored.length) stored.push({ id: blockId(), kind: 'text', md: '' });

  // ---------------------------------------------------------------- views

  /** A context of its own for an embedded editor: its "note" is the element, its changes are this note's. */
  const sub = (type, b, extra = {}) => ({
    note: { type, title: '', content: '', data: JSON.stringify(b), timestamp: ctx.note.timestamp },
    readOnly: ctx.readOnly, noDates: true, embedded: true, syncStatus: '', onSyncStatus: () => () => {},
    changed: () => ctx.changed(), putFile: (name, blob) => ctx.putFile(name, blob), fileUrl: name => ctx.fileUrl(name),
    save: ctx.save, linkNotes: ctx.linkNotes, onLinks: ctx.onLinks, openLink: ctx.openLink, self: ctx.self,
    ...extra,
  });
  /** The embedded editor's data, as this element. */
  const collected = (impl, b, extra) => {
    const probe = { title: '', content: '', data: null };
    impl.collect(probe);
    let data = {};
    try { data = JSON.parse(probe.data) || {}; } catch { /* Kept empty. */ }
    return { ...data, ...extra, id: b.id, kind: b.kind };
  };

  function textView(b) {
    const v = { b };
    const first = opts.root && !views.length;
    v.body = richBody(ctx, b.md || '', first ? 'Start writing. Type / for a checklist, a mind map, voice…' : '', {
      onSlash: (rect, clear) => {
        clear();
        const parts = v.body.split();
        kindMenu(anchorAt(rect), kind => {
          if (kind === 'picture') pickFiles('image/*', true, files => { v.body.addPicture(files); ctx.changed(); });
          else splitInsert(v, kind, parts);
        });
      },
    });
    v.body.onCaret(() => { shared.activeText = v; shared.onCaret(); });
    v.node = v.body.node;
    v.value = () => ({ id: b.id, kind: 'text', md: v.body.value().trim() });
    v.focus = () => v.body.focusStart();
    return v;
  }

  function listView(b) {
    const v = { b };
    v.impl = listEditor(sub(LIST, b, {
      onItemInside: ctx.readOnly ? null : (i, btn) => kindMenu(btn, kind => nestUnderItem(v, i, kind)),
    }));
    v.node = v.impl.node;
    v.value = () => collected(v.impl, b);
    v.focus = () => v.impl.focusItem(0);
    return v;
  }

  function mapView(b) {
    const v = { b };
    v.impl = mindMapEditor(sub(MINDMAP, b, {
      insideLabel: idea => summaryOf(idea.blocks),
      inside: (idea, kind, refresh) => openInside(ctx, idea, kind, refresh, shared),
      insideMenu: (idea, anchor, open) => kindMenu(anchor, kind => open()(idea, kind === 'picture' ? 'text' : kind), false),
    }));
    let height = mapHeight(b);
    const frame = h('div.ub-map', v.impl.node);
    frame.style.height = height + 'px';
    const grip = ctx.readOnly ? null : h('div.ub-resize', { title: 'Drag to make the map taller or shorter', role: 'separator', 'aria-orientation': 'horizontal' });
    if (grip) grip.addEventListener('pointerdown', e => {
      e.preventDefault();
      grip.setPointerCapture(e.pointerId);
      const y0 = e.clientY, h0 = height;
      frame.classList.add('sizing');
      const move = ev => { height = Math.max(MAP_MIN_H, Math.min(MAP_MAX_H, Math.round(h0 + ev.clientY - y0))); frame.style.height = height + 'px'; };
      const up = () => {
        frame.classList.remove('sizing');
        grip.removeEventListener('pointermove', move);
        for (const t of ['pointerup', 'pointercancel', 'lostpointercapture']) grip.removeEventListener(t, up);
        if (height !== h0) ctx.changed();
      };
      grip.addEventListener('pointermove', move);
      for (const t of ['pointerup', 'pointercancel', 'lostpointercapture']) grip.addEventListener(t, up);
    });
    v.node = h('div.ub-mapwrap', frame, grip);
    v.value = () => collected(v.impl, b, { h: height });
    v.focus = isNew => v.impl.focus(isNew);
    v.mapRef = { el: frame, impl: v.impl };
    shared.maps.add(v.mapRef);
    return v;
  }

  function audioView(b) {
    const v = { b };
    v.impl = audioEditor(sub(AUDIO, b));
    v.node = v.impl.node;
    v.value = () => collected(v.impl, b);
    return v;
  }

  function dayView(b) {
    const d = { day: Number(b.day) || dayStart(Date.now()), mood: typeof b.mood === 'string' ? b.mood : '' };
    const num = h('span.ud-num'), words = h('span.ud-words');
    const input = h('input.j-date-input', { type: 'date', tabindex: -1 });
    const moodNow = h('button.ud-mood', { type: 'button', disabled: ctx.readOnly, title: 'How was it?' });
    const show = () => {
      const t = new Date(d.day);
      num.textContent = t.getDate();
      words.replaceChildren(h('b', t.toLocaleDateString([], { weekday: 'long' })), ' ', t.toLocaleDateString([], { month: 'long', year: 'numeric' }));
      input.value = new Date(d.day - t.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
      moodNow.textContent = d.mood || '☺︎';
      moodNow.classList.toggle('empty', !d.mood);
    };
    input.addEventListener('change', () => {
      if (!input.value) return;
      const [y, m, dd] = input.value.split('-').map(Number);
      d.day = new Date(y, m - 1, dd).getTime();
      show();
      ctx.changed();
    });
    const date = h('button.ud-date', { type: 'button', disabled: ctx.readOnly, title: 'Change the day',
      onClick: () => (input.showPicker ? input.showPicker() : input.click()) }, num, words);
    const moods = h('div.ud-moods');
    const paint = () => moods.querySelectorAll('button').forEach(x => x.classList.toggle('on', x.textContent === d.mood));
    for (const m of MOODS) {
      moods.appendChild(h('button', { type: 'button', disabled: ctx.readOnly, onClick: () => {
        d.mood = d.mood === m ? '' : m;
        paint();
        show();
        el.classList.remove('picking');
        ctx.changed();
      } }, m));
    }
    const el = h('div.ub-day', date, moodNow, input, moods);
    moodNow.addEventListener('click', () => el.classList.toggle('picking'));
    show();
    paint();
    return { b, node: el, value: () => ({ id: b.id, kind: 'day', day: d.day, mood: d.mood }) };
  }

  function unknownView(b) {
    return { b, node: h('p.ub-unknown', 'Something made by a newer Notes+ is here. Update to see it.'), value: () => b };
  }

  function make(b) {
    const v = (b.kind === 'text' ? textView : b.kind === 'list' ? listView : b.kind === 'mindmap' ? mapView
      : b.kind === 'audio' ? audioView : b.kind === 'day' ? dayView : unknownView)(b);
    v.indent = indentOf(b);
    const handle = ctx.readOnly ? null : h('button.ub-handle', { type: 'button', title: 'Drag to move (sideways to nest) · click for more', 'aria-label': 'Move or change' },
      h('i'), h('i'), h('i'), h('i'), h('i'), h('i'));
    v.el = h('section.ub', { 'data-kind': b.kind }, handle, h('div.ub-body', v.node));
    paintIndent(v);
    v.el.addEventListener('focusin', () => { focused = v; });
    v.el.addEventListener('pointerdown', () => { focused = v; });
    if (handle) dragByHandle(v, handle);
    return v;
  }

  function paintIndent(v) {
    v.el.style.setProperty('--indent', v.indent);
    v.el.toggleAttribute('data-nested', v.indent > 0);
  }

  // ----------------------------------------------------------- arranging

  function put(at, b) {
    const v = make(b);
    views.splice(at, 0, v);
    if (views[at + 1]) views[at + 1].el.before(v.el); else node.insertBefore(v.el, tail);
    return v;
  }

  function drop(v) {
    if (v.impl && v.impl.destroy) v.impl.destroy();
    if (v.mapRef) shared.maps.delete(v.mapRef);
    if (shared.activeText === v) shared.activeText = null;
    if (focused === v) focused = null;
    views.splice(views.indexOf(v), 1);
    v.el.remove();
  }

  /** As stored: its data, and how far in it sits. */
  const valueOf = v => {
    const b = v.value();
    if (v.indent > 0) b.indent = v.indent; else delete b.indent;
    return b;
  };

  function blank(kind, indent) {
    const b = { id: blockId(), kind };
    if (kind === 'text') b.md = '';
    if (kind === 'list') b.items = [{ text: '', done: false }];
    if (kind === 'mindmap') b.h = MAP_H;
    if (kind === 'day') { b.day = dayStart(Date.now()); b.mood = ''; }
    if (indent > 0) b.indent = indent;
    return b;
  }

  function reveal(v, isNew) {
    v.el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    v.el.classList.add('arrived');
    setTimeout(() => v.el.classList.remove('arrived'), 700);
    setTimeout(() => v.focus && v.focus(isNew), 60);
  }

  /** Two neighbours of the same kind at the same depth read as one: they become one. */
  function mergeAt(i) {
    const a = views[i - 1], c = views[i];
    if (!a || !c || a.indent !== c.indent || a.b.kind !== c.b.kind) return;
    if (a.b.kind === 'list') {
      const items = [...a.impl.items(), ...c.impl.items()].map(x => ({ text: x.text, done: !!x.done }));
      const b = { ...valueOf(a), items };
      drop(a);
      drop(c);
      put(i - 1, b);
    } else if (a.b.kind === 'text') {
      const x = a.value().md, y = c.value().md;
      const b = { ...valueOf(a), md: x && y ? x + '\n' + y : x + y };
      drop(a);
      drop(c);
      put(i - 1, b);
    }
  }

  /** Words split at the caret around a new element: before, the element, then the rest. */
  function splitInsert(v, kind, [before, after]) {
    const i = views.indexOf(v);
    const b = valueOf(v);
    drop(v);
    let at = i;
    if (before || (opts.root && i === 0)) put(at++, { ...b, md: before });
    const made = put(at++, blank(kind, v.indent));
    if (after || at >= views.length) put(at, { id: blockId(), kind: 'text', md: after, ...(v.indent ? { indent: v.indent } : {}) });
    focused = made;
    ctx.changed();
    reveal(made, true);
    return made;
  }

  /** Something new inside a list item: the list splits after it, and the new element sits under it. */
  function nestUnderItem(v, i, kind) {
    const items = v.impl.items().map(x => ({ text: x.text, done: !!x.done }));
    const at = views.indexOf(v), b = valueOf(v), indent = v.indent;
    drop(v);
    put(at, { ...b, items: items.slice(0, i + 1) });
    const made = kind === 'picture' ? put(at + 1, blank('text', indent + 1)) : put(at + 1, blank(kind, Math.min(4, indent + 1)));
    if (items.length > i + 1) put(at + 2, { id: blockId(), kind: 'list', items: items.slice(i + 1), ...(indent ? { indent } : {}) });
    focused = made;
    ctx.changed();
    if (kind === 'picture') pickFiles('image/*', true, files => { made.body.addPicture(files); ctx.changed(); });
    else reveal(made, true);
  }

  /** The bar's choice: at the caret (splitting the words), or after the element in use. */
  function insertKind(kind) {
    const v = focused && views.includes(focused) ? focused : null;
    if (v && v.b.kind === 'text' && v.body.focused()) return splitInsert(v, kind, v.body.split());
    const at = v ? views.indexOf(v) + 1 : views.length;
    const made = put(at, blank(kind, v ? v.indent : 0));
    focused = made;
    ctx.changed();
    reveal(made, true);
    return made;
  }

  function addPictures() {
    pickFiles('image/*', true, files => {
      const v = focused && focused.b.kind === 'text' && views.includes(focused) ? focused
        : put(focused ? views.indexOf(focused) + 1 : views.length, blank('text', focused ? focused.indent : 0));
      v.body.addPicture(files);
      ctx.changed();
    });
  }

  function remove(v) {
    const go = () => {
      const i = views.indexOf(v);
      drop(v);
      if (!views.some(x => x.b.kind === 'text') || (opts.root && views[0] && views[0].b.kind !== 'text')) put(0, blank('text', 0));
      mergeAt(i);
      ctx.changed();
    };
    const empty = v.b.kind !== 'day' && blocksContent([v.value()]).trim() === '';
    if (empty) go(); else confirm(`Remove this ${(KINDS[v.b.kind] || { label: 'part' }).label.toLowerCase()}?`, 'What\'s in it goes too.', 'Remove', go, true);
  }

  function setIndent(v, indent) {
    v.indent = Math.max(0, Math.min(4, indent));
    paintIndent(v);
    ctx.changed();
  }

  function moveTo(v, to, indent) {
    const from = views.indexOf(v);
    views.splice(from, 1);
    const at = to > from ? to - 1 : to;
    views.splice(at, 0, v);
    if (views[at + 1]) views[at + 1].el.before(v.el); else node.insertBefore(v.el, tail);
    if (indent !== undefined) v.indent = indent;
    paintIndent(v);
    // Where it left and where it landed, like neighbours merge (two lists meeting become one).
    mergeAt(Math.min(from, views.length - 1));
    const i = views.indexOf(v);
    if (i >= 0) { mergeAt(i + 1); if (views.includes(v)) mergeAt(views.indexOf(v)); }
    ctx.changed();
  }

  function options(v, anchor) {
    const i = views.indexOf(v);
    menu(anchor, [
      { text: 'Add below…', onClick: () => kindMenu(anchor, kind => {
        if (kind === 'picture') { focused = v; addPictures(); return; }
        const made = put(views.indexOf(v) + 1, blank(kind, v.indent));
        focused = made;
        ctx.changed();
        reveal(made, true);
      }) },
      { text: 'Nest further in  →', disabled: v.indent >= 4 || i === 0, onClick: () => setIndent(v, v.indent + 1) },
      { text: '←  Bring out', disabled: v.indent <= 0, onClick: () => setIndent(v, v.indent - 1) },
      { text: 'Move up', disabled: i <= 0, onClick: () => moveTo(v, i - 1) },
      { text: 'Move down', disabled: i >= views.length - 1, onClick: () => moveTo(v, i + 2) },
      { text: 'Remove', danger: true, onClick: () => remove(v) },
    ]);
  }

  /** The handle: a click opens its menu; a drag moves the element (and sideways, nests it). */
  function dragByHandle(v, handle) {
    handle.addEventListener('pointerdown', e => {
      if (e.button !== 0) return;
      e.preventDefault();
      handle.setPointerCapture(e.pointerId);
      const x0 = e.clientX, y0 = e.clientY, base = v.indent;
      let dragging = false, to = views.indexOf(v), indent = base;
      const line = h('div.ub-drop');
      const move = ev => {
        if (!dragging && Math.hypot(ev.clientX - x0, ev.clientY - y0) < 6) return;
        if (!dragging) { dragging = true; v.el.classList.add('dragging'); node.appendChild(line); }
        to = views.length;
        for (let k = 0; k < views.length; k++) {
          const r = views[k].el.getBoundingClientRect();
          if (ev.clientY < r.top + r.height / 2) { to = k; break; }
        }
        indent = Math.max(0, Math.min(4, base + Math.round((ev.clientX - x0) / STEP)));
        if (to === 0) indent = 0;
        const ref = views[to], top = ref ? ref.el.offsetTop - 3 : tail.offsetTop - 3;
        line.style.top = top + 'px';
        line.style.left = indent * STEP + 'px';
      };
      const up = ev => {
        handle.removeEventListener('pointermove', move);
        for (const t of ['pointerup', 'pointercancel']) handle.removeEventListener(t, up);
        line.remove();
        v.el.classList.remove('dragging');
        if (!dragging) { if (ev.type === 'pointerup') options(v, handle); return; }
        const from = views.indexOf(v);
        if (to === from || to === from + 1) { if (indent !== v.indent) setIndent(v, indent); return; }
        moveTo(v, to, indent);
      };
      handle.addEventListener('pointermove', move);
      for (const t of ['pointerup', 'pointercancel']) handle.addEventListener(t, up);
    });
  }

  // ------------------------------------------------------------------ build

  // Below the last element: a click writes on (in the last words, or new ones).
  const tail = h('div.ult-tail', { title: ctx.readOnly ? null : 'Click to write here' });
  node.appendChild(tail);
  if (!ctx.readOnly) tail.addEventListener('click', () => {
    const last = views[views.length - 1];
    if (last && last.b.kind === 'text' && last.indent === 0) { last.body.focusEnd(); return; }
    const made = put(views.length, blank('text', 0));
    made.body.focusStart();
  });
  for (const b of stored) put(views.length, b);

  return {
    node,
    value() {
      return views.map(valueOf).filter((b, i) => b.kind !== 'text' || b.md || (opts.root && i === 0));
    },
    insertKind,
    addPictures,
    firstText: () => views.find(v => v.b.kind === 'text'),
    focusFirst() { const v = views[0]; if (v && v.focus) v.focus(false); },
    destroy() { for (const v of [...views]) drop(v); },
  };
}

/**
 * What's inside an idea: a panel over the page (a side sheet, or a sheet from the bottom on a phone)
 * holding a page of its own. Its changes go into the idea at once, and the idea's badge follows.
 */
function openInside(ctx, idea, kind, refresh, shared) {
  let timer = 0;
  const sync = () => {
    clearTimeout(timer);
    const list = inner.value();
    idea.blocks = list.some(b => b.kind !== 'text' || b.md) ? list : null;
    refresh();
  };
  const innerCtx = { ...ctx, changed: () => { ctx.changed(); clearTimeout(timer); timer = setTimeout(sync, 120); } };
  const stored = parseBlocks(JSON.stringify({ blocks: idea.blocks || [] }));
  const inner = column(innerCtx, stored, shared, {});
  const close = () => {
    sync();
    inner.destroy();
    document.removeEventListener('keydown', esc, true);
    shared.panels.splice(shared.panels.indexOf(panel), 1);
    sheet.classList.add('leaving');
    scrim.classList.add('leaving');
    setTimeout(() => { sheet.remove(); scrim.remove(); }, 180);
  };
  const esc = e => {
    if (e.key !== 'Escape' || shared.panels[shared.panels.length - 1] !== panel) return;
    e.preventDefault();
    e.stopPropagation();
    close();
  };
  const head = h('div.ult-sheet-head',
    h('div', h('div.ult-sheet-eyebrow', 'Inside the idea'), h('div.ult-sheet-title', idea.text || 'Idea')),
    h('button.icon-btn', { type: 'button', title: 'Done (Esc)', onClick: close }, icon('close', 22)));
  const bar = ctx.readOnly ? null : addBar(k => inner.insertKind(k), () => inner.addPictures());
  const sheet = h('aside.ult-sheet', { role: 'dialog', 'aria-label': 'Inside ' + (idea.text || 'the idea') },
    head, h('div.ult-sheet-body', inner.node), bar);
  const scrim = h('div.ult-scrim', { onClick: close });
  const panel = { close };
  shared.panels.push(panel);
  document.body.append(scrim, sheet);
  document.addEventListener('keydown', esc, true);
  if (kind) setTimeout(() => inner.insertKind(kind), 30);
  else setTimeout(() => inner.focusFirst(), 30);
}
