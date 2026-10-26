// The notes grid (MainActivity / MainView): filters by kind, "Shared with me", search, cards
// drawn the way each type reads best, pinned first, and the New sheet.

import * as db from './db.js';
import * as lock from './lock.js';
import * as sync from './sync.js';
import * as account from './account.js';
import * as links from './links.js';
import * as drive from './drive.js';
import { h, icon, iconButton, menu, snackbar, pickColor, dialog, prompt, choices, confirm, toast } from './ui.js';
import { KINDS, kind, tint, COLOR_NAMES, PICTURE_LINE, plain, firstPicture, todos, mindMap, journal, recordings, relative, searchable, dayStart, isVideo, TEXT, LIST, MINDMAP, JOURNAL, SKETCH, AUDIO } from './model.js';
import { drawPreview } from './mindmap-draw.js';
import { drawSketchPreview } from './ed-sketch.js';

const SHARED = 'SHARED';
// The archive (notes put away, shown nowhere else) and a folder's filter (prefix + name).
const ARCHIVED = 'ARCHIVED';
const FOLDER = 'F:';
let filter = sessionStorage.getItem('np_filter') || null;

// Folders are a name on the note, synced with it; an empty one made here is remembered in this
// browser until a note goes in or it is removed.
const remembered = () => { try { return JSON.parse(localStorage.getItem('np_folders') || '[]'); } catch { return []; } };
const storeFolders = list => { try { localStorage.setItem('np_folders', JSON.stringify(list)); } catch { /* private mode */ } };
const remember = name => { const l = remembered(); if (!l.some(x => x.toLowerCase() === name.toLowerCase())) { l.push(name); storeFolders(l); } };
const forget = name => storeFolders(remembered().filter(x => x.toLowerCase() !== name.toLowerCase()));
const cleanFolder = raw => (raw || '').replace(/\s+/g, ' ').trim().slice(0, 100).trim() || null;
function allFolders(notes) {
  const seen = new Map();
  for (const n of notes) if (n.folder) seen.set(n.folder.toLowerCase(), n.folder);
  for (const f of remembered()) if (!seen.has(f.toLowerCase())) seen.set(f.toLowerCase(), f);
  return [...seen.values()].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
}

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
      // Signed in: the account's photo in place of the icon (when it has one the web can show).
      if (account.uid()) {
        account.profile().then(p => {
          if (!p.photoUrl || !/^https:\/\//.test(p.photoUrl)) return;
          const img = h('img.account-photo', { alt: 'Account', referrerpolicy: 'no-referrer' });
          img.onload = () => accountBtn.replaceChildren(img);
          img.src = p.photoUrl;
        }).catch(() => {});
      }
      toolbar.replaceChildren(h('div.title-wrap', title, syncing), h('div.spacer'),
        iconButton('search', 'Search (Ctrl+F)', startSearch), accountBtn);
    }
  }
  function startSearch() { searching = true; showToolbar(); setTimeout(() => search.focus(), 0); }
  function stopSearch() { searching = false; search.value = ''; query = ''; showToolbar(); render(); }

  // Filter chips: All, the folders (right-click or hold: rename or remove), Shared with me,
  // Archived, the kinds, and "New folder".
  const chips = h('div.chips');
  const setFilter = type => { filter = type; sessionStorage.setItem('np_filter', type || ''); paintChips(); render(); };
  const chip = (label, type, ic, accent) => {
    const c = h('button.chip', { type: 'button', onClick: () => setFilter(type) },
      ic ? icon(ic, 18, `var(--${accent})`) : null, label);
    c._type = type;
    return c;
  };
  if (!filter) filter = null;
  let shownFolders = null;
  function buildChips() {
    const folders = allFolders(notes);
    if (shownFolders && folders.join('\n') === shownFolders.join('\n')) return;
    shownFolders = folders;
    if (filter && filter.startsWith(FOLDER) && !folders.includes(filter.slice(FOLDER.length))) filter = null;
    const folderChips = folders.map(f => {
      const c = chip(f, FOLDER + f, 'folder', 'brand');
      c.title = 'Right-click to rename or remove';
      c.addEventListener('contextmenu', e => { e.preventDefault(); manageFolder(f); });
      let hold = null;
      c.addEventListener('pointerdown', e => { if (e.pointerType !== 'mouse') hold = setTimeout(() => { hold = 'fired'; manageFolder(f); }, 520); });
      c.addEventListener('pointerup', () => { if (hold !== 'fired') clearTimeout(hold); });
      c.addEventListener('click', e => { if (hold === 'fired') { e.stopImmediatePropagation(); hold = null; } }, true);
      return c;
    });
    const add = h('button.chip.add-chip', { type: 'button', onClick: () => newFolder(name => setFilter(FOLDER + name)) }, icon('add', 18, 'var(--secondary)'), 'New folder');
    chips.replaceChildren(chip('All', null), ...folderChips, chip('Shared with me', SHARED, 'person', 'tile-teal'),
      chip('Archived', ARCHIVED, 'archive', 'secondary'), ...KINDS.map(k => chip(k.plural, k.type, k.icon, k.accent)), add);
    paintChips();
  }
  function paintChips() { chips.querySelectorAll('.chip').forEach(c => c.classList.toggle('on', (c._type || null) === (filter || null))); }

  function newFolder(then) {
    prompt({ title: 'New folder', hint: 'Folder name', ok: 'Create', onOk: raw => {
      const name = cleanFolder(raw);
      if (!name) return;
      remember(name);
      shownFolders = null;
      buildChips();
      then(name);
    } });
  }

  function manageFolder(f) {
    choices(f, [
      { text: 'Rename', onClick: () => prompt({ title: 'Rename folder', hint: 'Folder name', value: f, ok: 'Rename', onOk: async raw => {
        const name = cleanFolder(raw);
        if (!name || name === f) return;
        forget(f);
        remember(name);
        await refile(f, name);
        if (filter === FOLDER + f) filter = FOLDER + name;
        shownFolders = null;
        buildChips();
      } }) },
      { text: 'Remove folder', danger: true, onClick: () => confirm(`Remove “${f}”?`, 'Its notes aren\'t deleted: they stay, in no folder.', 'Remove', async () => {
        forget(f);
        await refile(f, null);
        shownFolders = null;
        buildChips();
      }, true) },
    ]);
  }

  /** Every note of mine in folder `from` moves to `to` (null: no folder). */
  async function refile(from, to) {
    for (const n of notes) if (n.folder === from && !n.sharedFrom) await update(n, x => { x.folder = to; });
  }

  function moveToFolder(n) {
    const folders = allFolders(notes);
    choices('Move to folder', [
      { text: 'No folder', onClick: () => update(n, x => { x.folder = null; }) },
      ...folders.map(f => ({ text: f, icon: 'folder', onClick: () => { forget(f); update(n, x => { x.folder = f; }); } })),
      { text: 'New folder…', icon: 'add', onClick: () => newFolder(name => { forget(name); update(n, x => { x.folder = name; }); }) },
    ]);
  }

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
    buildChips();
    render();
    showWaiting();
  }

  // Pictures and recordings made here reach the other devices through Drive. When some are
  // waiting and Drive isn't connected this visit, say so (a click is needed to ask Google).
  const banner = h('div.drive-banner');
  banner.hidden = true;
  async function showWaiting() {
    const waiting = account.uid() && !drive.hasToken() ? await sync.waitingFiles() : 0;
    banner.hidden = !waiting;
    if (!waiting) return;
    const what = waiting === 1 ? '1 picture or recording is' : `${waiting} pictures and recordings are`;
    banner.replaceChildren(icon('cloud', 20, 'var(--secondary)'),
      h('span', `${what} only in this browser. Connect Google Drive so they reach your other devices.`),
      h('button.btn.filled.small', { type: 'button', onClick: async () => {
        try {
          await drive.connect(account.email());
          banner.hidden = true;
          sync.schedule(0);
        } catch (e) {
          if (String(e.message) !== 'cancelled') toast(e.message);
        }
      } }, drive.connectedEmail() ? 'Reconnect' : 'Connect'));
  }

  function shown() {
    let list = notes;
    if (filter === ARCHIVED) list = list.filter(n => n.archived);
    else {
      list = list.filter(n => !n.archived); // Put away: only under Archived.
      if (filter && filter.startsWith(FOLDER)) list = list.filter(n => n.folder === filter.slice(FOLDER.length));
      else if (filter === SHARED) list = list.filter(n => n.sharedFrom || n.groupId);
      else if (filter) list = list.filter(n => n.type === filter);
    }
    if (query) list = list.filter(n => searchable(n).includes(query));
    return [...list].sort((a, b) => (b.pinned - a.pinned) || (b.timestamp - a.timestamp));
  }

  function render() {
    let list = shown();
    const tiles = [];
    // All, not searching: a folder's notes are one tile (opening the folder), before the rest.
    if (!filter && !query) {
      const byFolder = new Map(allFolders(notes).map(f => [f, []]));
      const loose = [];
      for (const n of list) (n.folder && byFolder.has(n.folder) ? byFolder.get(n.folder) : loose).push(n);
      list = loose;
      for (const [f, inside] of byFolder) tiles.push(folderTile(f, inside));
    }
    grid.replaceChildren(...tiles, ...list.map(card));
    empty.hidden = list.length + tiles.length > 0;
    empty.querySelector('p').textContent = query ? 'No notes match your search'
      : filter === SHARED ? 'Notes people share or send to you appear here'
      : filter === ARCHIVED ? 'Nothing archived. Archive a note from its options to put it away here.'
      : filter && filter.startsWith(FOLDER) ? `Nothing in “${filter.slice(FOLDER.length)}” yet. Move notes here from their options.`
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
      const isPicture = it => PICTURE_LINE.test((it.text || '').trim());
      for (const it of items.slice(0, 6)) box.appendChild(h('div.card-item' + (it.done ? '.done' : ''), isPicture(it) ? '🖼  Picture' : (it.done ? '☑  ' : '☐  ') + it.text));
      inner.appendChild(box);
      const tasks = items.filter(i => !isPicture(i));
      if (tasks.length) {
        const done = tasks.filter(i => i.done).length;
        inner.appendChild(h('div.card-meta', done === tasks.length ? 'All done' : `${done} of ${tasks.length} done`));
      } else if (!n.title && !items.length) inner.appendChild(h('h3.card-title', 'List'));
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
      h('span', n.sharedFrom ? `@${n.sharedFrom}  ·  ${when}` : n.groupId ? `Shared · ${people(n)}  ·  ${when}` : when), h('span.spacer'),
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

  /**
   * A folder on the All grid: its name and count, and its newest notes as small cards (one
   * filling it, two halves, one tall beside two, or four, the last saying how many more).
   */
  function folderTile(name, inside) {
    const shownNotes = inside.slice(0, 4);
    const cells = h('div.folder-cells.n' + shownNotes.length, ...shownNotes.map((n, i) => {
      const cell = h('div.folder-cell', card(n));
      if (i === 3 && inside.length > 4) cell.appendChild(h('div.folder-more', '+' + (inside.length - 3)));
      return cell;
    }));
    const tile = h('article.card.folder-tile', { tabindex: 0, title: 'Open the folder · right-click to rename or remove' },
      h('div.card-inner',
        h('div.folder-head', icon('folder', 22, 'var(--brand)'), h('h3.card-title', name),
          h('span.card-meta', inside.length ? `${inside.length} note${inside.length === 1 ? '' : 's'}` : 'Empty')),
        shownNotes.length ? cells : null));
    tile.addEventListener('click', () => setFilter(FOLDER + name));
    tile.addEventListener('keydown', e => { if (e.key === 'Enter') setFilter(FOLDER + name); });
    tile.addEventListener('contextmenu', e => { e.preventDefault(); manageFolder(name); });
    return tile;
  }

  /** How many are in a group note. */
  function people(n) {
    try { return Object.keys(JSON.parse(n.groupMembers || '{}')).length; } catch { return 0; }
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
      { text: 'Move to folder…', onClick: () => moveToFolder(n) },
      account.uid() && links.shareable(n) ? { text: 'Share link…', onClick: () => links.sheet(n) } : null,
      { text: n.archived ? 'Unarchive' : 'Archive', onClick: () => { update(n, x => { x.archived = !n.archived; }); toast(n.archived ? 'Back with your notes' : 'Archived. It\'s under the Archived filter.'); } },
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

  // Press on the notes, sweep right to left and keep holding for two seconds: the same
  // gesture as the apps. Nothing on screen hints at it.
  const gridWrap = h('div.grid-wrap', grid, empty);
  let sweep = null;
  gridWrap.addEventListener('pointerdown', e => { sweep = { x: e.clientX, y: e.clientY, timer: null }; });
  gridWrap.addEventListener('pointermove', e => {
    if (!sweep || sweep.timer) return;
    const dx = sweep.x - e.clientX, dy = Math.abs(sweep.y - e.clientY);
    if (dx > 50 && dx > dy * 2) {
      sweep.timer = setTimeout(() => {
        sweep = null;
        swept = Date.now(); // The release that follows isn't a click on a card.
        import('./gallery.js').then(g => import('./main.js').then(m => m.present(close => g.galleryScreen(close))));
      }, 2000);
    }
  });
  let swept = 0;
  gridWrap.addEventListener('click', e => { if (Date.now() - swept < 1500) { e.stopPropagation(); e.preventDefault(); } }, true);
  const endSweep = () => { if (sweep && sweep.timer) clearTimeout(sweep.timer); sweep = null; };
  gridWrap.addEventListener('pointerup', endSweep);
  gridWrap.addEventListener('pointercancel', endSweep);

  const offDb = db.onChange(() => load());
  const offStatus = sync.onStatus(on => { syncing.hidden = !on; });
  const offAccount = account.observe(() => showToolbar());
  showToolbar();
  load();

  return {
    el: h('div.home', toolbar, h('div.chips-scroll', chips), banner, gridWrap, fab),
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
