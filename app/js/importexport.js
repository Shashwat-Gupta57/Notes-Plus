// Import and export on the web: files, a folder or a zip in; a zip of Markdown out (a download).
// The work is in interchange.js; zips through zip.js. Notes only: nothing here touches the gallery.

import * as db from './db.js';
import * as io from './interchange.js';
import { readZip, writeZip } from './zip.js';
import { newNote, mediaName } from './model.js';
import * as lock from './lock.js';
import * as account from './account.js';
import * as sync from './sync.js';
import { h, dialog, toast } from './ui.js';

const MAX_FILE = 200 * 1024 * 1024;
const breathe = () => new Promise(r => setTimeout(r));

export function chooseImport() {
  const list = h('ul.import-sources',
    h('li', h('b', 'Google Keep: '), 'the zip from Google Takeout'),
    h('li', h('b', 'Apple Notes: '), 'the exported notes (.html or .txt), or their folder'),
    h('li', h('b', 'Markdown: '), '.md files, or a whole folder (Obsidian, Bear, Joplin…)'),
    h('li', h('b', 'A Notes+ export')));
  dialog({
    title: 'Import notes', width: 500,
    content: h('div', h('p', 'Choose what you exported from another app:'), list, h('p.secondary', 'You\'ll see what was found before anything is added.')),
    buttons: [{ text: 'Cancel' }, { text: 'Choose a folder', onClick: () => pick(true) }, { text: 'Choose files', onClick: () => pick(false) }],
  });
}

function pick(folder) {
  const input = h('input', { type: 'file', multiple: true });
  if (folder) input.webkitdirectory = true;
  else input.accept = '.zip,.md,.markdown,.txt,.html,.htm,.json';
  input.addEventListener('change', () => { if (input.files.length) readFiles([...input.files]); });
  input.click();
}

/** Reads what was picked, says what was found, and adds it on Import. Exported for tests. */
export async function readFiles(files) {
  toast('Reading…');
  const entries = [];
  let unreadable = 0;
  for (const f of files) {
    if (f.size > MAX_FILE) { unreadable++; continue; }
    try {
      if (/\.zip$/i.test(f.name)) entries.push(...await readZip(await f.arrayBuffer()));
      else entries.push({ path: f.webkitRelativePath || f.name, bytes: new Uint8Array(await f.arrayBuffer()) });
    } catch (e) {
      unreadable++;
      console.warn('Import: could not read', f.name, e);
    }
  }
  const found = io.read(entries, mediaName, Date.now());
  const here = new Set((await db.allNotes()).filter(n => !n.deleted).map(n => io.sameKey(n.title, n.content)));
  const fresh = found.notes.filter(n => {
    const k = io.sameKey(n.title, n.content);
    if (here.has(k)) return false;
    here.add(k);
    return true;
  });
  if (!fresh.length) {
    dialog({
      title: found.notes.length ? 'Already here' : 'No notes found',
      message: found.notes.length ? 'Every note in what you chose is already in Notes+.'
        : 'Notes+ reads Google Keep (Takeout), Apple Notes (.html, .txt) and Markdown (.md) files, folders of them, or a zip.',
      buttons: [{ text: 'OK' }],
    });
    return { found, fresh };
  }
  const skipped = found.notes.length - fresh.length;
  dialog({
    title: `Import ${fresh.length} ${fresh.length === 1 ? 'note' : 'notes'}?`, width: 500,
    message: found.summary()
      + (skipped ? `\n\n${skipped} ${skipped === 1 ? 'is' : 'are'} already here and will be skipped.` : '')
      + (unreadable ? `\n\n${unreadable} ${unreadable === 1 ? "file couldn't" : "files couldn't"} be read.` : ''),
    buttons: [{ text: 'Cancel' }, { text: 'Import', onClick: () => add(fresh) }],
  });
  return { found, fresh };
}

/** Adds the notes with their pictures, a few at a time so the page never freezes, then syncs. */
export async function add(notes) {
  const uid = account.uid();
  let done = 0;
  for (const n of notes) {
    const note = {
      ...newNote(n.type), title: n.title, content: n.content, data: n.data, color: n.color, pinned: n.pinned,
      archived: n.archived, folder: n.folder || null, timestamp: n.edited || Date.now(), account: uid,
    };
    if (n.template) note.template = true;
    for (const [name, bytes] of n.files) await db.putFile(note.uuid, name, new Blob([bytes]));
    await db.putNote(note, true);
    if (++done % 25 === 0) { toast(`Importing… ${done} of ${notes.length}`); await breathe(); }
  }
  db.changed('notes');
  sync.schedule();
  toast(`Imported ${done} ${done === 1 ? 'note' : 'notes'}`);
  return done;
}

/** Every note as a zip of Markdown, downloaded. */
export async function exportAll() {
  toast('Exporting…');
  const day = new Date(), stamp = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
  const notes = (await db.allNotes()).filter(n => !n.deleted).map(n => ({
    ...io.emptyNote(), type: n.type, title: n.title || '', content: n.content || '', data: n.data, color: n.color || 0,
    pinned: !!n.pinned, archived: !!n.archived, template: !!n.template, folder: n.folder || null, edited: n.timestamp || 0,
    locked: lock.isLocked(n.data), key: n.uuid,
  }));
  const out = await io.write(notes, async (n, name) => {
    const b = await db.getFile(n.key, name);
    return b ? new Uint8Array(await b.arrayBuffer()) : null;
  }, 'Notes+ ' + stamp);
  const blob = await writeZip(out);
  const a = h('a', { href: URL.createObjectURL(blob), download: `Notes+ ${stamp}.zip` });
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 4000);
  const locked = notes.filter(n => n.locked).length, exported = notes.length - locked;
  toast(`Exported ${exported} ${exported === 1 ? 'note' : 'notes'}` + (locked ? `. ${locked} locked ${locked === 1 ? 'note was' : 'notes were'} left out.` : ''), 5000);
  return blob;
}
