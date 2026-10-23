// The notes grid (MainActivity / MainView): filters by kind, "Shared with me", search, cards
// drawn the way each type reads best, pinned first, and the New sheet.

import * as db from './db.js';
import * as lock from './lock.js';
import * as sync from './sync.js';
import * as account from './account.js';
import { h, icon, iconButton, menu, snackbar, pickColor, dialog } from './ui.js';
import { KINDS, kind, tint, COLOR_NAMES, plain, firstPicture, todos, mindMap, journal, recordings, relative, searchable, dayStart, isVideo, TEXT, LIST, MINDMAP, JOURNAL, SKETCH, AUDIO } from './model.js';
import { drawPreview } from './mindmap-draw.js';
import { drawSketchPreview } from './ed-sketch.js';

const SHARED = 'SHARED';
let filter = sessionStorage.getItem('np_filter') || null;

export function homeScreen(go) {
  let query = '';
  let searching = false;
  const grid = h('div.grid');
  const empty = h('div.empty', icon('noteText', 72, 'var(--secondary)'), h('p'));
  const title = h('h1.toolbar-title', 'Notes');
  const syncing = h('span.syncing', 'Syncing…');
  syncing.hidden = true;
  const search = h('input.search-field', { type: 'search', placeholder: 'Search notes', 'aria-label': 'Search notes' });
  search.addEventListener('input', () => { query = search.value.trim().toLowerCase(); render(); });
  const toolbar = h('div.toolbar');
  const accountBtn = iconButton('person', 'Account', () => go('#/account'));

  function showToolbar() {
    if (searching) {
      toolbar.replaceChildren(iconButton('back', 'Close search', stopSearch), search,
        iconButton('close', 'Clear', () => { search.value = ''; query = ''; render(); search.focus(); }));
    } else {
      accountBtn.replaceChildren(icon('person', 24, account.signedIn() ? 'var(--brand)' : 'currentColor'));
      toolbar.replaceChildren(h('div.title-wrap', title, syncing), h('div.spacer'),
        iconButton('search', 'Search (Ctrl+F)', startSearch), accountBtn);
    }
  }
  function startSearch() { searching = true; showToolbar(); setTimeout(() => search.focus(), 0); }
  function stopSearch() { searching = false; search.value = ''; query = ''; showToolbar(); render(); }

  // Filter chips.
  const chips = h('div.chips');
  const chip = (label, type, ic, accent) => {
    const c = h('button.chip', { type: 'button', onClick: () => { filter = type; sessionStorage.setItem('np_filter', type || ''); paintChips(); render(); } },
      ic ? icon(ic, 18, `var(--${accent})`) : null, label);
    c._type = type;
    return c;
  };
  chips.append(chip('All', null), chip('Shared with me', SHARED, 'person', 'tile-teal'), ...KINDS.map(k => chip(k.plural, k.type, k.icon, k.accent)));
  if (!filter) filter = null;
  function paintChips() { chips.querySelectorAll('.chip').forEach(c => c.classList.toggle('on', (c._type || null) === (filter || null))); }
  paintChips();

  const fab = h('button.fab.new-fab', { type: 'button', title: 'New (Ctrl+N)', onClick: () => newSheet() }, icon('add', 24, 'var(--on-brand)'), h('span', 'New'));

  function newSheet() {
    const list = h('div.kind-grid');
    let d;
    for (const k of KINDS) {
      list.appendChild(h('button.kind', { type: 'button', onClick: () => { d.close(); go('#/new/' + k.type); } },
        h('span.kind-icon', { style: { background: `var(--${k.accent})` } }, icon(k.icon, 24, '#fff')),
        h('span.kind-texts', h('b', k.label), h('small', k.description))));
    }
    d = dialog({ title: 'New', content: list, width: 520 });
  }

  // ------------------------------------------------------------------ cards
  let notes = [];
  async function load() {
    notes = (await db.allNotes()).filter(n => !n.deleted);
    render();
  }

  function shown() {
    let list = notes;
    if (filter === SHARED) list = list.filter(n => n.sharedFrom);
    else if (filter) list = list.filter(n => n.type === filter);
    if (query) list = list.filter(n => searchable(n).includes(query));
    return [...list].sort((a, b) => (b.pinned - a.pinned) || (b.timestamp - a.timestamp));
  }

  function render() {
    const list = shown();
    grid.replaceChildren(...list.map(card));
    empty.hidden = list.length > 0;
    empty.querySelector('p').textContent = query ? 'No notes match your search'
      : filter === SHARED ? 'Notes people send you appear here'
      : filter ? `No ${kind(filter).plural.toLowerCase()} yet` : 'No notes yet. Tap New to write one.';
  }

  function card(n) {
    const k = kind(n.type);
    const t = tint(n.color, null);
    const inner = h('div.card-inner');
    const c = h('article.card', { tabindex: 0 }, inner);
    if (t) {
      c.style.background = `var(--${t})`;
      c.style.setProperty('--card-surface', `var(--${t})`);
    }
    const titleEl = n.title ? h('h3.card-title', n.title) : null;
    const addTitle = fallback => { inner.appendChild(titleEl || (fallback ? h('h3.card-title', fallback) : null) || h('span')); };

    if (lock.isLocked(n.data)) {
      addTitle('Locked note');
      inner.appendChild(h('p.card-body.locked', icon('lock', 16, 'var(--secondary)'), ' Locked'));
    } else if (n.type === LIST) {
      addTitle(null);
      const items = todos(n.data);
      const box = h('div.card-list');
      for (const it of items.slice(0, 6)) box.appendChild(h('div.card-item' + (it.done ? '.done' : ''), (it.done ? '☑  ' : '☐  ') + it.text));
      inner.appendChild(box);
      if (items.length) {
        const done = items.filter(i => i.done).length;
        inner.appendChild(h('div.card-meta', done === items.length ? 'All done' : `${done} of ${items.length} done`));
      } else if (!n.title) inner.appendChild(h('h3.card-title', 'List'));
    } else if (n.type === MINDMAP) {
      const map = mindMap(n.data);
      const root = map.nodes.find(x => x.parent < 0);
      inner.appendChild(n.title ? titleEl : h('h3.card-title', (root && root.text) || 'Mind map'));
      const cv = h('canvas.card-map');
      inner.appendChild(cv);
      requestAnimationFrame(() => drawPreview(cv, map, new Map()));
      inner.appendChild(h('div.card-meta', map.nodes.length + (map.nodes.length === 1 ? ' idea' : ' ideas')));
    } else if (n.type === JOURNAL) {
      const j = journal(n.data, dayStart(n.timestamp));
      const d = new Date(j.day);
      inner.appendChild(h('div.card-journal', h('b', d.getDate()), h('div', h('div', d.toLocaleDateString([], { weekday: 'long' })), h('small', d.toLocaleDateString([], { month: 'long', year: 'numeric' }))), j.mood ? h('span.card-mood', j.mood) : null));
      addTitle(null);
      const cover = j.media.find(m => !isVideo(m)) || firstPicture(n.content);
      if (cover) inner.appendChild(picture(n.uuid, cover));
      const body = plain(n.content);
      if (body) inner.appendChild(h('p.card-body', body));
    } else if (n.type === SKETCH) {
      addTitle(null);
      const cv = h('canvas.card-sketch');
      inner.appendChild(cv);
      requestAnimationFrame(() => { if (!drawSketchPreview(cv, n.sketch)) { cv.remove(); if (!n.title) inner.prepend(h('h3.card-title', 'Sketch')); } });
    } else if (n.type === AUDIO) {
      const recs = recordings(n.data);
      inner.appendChild(n.title ? titleEl : h('h3.card-title', recs.length ? recs[0].label || 'Audio note' : 'Audio note'));
      if (recs.length) {
        const wave = h('div.card-wave', ...recs[0].wave.slice(0, 48).map(a => h('i', { style: { height: Math.max(8, a * 100) + '%' } })));
        inner.appendChild(wave);
        inner.appendChild(h('div.card-meta', recs.length + (recs.length === 1 ? ' recording' : ' recordings')));
      }
    } else {
      const body = plain(n.content);
      const pic = firstPicture(n.content);
      addTitle(!body && !pic ? 'Empty note' : null);
      if (pic) inner.appendChild(picture(n.uuid, pic));
      if (body) inner.appendChild(h('p.card-body', body));
    }

    const when = relative(n.timestamp);
    inner.appendChild(h('div.card-foot', icon(k.icon, 16, `var(--${k.accent})`),
      h('span', n.sharedFrom ? `@${n.sharedFrom}  ·  ${when}` : when), h('span.spacer'),
      n.pinned ? icon('pin', 16, 'var(--brand)') : null));
    c.addEventListener('click', () => go('#/n/' + n.uuid));
    c.addEventListener('keydown', e => { if (e.key === 'Enter') go('#/n/' + n.uuid); });
    c.addEventListener('contextmenu', e => { e.preventDefault(); options(n, c); });
    let hold = null;
    c.addEventListener('pointerdown', e => { if (e.pointerType !== 'mouse') hold = setTimeout(() => { hold = 'fired'; options(n, c); }, 520); });
    c.addEventListener('pointerup', () => { if (hold !== 'fired') clearTimeout(hold); });
    c.addEventListener('pointermove', () => { if (hold && hold !== 'fired') { clearTimeout(hold); hold = null; } });
    c.addEventListener('click', e => { if (hold === 'fired') { e.stopImmediatePropagation(); hold = null; } }, true);
    return c;
  }

  function picture(uuid, name) {
    const img = h('img.card-picture', { alt: '', loading: 'lazy' });
    db.fileUrl(uuid, name).then(u => { if (u) img.src = u; else img.remove(); });
    return img;
  }

  function options(n, anchor) {
    menu(anchor, [
      { text: n.pinned ? 'Unpin' : 'Pin', onClick: () => update(n, x => { x.pinned = !x.pinned; }) },
      { text: 'Colour', onClick: () => pickColor(n.color, i => update(n, x => { x.color = i; }), COLOR_NAMES) },
      n.sharedFrom ? null : { text: 'Delete', danger: true, onClick: () => deleteWithUndo(n) },
    ]);
  }

  async function update(n, change) {
    const x = await db.getNote(n.uuid);
    if (!x) return;
    change(x);
    if (!x.sharedFrom) x.dirty = true;
    await db.putNote(x);
    sync.schedule();
  }

  async function deleteWithUndo(n) {
    sync.OPEN.add(n.uuid); // Held back from sync while Undo is on screen.
    await update(n, x => { x.deleted = true; });
    snackbar('Note deleted', 'Undo', () => update(n, x => { x.deleted = false; }), async () => {
      sync.OPEN.delete(n.uuid);
      const x = await db.getNote(n.uuid);
      if (x && x.deleted && (!x.account || x.rev === 0 || x.sharedFrom)) await db.purgeNote(x.uuid);
      sync.schedule();
    });
  }

  const offDb = db.onChange(() => load());
  const offStatus = sync.onStatus(on => { syncing.hidden = !on; });
  const offAccount = account.observe(() => showToolbar());
  showToolbar();
  load();

  return {
    el: h('div.home', toolbar, h('div.chips-scroll', chips), h('div.grid-wrap', grid, empty), fab),
    leave() { offDb(); offStatus(); offAccount(); },
    onKey(e) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'n') { e.preventDefault(); newSheet(); return true; }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') { e.preventDefault(); startSearch(); return true; }
      if (e.key === 'Escape' && searching) { stopSearch(); return true; }
      return false;
    },
  };
}

export { TEXT };
