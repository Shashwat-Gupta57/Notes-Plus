// Sync with the account, the apps' NoteSync: users/{uid}/notes/{uuid}, the same shape and the
// same rules (the database's rules check them).
//
//  • Every write bumps `rev` by one, in a transaction that lands only on the rev it was based
//    on. If the account moved on meanwhile, an edit made here replaces it (based on the newer
//    rev): no conflict copies.
//  • A pull overwrites only notes with no unsent edits. A note open in an editor waits; if the
//    editor has changed nothing, it takes the newer version in place (takeNewer).
//  • Deletes are tombstones ({deleted: true}), then the row goes.
//  • Notes sent to me ("Shared with me") come from inbox/{uid} → sentNotes/{uuid}: read-only.
//  • Group notes (shared with people, everyone edits) come from groupIndex/{uid} →
//    groupNotes/{gid}/note, under the group's id; each edit tells the members' indexes.
//  • Pictures and recordings go to the person's Drive when connected (drive.js).

import * as links from './links.js';
import * as db from './db.js';
import * as account from './account.js';
import * as drive from './drive.js';
import { SKETCH } from './model.js';

export const OPEN = new Set();
const NEWER = new Map();
export const WATCHERS = new Map();
const statusListeners = new Set();
let running = null;
let again = false;
let timer = null;
let live = null;
let lastError = null;

export const onStatus = f => { statusListeners.add(f); return () => statusListeners.delete(f); };
const status = on => statusListeners.forEach(f => f(on));
export const lastSync = () => Number(localStorage.getItem('np_last_sync_' + account.uid()) || 0);
export const error = () => lastError;

const SKIP = 0, WAIT = 1, INSERT = 2, OVERWRITE = 3, REMOVE = 4;

function decide(exists, open, dirty, localRev, remoteRev, remoteDeleted) {
  if (!exists) return remoteDeleted ? SKIP : INSERT;
  if (open) return WAIT;
  if (dirty) return SKIP;
  if (remoteRev <= localRev) return SKIP;
  return remoteDeleted ? REMOVE : OVERWRITE;
}

/** A synced note as the apps write it. `mine`: one of my own notes, the only kind that says how it's filed. */
function remoteOf(uuid, v, mine = true) {
  if (!v || typeof v.rev !== 'number') return null;
  const media = {};
  if (v.media && typeof v.media === 'object') {
    for (const m of Object.values(v.media)) {
      if (!m || typeof m.name !== 'string') continue;
      const e = { driveId: m.driveId || '', size: Number(m.size) || 0, mime: m.mime || '' };
      for (const f of ['sha256', 'key']) if (typeof m[f] === 'string') e[f] = m[f];
      for (const f of ['w', 'h', 'durationMs']) if (typeof m[f] === 'number') e[f] = m[f];
      media[m.name] = e;
    }
  }
  return {
    uuid, rev: v.rev, updatedAt: Number(v.updatedAt) || 0, deleted: v.deleted === true,
    type: typeof v.type === 'string' ? v.type : 'TEXT', title: typeof v.title === 'string' ? v.title : '',
    content: typeof v.content === 'string' ? v.content : '', data: typeof v.data === 'string' ? v.data : null,
    sketch: typeof v.sketch === 'string' ? v.sketch : null, color: Number(v.color) || 0, pinned: v.pinned === true,
    edited: Number(v.edited) || Number(v.updatedAt) || Date.now(), media: Object.keys(media).length ? media : null,
    mine, archived: v.archived === true, folder: typeof v.folder === 'string' && v.folder ? v.folder : null,
  };
}

function copyInto(r, note) {
  Object.assign(note, {
    type: r.type, title: r.title, content: r.content, data: r.data, color: r.color, pinned: r.pinned,
    timestamp: r.edited, rev: r.rev, media: r.media, sketch: r.sketch, dirty: false, deleted: false,
  });
  if (r.mine && account.isSyncFoldersArchive()) Object.assign(note, { archived: r.archived, folder: r.folder });
  return note;
}

