// The frame every note editor shares (the apps' NoteActivity): loading the note (and
// unlocking it), saving only when something changed, pin / colour / lock / delete, a newer
// version taken in place while nothing was changed here, and notes sent to me shown read-only.

import * as db from './db.js';
import * as lock from './lock.js';
import * as sync from './sync.js';
import * as account from './account.js';
import * as links from './links.js';
import * as noteLinks from './notelinks.js';
import { h, icon, iconButton, toast, dialog, confirm, menu, pickColor, askLock, newLock, choices, prompt } from './ui.js';
import * as templates from './templates.js';
import { newNote, kind, tint, COLOR_NAMES, SKETCH, AUDIO } from './model.js';

const state = n => [n.title, n.content, n.data, n.color, n.pinned, n.sketch || ''].join('\u0000');

/**
 * Opens a note (uuid) or a new one of `type`; `implFor(type)` gives the type's editor:
 * {node, collect(note) → keep?, wrap?, setWrap?, destroy?, focus?}. Resolves to a screen, or
 * null when the note can't be opened (gone, or its lock wasn't given).
 */
export async function openEditor({ uuid, type }, implFor, go) {
  let note;
  let isNew = false;
  if (uuid) {
    note = await db.getNote(uuid);
    if (!note || note.deleted) return null;
  } else {
    note = newNote(type);
    note.account = account.uid();
    isNew = true;
  }
  const readOnly = !!note.sharedFrom;
  let key = null;
  if (!readOnly && lock.isLocked(note.data)) {
    key = await unlock(note);
    if (!key) return null;
    const plain = await lock.unseal(key, note.data);
    note.content = plain.content;
    note.data = plain.data;
    lock.opened(note.uuid, key);
  }
  sync.OPEN.add(note.uuid);

  const root = h('div.editor');
  const body = h('div.editor-body');
  let impl = null;
  let saved = state(note);
  let inDb = !isNew;
  let timer = null;
  let saving = Promise.resolve();

  // Links between notes: every note (what "[[" offers, which links lead somewhere), and the title
  // as last saved (when it changes, the links to it are renamed).
  let linkNotes = [];
  const linkListeners = new Set();
  let linkedTitle = note.title || '';
  const backBar = h('div.backlinks-wrap');
  async function loadLinks() {
    linkNotes = (await db.allNotes()).filter(n => !n.deleted);
    linkListeners.forEach(f => f());
    const from = readOnly || isNew && !inDb ? [] : noteLinks.backlinks(note, linkNotes);
    backBar.replaceChildren(...[noteLinks.backlinksRow(from, uuid => go('#/n/' + uuid))].filter(Boolean));
  }
  async function openLink(title) {
    const all = (await db.allNotes()).filter(n => !n.deleted);
    const target = noteLinks.resolve(title, all);
    if (target) { go('#/n/' + target.uuid); return; }
    if (readOnly) { toast(`No note called “${title.trim()}”`); return; }
    confirm(`No note called “${title.trim()}”`, 'Make a note with that title?', 'Create', async () => {
      const made = { ...newNote('TEXT'), title: title.trim(), account: account.uid() };
      await db.putNote(made);
      go('#/n/' + made.uuid);
    });
  }
  /** Every note of mine linking "[[from]]" now links "[[to]]" (unless another note still has the old title). */
  async function renameLinks(from, to) {
    const all = (await db.allNotes()).filter(n => !n.deleted);
    if (noteLinks.resolve(from, all)) return 0;
    let changed = 0;
    for (const n of all) {
      if (n.uuid === note.uuid || n.sharedFrom || lock.isLocked(n.data) || sync.OPEN.has(n.uuid)) continue;
      const next = noteLinks.renamed(n, from, to);
      if (!next) continue;
      await db.putNote({ ...n, content: next.content, data: next.data, dirty: true, timestamp: Date.now() }, true);
      changed++;
    }
    if (changed) db.changed('notes');
    return changed;
  }

  let syncStatus = 'Saved';
  const statusListeners = new Set();
  const setSyncStatus = s => {
    syncStatus = s;
    statusListeners.forEach(f => f(s));
  };

  const offSync = sync.onStatus(active => {
    if (active) {
      if (syncStatus === 'Saved') setSyncStatus('Syncing…');
    } else if (syncStatus === 'Syncing…' || syncStatus === 'Saving…') {
      // "Synced" only once this note really went up (signed out or offline, it waits here).
      db.getNote(note.uuid).then(n => setSyncStatus(n && n.dirty ? 'Not synced yet' : 'Synced'));
    }
  });

  const ctx = {
    get note() { return note; },
    readOnly,
    get syncStatus() { return syncStatus; },
    onSyncStatus: f => { statusListeners.add(f); return () => statusListeners.delete(f); },
    changed() {
      if (readOnly) return;
      setSyncStatus('Unsaved changes');
      clearTimeout(timer);
      timer = setTimeout(async () => {
        setSyncStatus('Saving…');
        saving = saving.then(save).then(() => {
          setSyncStatus('Saved');
          sync.schedule(300);
        });
      }, 1500);
    },
    async putFile(name, blob) { await db.putFile(note.uuid, name, blob); },
    fileUrl: name => db.fileUrl(note.uuid, name),
    save: () => (saving = saving.then(save)),
    linkNotes: () => linkNotes,
    onLinks: f => { linkListeners.add(f); return () => linkListeners.delete(f); },
    openLink: title => openLink(title),
    self: () => note.uuid,
  };

  function build() {
    if (impl && impl.destroy) impl.destroy();
    impl = implFor(note.type)(ctx);
    body.replaceChildren(impl.node);
    paint();
  }

  async function save() {
    if (!impl) return;
    if (readOnly) {
      // A note sent to me: only my own choices about it, its pin and colour, are kept.
      const st = state(note);
      if (st === saved) return;
      saved = st;
      const stored = await db.getNote(note.uuid);
      if (stored) await db.putNote({ ...stored, pinned: note.pinned, color: note.color });
      return;
    }
    const probe = { ...note };
    const keep = impl.collect(probe);
    if (!keep) {
      if (isNew && inDb) {
        await db.purgeNote(note.uuid);
        inDb = false;
      }
      return;
    }
    const st = state(probe);
    if (st === saved) return;
    saved = st;
    Object.assign(note, { title: probe.title, content: probe.content, data: probe.data, sketch: probe.sketch || null });
    const current = inDb ? await db.getNote(note.uuid) : null;
    if (current) Object.assign(note, { rev: current.rev, media: current.media, groupMembers: current.groupMembers });
    note.timestamp = Date.now();
    note.dirty = true;
    const stored = { ...note };
    if (key) {
      stored.data = await lock.seal(key, note.content, note.data);
      stored.content = '';
    }
    await db.putNote(stored);
    inDb = true;
    // A new title: the links to the old one follow it (not for locked notes, whose links can't be seen).
    const from = linkedTitle;
    linkedTitle = note.title || '';
    if (!key && noteLinks.key(from) && noteLinks.key(note.title) && noteLinks.key(from) !== noteLinks.key(note.title)) {
      const changed = await renameLinks(from, note.title);
      if (changed) toast(`Updated links in ${changed} ${changed === 1 ? 'note' : 'notes'}`);
    }
  }

  /** "Unchanged" is what this editor makes of the note once shown, not the stored text. */
  function baseline() {
    setTimeout(() => {
      if (!impl) return;
      const probe = { ...note };
      impl.collect(probe);
      saved = state(probe);
    }, 0);
  }

  async function takeNewerIfUnchanged() {
    if (!impl || readOnly) return;
    const probe = { ...note };
    impl.collect(probe);
    if (state(probe) !== saved) return; // Edited here: this edit wins on leaving.
    const fresh = await sync.takeNewer(note.uuid);
    if (!fresh) return;
    if (lock.isLocked(fresh.data)) {
      if (!key || !lock.fits(key, fresh.data)) return;
      const p = await lock.unseal(key, fresh.data);
      fresh.content = p.content;
      fresh.data = p.data;
    } else if (key) {
      key = null;
    }
    note = fresh;
    build();
    baseline();
    paintToolbar();
    toast('Updated from your other device');
  }
  sync.WATCHERS.set(note.uuid, takeNewerIfUnchanged);

  const flushAway = () => {
    if (document.visibilityState !== 'hidden' || readOnly) return;
    clearTimeout(timer);
    saving = saving.then(save).then(() => sync.schedule(0));
  };
  document.addEventListener('visibilitychange', flushAway);
  window.addEventListener('pagehide', flushAway);

  function exitToHome() {
    root.classList.add('leaving');
    setTimeout(() => go('#/'), 140);
  }

  // ------------------------------------------------------------- toolbar
  const lockBtn = iconButton('lock', 'Lock this note', () => lockClicked());
  const pinBtn = iconButton('pin', 'Pin', () => {
    note.pinned = !note.pinned;
    paintToolbar();
    ctx.changed();
  });
  const colorBtn = iconButton('palette', 'Colour', () => pickColor(note.color, i => {
    note.color = i;
    paint();
    ctx.changed();
  }, COLOR_NAMES));
  const moreBtn = iconButton('more', 'More', () => showMore());
  const from = readOnly ? h('span.editor-from', `From @${note.sharedFrom} · read only`)
    : note.template ? h('span.editor-from', 'Template · new notes start as this') : null;
  const toolbar = h('div.toolbar.editor-toolbar',
    iconButton('back', 'Back (Esc)', () => exitToHome()), from, h('div.spacer'), lockBtn, pinBtn, colorBtn, moreBtn);
  root.append(toolbar, body);

  function paintToolbar() {
    const can = !readOnly && lock.lockable(note.type);
    lockBtn.hidden = !can;
    lockBtn.replaceChildren(icon(key ? 'lockOpen' : 'lock', 24, key ? 'var(--brand)' : 'var(--secondary)'));
    lockBtn.title = key ? 'Unlocked for now: click to lock it again' : 'Lock this note';
    pinBtn.replaceChildren(icon('pin', 24, note.pinned ? 'var(--brand)' : 'var(--secondary)'));
    pinBtn.title = note.pinned ? 'Unpin' : 'Pin';
    colorBtn.hidden = false;
  }

  function paint() {
    const t = tint(note.color, null);
    root.style.setProperty('--editor-bg', t ? `var(--${t})` : 'var(--bg)');
    root.style.setProperty('--card-surface', t ? `var(--${t})` : 'var(--surface)');
  }

  function showMore() {
    const items = [];
    if (readOnly) {
      items.push({ text: 'Save a copy', onClick: () => saveCopy() });
    } else {
      if (impl.wrap !== undefined) items.push({ text: impl.wrap() ? 'Don\'t wrap text' : 'Wrap text', onClick: () => { impl.setWrap(!impl.wrap()); ctx.changed(); } });
      if (key) {
        items.push({ text: 'Change lock…', onClick: () => chooseNewLock() });
        items.push({ text: 'Decrypt…', onClick: () => confirmDecrypt() });
      }
      if (account.uid() && !key && note.type !== AUDIO) {
        items.push({ text: 'Share link…', onClick: async () => { await (saving = saving.then(save)); links.sheet(await db.getNote(note.uuid) || note); } });
      }
      if (!key && templates.canSave(note)) {
        // The note as it is on screen (saved first) becomes a template under New.
        items.push({ text: 'Save as template…', onClick: () => prompt({
          title: 'Save as template',
          message: 'A copy of this note that new notes can start from, under New. Ticks, one-off dates and pictures are left out.',
          hint: 'Template name', value: note.title || kind(note.type).label, ok: 'Save',
          onOk: async v => {
            await (saving = saving.then(save));
            const saved = await db.getNote(note.uuid) || note;
            await db.putNote(templates.copyOf(saved, v.trim() || saved.title, true, account.uid()));
            sync.schedule();
            toast('Saved as a template. Find it under New or the Templates filter.');
          },
        }) });
      }
      items.push({ text: 'Delete', danger: true, onClick: () => confirm('Delete this note?', null, 'Delete', () => remove(), true) });
    }
    menu(moreBtn, items);
  }

  async function saveCopy() {
    const copy = { ...newNote(note.type), title: note.title, content: note.content, data: note.data, sketch: note.sketch, color: note.color };
    copy.account = account.uid();
    for (const name of await db.filesOf(note.uuid)) {
      const b = await db.getFile(note.uuid, name);
      if (b) await db.putFile(copy.uuid, name, b);
    }
    await db.putNote(copy);
    toast('Saved as your own note');
    go('#/n/' + copy.uuid);
  }

  async function remove() {
    clearTimeout(timer);
    impl = null;
    if (!inDb) { go('#/'); return; }
    const n = await db.getNote(note.uuid);
    if (n && (!n.account || n.rev === 0)) {
      await db.purgeNote(n.uuid); // Never reached an account: nothing to tell it.
    } else if (n) {
      n.deleted = true;
      n.dirty = true;
      await db.putNote(n);
    }
    sync.OPEN.delete(note.uuid);
    go('#/');
  }

  // ---------------------------------------------------------------- lock
  function lockClicked() {
    if (!key) startLocking();
    else lockedOptions();
  }

  async function startLocking() {
    const open = lock.sessionKey(note.uuid);
    if (open) { applyLock(open); return; }
    const last = lock.lastType(note.uuid);
    if (!last) { chooseNewLock(); return; }
    const word = lock.typeWord(last);
    dialog({
      title: 'Lock this note', message: `Lock it again with its last ${word}?`,
      buttons: [
        { text: 'Cancel' },
        { text: 'New lock', onClick: () => chooseNewLock() },
        { text: 'Use last ' + word, onClick: () => askLock(last, 'Last ' + word, 'Enter it once.', async v => {
          try { applyLock(await lock.lastKey(note.uuid, v)); } catch { toast(`That isn't its last ${word}`); }
        }) },
      ],
    });
  }

  function chooseNewLock() {
    newLock('Lock this note', 'It opens with this lock on every device.', async (type, value) => {
      toast('Locking…');
      applyLock(await lock.newKey(type, value));
    });
  }

  async function applyLock(k) {
    key = k;
    lock.opened(note.uuid, k);
    lock.remember(note.uuid, k);
    saved = null; // Written now, sealed.
    await (saving = saving.then(save));
    paintToolbar();
    toast(`Locked. It opens only with this ${lock.typeWord(k.type)}, on every device.`, 3500);
  }

  function lockedOptions() {
    const m = lock.timeoutMinutes();
    const stays = m < 0 ? 'until this tab closes' : m === 0 ? 'only while it\'s on screen' : `for ${m} minute${m === 1 ? '' : 's'} after you leave it`;
    dialog({
      title: 'Unlocked for now',
      message: `It stays open ${stays}, then needs its ${lock.typeWord(key.type)} again. It stays encrypted all along in your account and on your other devices. To store it unencrypted, use ⋮ → Decrypt.`,
      buttons: [{ text: 'Close' }, { text: 'Lock now', onClick: () => { lock.forget(note.uuid); go('#/'); } }],
    });
  }

  function confirmDecrypt() {
    confirm('Decrypt this note?', 'Its text will be stored unencrypted: here, in your account and on your other devices. Click the lock any time to encrypt it again with the same lock.', 'Decrypt', () => {
      // It asks for the lock itself, even though the note is open: it's the lock's owner deciding.
      const word = lock.typeWord(key.type);
      askLock(key.type, 'Decrypt this note', `Enter its ${word} to decrypt it.`, async v => {
        try {
          await lock.open(await lock.seal(key, '', ''), v);
        } catch {
          toast(`That isn't this note's ${word}`);
          return;
        }
        decrypt();
      });
    });
  }

  function decrypt() {
    (async () => {
      key = null;
      saved = null;
      await (saving = saving.then(save));
      paintToolbar();
      toast('Decrypted. Click the lock to encrypt it again.');
    })();
  }

  build();
  paintToolbar();
  if (!isNew) baseline();
  setTimeout(() => impl && impl.focus && impl.focus(isNew), 30);

  root.append(backBar);
  loadLinks();

  return {
    el: root,
    noteUuid: note.uuid,
    async leave() {
      clearTimeout(timer);
      offSync();
      document.removeEventListener('visibilitychange', flushAway);
      window.removeEventListener('pagehide', flushAway);
      if (impl) await (saving = saving.then(save));
      if (impl && impl.destroy) impl.destroy();
      sync.WATCHERS.delete(note.uuid);
      sync.OPEN.delete(note.uuid);
      lock.left(note.uuid);
      sync.schedule(800);
    },
    flush() { clearTimeout(timer); return (saving = saving.then(save)); },
    onKey(e) {
      if (e.key === 'Escape') {
        e.preventDefault();
        exitToHome();
        return true;
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        ctx.save().then(() => toast('Saved'));
        return true;
      }
      return impl && impl.onKey ? impl.onKey(e) : false;
    },
  };
}

/** Asks for a locked note's lock; resolves to its key, or null when cancelled. */
function unlock(note) {
  const cached = lock.sessionKey(note.uuid);
  if (cached && lock.fits(cached, note.data)) return Promise.resolve(cached);
  const type = lock.typeOf(note.data);
  return new Promise(resolve => {
    const ask = () => askLock(type, note.title || 'Locked note', `Enter this note's ${lock.typeWord(type)}.`, async v => {
      try {
        resolve(await lock.open(note.data, v));
      } catch {
        toast(`That isn't this note's ${lock.typeWord(type)}`);
        ask();
      }
    }, () => resolve(null));
    ask();
  });
}

export { kind, SKETCH, AUDIO, choices };