const key = name => name.replace(/[.#$[\]/]/g, '_');

function toRemote(note) {
  const { D } = account.firebase();
  const m = { rev: note.rev + 1, updatedAt: D.serverTimestamp(), device: account.device() };
  if (note.deleted) {
    m.deleted = true;
    return m;
  }
  Object.assign(m, {
    type: note.type, title: note.title || '', content: note.content || '', color: note.color || 0,
    pinned: !!note.pinned, edited: note.timestamp,
  });
  // How it's filed is mine alone, shared across devices when opted in.
  if (account.isSyncFoldersArchive()) {
    if (note.archived) m.archived = true;
    if (note.folder) m.folder = String(note.folder).slice(0, 100);
  }
  if (note.data !== null && note.data !== undefined) m.data = note.data;
  if (note.type === SKETCH && note.sketch) m.sketch = note.sketch;
  const media = {};
  for (const [name, e] of Object.entries(note.media || {})) {
    if (!e || !e.driveId) continue;
    const x = { name, driveId: e.driveId, size: e.size || 0 };
    if (e.mime) x.mime = e.mime;
    if (e.sha256 && e.sha256.length === 64) x.sha256 = e.sha256;
    if (e.key) x.key = e.key;
    for (const f of ['w', 'h', 'durationMs']) if (e[f] > 0) x[f] = e[f];
    media[key(name)] = x;
  }
  if (Object.keys(media).length) m.media = media;
  return m;
}

const sameContent = (n, r) => n.type === r.type && (n.title || '') === r.title && (n.content || '') === r.content
  && (n.data || null) === r.data && (n.color || 0) === r.color && !!n.pinned === r.pinned
  && (!r.mine || !account.isSyncFoldersArchive() || (!!n.archived === r.archived && (n.folder || null) === r.folder));

// ------------------------------------------------------------------ the pass

/** Queues a pass soon (several asks become one). */
export function schedule(delay = 1500) {
  if (!account.uid()) return;
  clearTimeout(timer);
  timer = setTimeout(() => run().catch(() => {}), delay);
}

/** One full pass: pull, upload new files, push, download missing files. */
export async function run() {
  if (running) { again = true; return running; }
  const uid = account.uid();
  if (!uid) return;
  running = (async () => {
    status(true);
    try {
      await account.load();
      for (const n of await db.allNotes()) {
        if (n.deleted && !OPEN.has(n.uuid) && (n.rev === 0 || n.account !== uid) && !n.sharedFrom) await db.purgeNote(n.uuid, true);
      }
      await pull(uid);
      try { await pullReceived(uid); } catch (e) { console.warn('Shared notes not fetched', e); }
      try { await pullGroups(uid); } catch (e) { console.warn('Group notes not fetched', e); }
      if (drive.hasToken()) {
        try { await uploadFiles(uid); } catch (e) { console.warn('Drive upload', e); }
      }
      // Which notes have links: they are published again as they go up.
      try { await links.refresh(uid); } catch (e) { console.warn('Links not checked', e); }
      await push(uid);
      if (drive.hasToken()) {
        try { await downloadFiles(uid); } catch (e) { console.warn('Drive download', e); }
      }
      localStorage.setItem('np_last_sync_' + uid, String(Date.now()));
      lastError = null;
    } catch (e) {
      lastError = e;
      console.warn('Sync failed', e);
    } finally {
      db.changed('notes');
      status(false);
    }
  })();
  try {
    await running;
  } finally {
    running = null;
    if (again) { again = false; schedule(500); }
  }
}

async function pull(uid) {
  const { db: fdb, D } = account.firebase();
  const since = Number(localStorage.getItem('np_last_pull_' + uid) || 0);
  const q = D.query(D.ref(fdb, `users/${uid}/notes`), D.orderByChild('updatedAt'), D.startAt(Math.max(0, since - 60000)));
  const snap = await D.get(q);
  let newest = since, waiting = Infinity;
  const all = [];
  snap.forEach(c => { all.push([c.key, c.val()]); });
  for (const [k, v] of all) {
    const r = remoteOf(k, v);
    if (!r) continue;
    newest = Math.max(newest, r.updatedAt);
    if (!(await apply(uid, r))) waiting = Math.min(waiting, r.updatedAt);
  }
  localStorage.setItem('np_last_pull_' + uid, String(waiting < Infinity ? Math.min(waiting, newest) : newest));
}

/** Applies one pulled note; false when it had to wait (open here). */
async function apply(uid, r) {
  let local = await db.getNote(r.uuid);
  if (local && local.sharedFrom) return true; // A received copy never takes my notes' place.
  if (local && local.account && local.account !== uid) {
    // Another account's copy kept here: it steps aside under a new identity.
    await db.purgeNote(local.uuid, true);
    local = null;
  }
  const action = decide(!!local, !!local && OPEN.has(local.uuid), !!local && local.dirty, local ? local.rev : 0, r.rev, r.deleted);
  switch (action) {
    case WAIT:
      if (!local.dirty && r.rev > local.rev) {
        NEWER.set(local.uuid, r);
        const w = WATCHERS.get(local.uuid);
        if (w) setTimeout(w);
      }
      return false;
    case INSERT:
      await db.putNote(copyInto(r, { uuid: r.uuid, account: uid, sharedFrom: null }), true);
      return true;
    case OVERWRITE:
      if (!local.account) local.account = uid;
      await db.putNote(copyInto(r, local), true);
      return true;
    case REMOVE:
      await db.purgeNote(local.uuid, true);
      return true;
    default:
      if (local && !local.account) {
        local.account = uid;
        await db.putNote(local, true);
      }
      return true;
  }
}

/** The newer version that arrived for an open, unchanged note: written in, or null. */
export async function takeNewer(uuid) {
  const r = NEWER.get(uuid);
  NEWER.delete(uuid);
  if (!r || r.deleted) return null;
  const local = await db.getNote(uuid);
  if (!local || local.dirty || r.rev <= local.rev) return null;
  copyInto(r, local);
  await db.putNote(local, true);
  return local;
}

async function push(uid) {
  const done = new Set();
  for (let round = 0; round < 3; round++) {
    let any = false;
    for (const n of await db.allNotes()) {
      if (!n.dirty || n.account !== uid || n.sharedFrom || OPEN.has(n.uuid) || done.has(n.uuid)) continue;
      any = true;
      done.add(n.uuid);
      await pushOne(uid, n, true);
    }
    if (!any) return;
  }
}

async function pushOne(uid, note, mayRetry) {
  if (note.groupId) { await pushGroup(uid, note, mayRetry); return; }
  if (note.deleted && note.rev === 0) {
    await links.afterPush(uid, note); // A link made before its first sync goes too.
    await db.purgeNote(note.uuid, true);
    return;
  }
  const { db: fdb, D } = account.firebase();
  const expected = note.rev;
  const value = toRemote(note);
  const result = await D.runTransaction(D.ref(fdb, `users/${uid}/notes/${note.uuid}`), current => {
    if (current !== null && (typeof current.rev !== 'number' || current.rev !== expected)) return undefined;
    return value;
  }, { applyLocally: false });
  if (result.committed) {
    await links.afterPush(uid, note); // Its link shows this version (or goes, deleted).
    if (note.deleted) {
      await db.purgeNote(note.uuid, true);
      return;
    }
    const now = await db.getNote(note.uuid);
    if (!now) return;
    now.rev = expected + 1;
    if (now.timestamp === note.timestamp && (now.content || '') === (note.content || '') && now.data === note.data) now.dirty = false;
    await db.putNote(now, true);
    return;
  }
  const r = remoteOf(note.uuid, result.snapshot.val());
  if (!r) return;
  if (note.deleted) {
    if (r.deleted) await db.purgeNote(note.uuid, true);
    else await db.putNote(copyInto(r, note), true); // Deleted here, edited elsewhere: the edit wins.
    return;
  }
  if (r.deleted || sameContent(note, r)) {
    note.rev = r.rev;
    if (!r.deleted) {
      note.media = r.media;
      note.dirty = false;
    }
    await db.putNote(note, true);
    if (r.deleted && mayRetry) await pushOne(uid, note, false);
    return;
  }
  // Changed elsewhere and edited here: this edit is the later intent; it replaces the account's.
  note.rev = r.rev;
  await db.putNote(note, true);
  if (mayRetry) await pushOne(uid, note, false);
}

// ------------------------------------------------------------ shared with me

async function pullReceived(uid) {
  const { db: fdb, D } = account.firebase();
  const inbox = await D.get(D.ref(fdb, 'inbox/' + uid));
  const listed = new Set();
  const ids = [];
  inbox.forEach(c => { ids.push(c.key); });
  for (const sid of ids) {
    let sent;
    try {
      sent = (await D.get(D.ref(fdb, 'sentNotes/' + sid))).val();
    } catch {
      continue; // Taken down, or no longer sent to me.
    }
    if (!sent || !sent.to || !sent.to[uid]) continue;
    const r = remoteOf(sid, sent.note, false);
    if (!r) continue;
    listed.add(sid);
    const local = await db.getNote(sid);
    if (local && !local.sharedFrom) continue; // My own note.
    if (r.deleted) {
      if (local) await db.purgeNote(sid, true);
      continue;
    }
    const from = sent.fromName || 'someone';
    if (!local) {
      await db.putNote(copyInto(r, { uuid: sid, account: uid, sharedFrom: from, pinned: false }), true);
    } else if (r.rev > local.rev || local.sharedFrom !== from) {
      const pinned = local.pinned, color = local.color;
      copyInto(r, local);
      Object.assign(local, { pinned, color, sharedFrom: from });
      await db.putNote(local, true);
    }
  }
  for (const n of await db.allNotes()) {
    if (n.sharedFrom && n.account === uid && !listed.has(n.uuid) && !OPEN.has(n.uuid)) await db.purgeNote(n.uuid, true);
  }
}

// ---------------------------------------------------------------- groups

/** Group notes I'm in, as the apps keep them: the note under the group's id. */
async function pullGroups(uid) {
  const { db: fdb, D } = account.firebase();
  const index = await D.get(D.ref(fdb, `groupIndex/${uid}`));
  const listed = new Set();
  const gids = [];
  index.forEach(c => { gids.push(c.key); });
  for (const gid of gids) {
    let g;
    try {
      g = (await D.get(D.ref(fdb, 'groupNotes/' + gid))).val();
    } catch {
      continue; // No longer a member: handled below, as not listed.
    }
    if (!g || !g.members || !g.members[uid]) continue;
    listed.add(gid);
    const members = JSON.stringify(g.members);
    const r = remoteOf(gid, g.note, false);
    let local = await db.getNote(gid);
    if (r && !r.deleted) {
      if (!local) {
        local = copyInto(r, { uuid: gid, account: uid, sharedFrom: null, groupId: gid, groupMembers: members });
        await db.putNote(local, true);
      } else if (!local.dirty && !OPEN.has(gid) && r.rev > local.rev) {
        copyInto(r, local);
        Object.assign(local, { groupId: gid, groupMembers: members });
        await db.putNote(local, true);
        continue;
      }
    }
    if (local && local.groupMembers !== members) {
      local.groupMembers = members;
      await db.putNote(local, true);
    }
  }
  // Taken out of a group (or it ended): the note stays, as my own again.
  for (const n of await db.allNotes()) {
    if (n.groupId && n.account === uid && !listed.has(n.groupId) && !OPEN.has(n.uuid)) {
      Object.assign(n, { groupId: null, groupMembers: null, rev: 0, dirty: true, media: null });
      await db.putNote(n, true);
    }
  }
}

/** One group note up: on the rev it was based on; an edit here wins over a newer one there. */
async function pushGroup(uid, note, mayRetry) {
  const { db: fdb, D } = account.firebase();
  const gid = note.groupId;
  if (note.deleted) {
    // Deleting a group note here means leaving it; the others keep it.
    try {
      const size = Number((await D.get(D.ref(fdb, `groupNotes/${gid}/size`))).val()) || 1;
      await D.update(D.ref(fdb), {
        [`groupNotes/${gid}/members/${uid}`]: null,
        [`groupNotes/${gid}/size`]: Math.max(0, size - 1),
        [`groupIndex/${uid}/${gid}`]: null,
      });
    } catch (e) {
      console.warn('Leaving the group', e); // Already out of it.
    }
    await db.purgeNote(note.uuid, true);
    return;
  }
  const value = toRemote(note);
  delete value.archived; // How it's filed stays mine.
  delete value.folder;
  const expected = note.rev;
  const result = await D.runTransaction(D.ref(fdb, `groupNotes/${gid}/note`), current => {
    if (current !== null && (typeof current.rev !== 'number' || current.rev !== expected)) return undefined;
    return value;
  }, { applyLocally: false });
  if (result.committed) {
    const now = await db.getNote(note.uuid);
    if (now) {
      now.rev = expected + 1;
      if (now.timestamp === note.timestamp && (now.content || '') === (note.content || '') && now.data === note.data) now.dirty = false;
      await db.putNote(now, true);
    }
    // The members' phones and computers hear of it.
    const update = {};
    let members = {};
    try { members = JSON.parse(note.groupMembers || '{}'); } catch { /* none */ }
    for (const m of Object.keys(members)) update[`groupIndex/${m}/${gid}`] = { at: D.serverTimestamp() };
    if (Object.keys(update).length) D.update(D.ref(fdb), update).catch(e => console.warn('Announce', e));
    return;
  }
  const r = remoteOf(gid, result.snapshot.val(), false);
  if (!r) return;
  note.rev = r.rev;
  await db.putNote(note, true);
  if (mayRetry) await pushGroup(uid, note, false);
}

// ---------------------------------------------------------------- live

/** Live while the app is open: changes from the phone and PC appear as they happen. */
export async function startLive() {
  const uid = account.uid();
  if (!uid || live) return;
  const { db: fdb, D } = await account.load();
  const since = Number(localStorage.getItem('np_last_pull_' + uid) || 0);
  const q = D.query(D.ref(fdb, `users/${uid}/notes`), D.orderByChild('updatedAt'), D.startAt(since));
  const arrived = async snap => {
    if (running) { again = true; return; }
    const r = remoteOf(snap.key, snap.val());
    if (r && (await apply(uid, r))) db.changed('notes');
  };
  const offs = [D.onChildAdded(q, arrived), D.onChildChanged(q, arrived)];
  let first = true;
  offs.push(D.onValue(D.ref(fdb, 'inbox/' + uid), () => { if (first) { first = false; return; } schedule(); }));
  live = () => offs.forEach(off => off());
  schedule(300);
}

export function stopLive() {
  if (live) live();
  live = null;
}

// ---------------------------------------------------------------- files

/** A file counts while the note names it (data, or a picture line in the text). */
const uses = (n, name) => (n.data || '').includes(name) || (n.content || '').includes(name);

async function sha256(blob) {
  const d = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
  return Array.from(new Uint8Array(d), x => x.toString(16).padStart(2, '0')).join('');
}

/** How many of my notes' files are only in this browser (waiting for Drive). */
export async function waitingFiles() {
  const uid = account.uid();
  if (!uid) return 0;
  let count = 0;
  for (const n of await db.allNotes()) {
    if (n.deleted || n.account !== uid || n.sharedFrom || n.groupId) continue;
    for (const name of await db.filesOf(n.uuid)) if (!(n.media || {})[name] && uses(n, name)) count++;
  }
  return count;
}

async function uploadFiles(uid) {
  for (const n of await db.allNotes()) {
    // A group note's files travel encrypted, by link: the apps share them.
    if (n.deleted || n.account !== uid || n.sharedFrom || n.groupId || OPEN.has(n.uuid)) continue;
    const media = { ...(n.media || {}) };
    let changed = false;
    for (const name of await db.filesOf(n.uuid)) {
      if (media[name] || !uses(n, name)) continue;
      const blob = await db.getFile(n.uuid, name);
      if (!blob) continue;
      const id = await drive.upload(blob, name, blob.type, await drive.noteFolder(n.uuid));
      media[name] = { driveId: id, size: blob.size, mime: blob.type || '', sha256: await sha256(blob) };
      changed = true;
    }
    for (const name of Object.keys(media)) {
      if (uses(n, name)) continue;
      if (media[name].driveId && !media[name].key) {
        try { await drive.remove(media[name].driveId); } catch (e) { console.warn(e); }
      }
      delete media[name];
      changed = true;
    }
    if (changed) {
      const now = await db.getNote(n.uuid);
      if (!now) continue;
      now.media = Object.keys(media).length ? media : null;
      now.dirty = true;
      await db.putNote(now, true);
    }
  }
}

async function downloadFiles(uid) {
  for (const n of await db.allNotes()) {
    if (n.deleted || n.account !== uid || !n.media || OPEN.has(n.uuid)) continue;
    let fetched = false;
    for (const [name, e] of Object.entries(n.media)) {
      if (!e.driveId || e.key || !uses(n, name)) continue; // Shared (encrypted) files open in the apps.
      if (await db.getFile(n.uuid, name)) continue;
      try {
        await db.putFile(n.uuid, name, await drive.download(e.driveId));
        fetched = true;
      } catch (err) {
        if (String(err.message) === 'drive-token') throw err;
        console.warn('Couldn\'t download', name, err);
      }
    }
    if (fetched) db.changed('notes');
  }
}

// ------------------------------------------------------------- accounts

/** First sign-in here: the notes made before it join the account (or stay only here). */
export async function adoptLocal(uid) {
  for (const n of await db.allNotes()) {
    if (n.account || n.sharedFrom) continue;
    n.account = uid;
    n.dirty = true;
    await db.putNote(n, true);
  }
  db.changed('notes');
  schedule(200);
}

export async function localCount() {
  return (await db.allNotes()).filter(n => !n.account && !n.sharedFrom && !n.deleted).length;
}

/** Signing out: the account's notes stay here as this browser's own, or go. */
export async function leave(uid, keep) {
  stopLive();
  for (const n of await db.allNotes()) {
    if (n.account !== uid) continue;
    if (n.sharedFrom || !keep || n.deleted) {
      await db.purgeNote(n.uuid, true);
      continue;
    }
    Object.assign(n, { account: null, rev: 0, dirty: true, media: null });
    await db.putNote(n, true);
  }
  localStorage.removeItem('np_last_pull_' + uid);
  db.changed('notes');
}
