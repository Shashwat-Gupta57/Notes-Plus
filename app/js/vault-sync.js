// The vault's Google Drive sync, in the browser: the desktop's CloudBackup, step for step, against
// the same store (Notes-plus/Saved-Notes/Cloud/Remote/Backup) the phone and the computer use:
//   keyring.json (plain), objects_index.json, objects/<fileName>, snapshots/index.json,
//   snapshots/<id>.json (encrypted under the data key).
// Items are stored here in that same format, so they go up and come down as they are.
//
// Unlike the apps, the web never deletes anything on Drive (objects, old restore points): a tab
// closed half-way must never remove something a restore point needs. The phone and the computer
// tidy up. The vault's Google account is its own, never the notes' Drive connection.

import * as store from './vault-store.js';
import * as vc from './vault-crypto.js';
import { normalise, settle, safe, FORMAT_VERSION } from './vault-rules.js';

const CLIENT_ID = '746909983498-4n7m7cne02j9shb3acm6gial0ergi5s9.apps.googleusercontent.com';
const SCOPE = 'https://www.googleapis.com/auth/drive.file';
const API = 'https://www.googleapis.com/drive/v3/files';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3/files';
const ROOT_PATH = ['Notes-plus', 'Saved-Notes', 'Cloud', 'Remote', 'Backup'];
const BINARY = 'application/octet-stream';
const DEVICE = 'Browser (Notes+ web)';
const CHECKPOINT_FILES = 20;

export class WrongPassword extends Error {}

// ------------------------------------------------------------------ Google sign-in

let token = null, tokenUntil = 0;
export const hasToken = () => !!token && Date.now() < tokenUntil - 60000;

function gis() {
  if (window.google && window.google.accounts && window.google.accounts.oauth2) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = 'https://accounts.google.com/gsi/client';
    s.onload = resolve;
    s.onerror = () => reject(new Error('Couldn\'t reach Google'));
    document.head.appendChild(s);
  });
}

/** A Drive token for the vault's account (`hint`: its email, so it goes straight there). */
export async function signIn(hint) {
  await gis();
  const t = await new Promise((resolve, reject) => {
    const client = window.google.accounts.oauth2.initTokenClient({
      client_id: CLIENT_ID, scope: SCOPE, hint: hint || undefined, prompt: hint ? '' : 'select_account',
      callback: r => (r && r.access_token ? resolve(r) : reject(new Error('cancelled'))),
      error_callback: () => reject(new Error('cancelled')),
    });
    client.requestAccessToken();
  });
  token = t.access_token;
  tokenUntil = Date.now() + (Number(t.expires_in) || 3600) * 1000;
  const about = await (await call('https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)')).json();
  return (about.user && about.user.emailAddress) || '';
}

export function signOut() {
  if (token && window.google && window.google.accounts) window.google.accounts.oauth2.revoke(token, () => {});
  token = null;
  tokenUntil = 0;
}

// ------------------------------------------------------------------ Drive

async function call(url, opts = {}) {
  if (!token) throw new Error('Sign in to Google first');
  const r = await fetch(url, { ...opts, headers: { Authorization: 'Bearer ' + token, ...(opts.headers || {}) } });
  if (r.status === 401) {
    token = null;
    throw new Error('Google sign-in expired. Sync again to sign in.');
  }
  if (!r.ok) throw new Error('Drive: HTTP ' + r.status);
  return r;
}

const q = s => s.replace(/\\/g, '\\\\').replace(/'/g, "\\'");

async function newest(name, parent, folder) {
  const query = `name = '${q(name)}' and '${q(parent)}' in parents and trashed = false`
    + (folder ? " and mimeType = 'application/vnd.google-apps.folder'" : '');
  const r = await call(`${API}?spaces=drive&orderBy=createdTime%20desc&fields=files(id)&q=${encodeURIComponent(query)}`);
  const files = (await r.json()).files || [];
  return files.length ? files[0].id : null;
}

async function folder(name, parent) {
  const id = await newest(name, parent, true);
  if (id) return id;
  const r = await call(`${API}?fields=id`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, mimeType: 'application/vnd.google-apps.folder', parents: [parent] }),
  });
  return (await r.json()).id;
}

async function children(parent) {
  const out = [];
  let page = '';
  do {
    const query = `'${q(parent)}' in parents and trashed = false`;
    const r = await (await call(`${API}?spaces=drive&pageSize=1000&fields=nextPageToken,files(id,name,size)&q=${encodeURIComponent(query)}`
      + (page ? '&pageToken=' + encodeURIComponent(page) : ''))).json();
    out.push(...(r.files || []));
    page = r.nextPageToken || '';
  } while (page);
  return out;
}

const download = async id => (await call(`${API}/${encodeURIComponent(id)}?alt=media`)).blob();

/** Uploads `blob` as a new file, or over `existing`: Drive's resumable upload (no 5 MB limit). */
async function upload(blob, name, parent, mime, existing) {
  const start = await call(existing ? `${UPLOAD}/${encodeURIComponent(existing)}?uploadType=resumable&fields=id`
    : `${UPLOAD}?uploadType=resumable&fields=id`, {
    method: existing ? 'PATCH' : 'POST',
    headers: { 'Content-Type': 'application/json; charset=UTF-8', 'X-Upload-Content-Type': mime },
    body: JSON.stringify(existing ? {} : { name, parents: [parent] }),
  });
  const session = start.headers.get('Location');
  if (!session) throw new Error('Drive didn\'t start the upload');
  const r = await fetch(session, { method: 'PUT', headers: { 'Content-Type': mime }, body: blob });
  if (!r.ok) throw new Error('Drive: HTTP ' + r.status);
  return (await r.json()).id;
}

const putOrReplace = async (blob, name, parent, mime) => upload(blob, name, parent, mime, await newest(name, parent, false));

// ------------------------------------------------------------------ the store

/** Its folders, made when `create`; null when there is no backup and not `create`. */
async function openStore(create) {
  let parent = 'root';
  for (const name of ROOT_PATH) {
    parent = create ? await folder(name, parent) : await newest(name, parent, true);
    if (!parent) return null;
  }
  return { root: parent, objects: await folder('objects', parent), snapshots: await folder('snapshots', parent) };
}

async function readPlain(name, parent) {
  const id = await newest(name, parent, false);
  return id ? JSON.parse(await (await download(id)).text()) : null;
}

async function readDoc(session, name, parent) {
  const id = await newest(name, parent, false);
  return id ? vc.decryptJson(await download(id), session.key, session.keyId) : null;
}

const writeDoc = async (session, name, parent, value) =>
  putOrReplace(await vc.encryptJson(value, session.key, session.keyId), name, parent, BINARY);

/** Is there a vault backup on this account? {keyring} when there is. */
export async function lookForBackup() {
  const st = await openStore(false);
  const keyring = st ? await readPlain('keyring.json', st.root) : null;
  return keyring && keyring.keyId ? { keyring } : null;
}

/** What opens a backup, for the person: its backup password, or which vault locks. */
export function describe(keyring) {
  const wraps = (keyring && keyring.wraps) || [];
  if (wraps.some(w => String(w.label || '').startsWith('Backup'))) return 'Enter the backup password set on your phone or computer.';
  const named = wraps.map(w => `${w.label || 'Vault'} (${w.lockType === 'PIN' ? 'PIN' : w.lockType === 'PATTERN' ? 'pattern' : 'password'})`);
  return named.length ? 'Enter the lock of one of these vaults, as set on your phone or computer: ' + named.join(', ') + '.' : 'Enter a vault lock from your phone or computer.';
}

/** The backup's data key with `value` (backup password or vault lock), or null. */
export async function openKeyring(keyring, value) {
  const key = await vc.backupUnwrap(keyring, value);
  if (!key) return null;
  const passwordWrap = (keyring.wraps || []).find(w => String(w.label || '').startsWith('Backup')) || null;
  return { key, keyId: keyring.keyId, passwordWrap };
}

async function keyring(session) {
  const passwordWrap = await store.kv('passwordWrap');
  const wraps = [];
  if (passwordWrap) {
    wraps.push(passwordWrap);
  } else {
    const locks = await store.locks(session);
    for (const v of await store.vaults()) {
      const l = locks[v.uuid];
      if (l) wraps.push(await vc.backupWrap(session.key, l.value, v.name, l.type));
    }
  }
  return { formatVersion: 2, keyId: session.keyId, wraps, device: DEVICE, updatedAt: Date.now() };
}

/**
 * Makes the store's key this browser's: a new store gets ours (and every run republishes it, so a
 * changed lock reaches Drive); one the phone started keeps its own, taken here once with
 * `password` (or the main vault's lock), re-encrypting what this browser already has.
 */
async function settleKey(session, st, password, progress) {
  const theirs = await readPlain('keyring.json', st.root);
  if (!theirs || !theirs.keyId || theirs.keyId === session.keyId) {
    // Never an empty keyring: just after joining, the vaults (and their locks) aren't here yet.
    const ours = await keyring(session);
    if (ours.wraps.length) await putOrReplace(new Blob([JSON.stringify(ours)]), 'keyring.json', st.root, 'application/json');
    return;
  }
  let tried = password;
  if (tried == null) {
    const main = await store.mainVault();
    const l = main && (await store.locks(session))[main.uuid];
    tried = l ? l.value : null;
  }
  const opened = tried == null ? null : await openKeyring(theirs, tried);
  if (!opened) throw new WrongPassword(describe(theirs));
  await adoptKey(session, opened, progress);
  await putOrReplace(new Blob([JSON.stringify(await keyring(session))]), 'keyring.json', st.root, 'application/json');
}

/** Everything here moves to `next` ({key, keyId, passwordWrap}): items, thumbnails, locks, wraps. */
export async function adoptKey(session, next, progress = () => {}) {
  const locks = await store.locks(session);
  const email = await store.driveEmail(session);
  const items = await store.secrets();
  let done = 0;
  for (const s of items) {
    progress('Re-encrypting for your backup', done++, items.length);
    for (const [get, put] of [[store.media, store.putMedia], [store.thumb, store.putThumb]]) {
      const blob = await get(s.fileName);
      if (!blob || (await vc.keyIdOf(blob)) === next.keyId) continue;
      await put(s.fileName, await vc.encrypt(await vc.decrypt(blob, session.key, session.keyId), next.key, next.keyId));
    }
  }
  const bundle = { key: next.key, keyId: next.keyId };
  for (const v of await store.vaults()) {
    const l = locks[v.uuid];
    if (l) await store.putVault({ ...v, wrap: await vc.localWrap(bundle, v.uuid, l.type, l.value) });
  }
  session.key = next.key;
  session.keyId = next.keyId;
  await store.setLocks(session, locks);
  await store.setKv('passwordWrap', next.passwordWrap || null);
  await store.setDriveEmail(session, email);
}

// ------------------------------------------------------------------ index and restore points

async function loadIndex(session, st) {
  let doc = null;
  try {
    doc = await readDoc(session, 'objects_index.json', st.root);
  } catch (e) {
    if (String(e.message).startsWith('Drive')) throw e;
  }
  const index = new Map();
  if (doc) {
    for (const o of doc.objects || []) if (o && o.name && o.driveId) index.set(o.name, o);
    return index;
  }
  // Unreadable here (or none): rebuilt from the folder, as the apps do.
  for (const f of await children(st.objects)) {
    index.set(f.name, { name: f.name, driveId: f.id, size: Number(f.size) || 0, sha256: null, uploadedAt: 0 });
  }
  return index;
}

/** Saved with whatever another device added since it was read, so nobody's uploads are lost. */
async function saveIndex(session, st, index) {
  try {
    const now = await readDoc(session, 'objects_index.json', st.root);
    for (const o of (now && now.objects) || []) if (o && o.name && o.driveId && !index.has(o.name)) index.set(o.name, o);
  } catch (e) {
    if (String(e.message).startsWith('Drive')) throw e;
  }
  await writeDoc(session, 'objects_index.json', st.root, { formatVersion: 2, generation: Date.now(), objects: [...index.values()] });
}

async function snapshotIndex(session, st) {
  const doc = await readDoc(session, 'index.json', st.snapshots);
  return ((doc && doc.snapshots) || []).filter(x => x && x.id).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
}

async function newestPoint(session, st) {
  for (const x of await snapshotIndex(session, st)) {
    try {
      const doc = await readDoc(session, x.id + '.json', st.snapshots);
      if (doc) return normalise(doc);
    } catch (e) {
      if (String(e.message).startsWith('Drive')) throw e;
    }
    return null; // The newest can't be read here: nothing older is merged in its place.
  }
  return null;
}

function newId() {
  const d = new Date(), p = n => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}T${p(d.getUTCHours())}${p(d.getUTCMinutes())}${p(d.getUTCSeconds())}Z-`
    + Math.floor(Math.random() * 0xFFFF).toString(16);
}

/** The vaults as they stand in this browser (the vault must be open: lock values are needed). */
async function capture(session) {
  const locks = await store.locks(session);
  const settings = await store.settings();
  return normalise({
    formatVersion: FORMAT_VERSION, id: newId(), createdAt: Date.now(), device: DEVICE, installId: await store.installId(),
    lockSettings: { lock_bg_type: 'DEFAULT', lock_bg_value: null, lock_bg_blur: false, lock_bg_dim: false,
      ...((await store.kv('lockSettings')) || {}), auto_lock_time: settings.autoLock },
    vaults: (await store.vaults()).map(v => ({
      uuid: v.uuid, name: v.name, lockType: (locks[v.uuid] || {}).type || v.lockType, lockValue: (locks[v.uuid] || {}).value || null,
      isMain: !!v.isMain, sortOrder: v.sortOrder || 0, bgType: v.bgType || 'DEFAULT', bgValue: v.bgValue || null, lockAt: v.lockChangedAt || 0,
    })),
    albums: (await store.albums()).map(a => ({ uuid: a.uuid, name: a.name, vaultUuid: a.vaultUuid, coverFileName: null })),
    media: (await store.secrets()).map(s => ({
      fileName: s.fileName, vaultUuid: s.vaultUuid, albumUuid: s.albumUuid || null, isVideo: !!s.isVideo, isFavourite: !!s.isFavourite,
      dateTaken: s.dateTaken || 0, dateAdded: s.dateAdded || 0, originalPath: s.originalPath || null, sha256: s.sha256 || null,
    })),
  });
}

/** Writes a restore point's vaults, albums and rows here; then its deletions (see the apps' Snapshot.apply). */
async function apply(session, snap, { replaceLocal = false, syncLocks = false, revived = new Set() } = {}) {
  const locks = await store.locks(session);
  const bundle = { key: session.key, keyId: session.keyId };
  const taken = (except, type, value) => Object.entries(locks).some(([u, l]) => u !== except && l.type === type && l.value === value);
  const map = new Map();
  for (const r of snap.vaults) {
    if (!r.uuid) continue;
    let v = await store.vault(r.uuid);
    if (!v && r.isMain) v = await store.mainVault(); // This browser's main vault takes the backup's main items.
    if (!v) {
      if (!r.lockType || !r.lockValue) continue; // No way to open it.
      v = { uuid: r.uuid, name: r.name || 'Vault', lockType: r.lockType, isMain: !!r.isMain && !(await store.mainVault()),
        sortOrder: r.sortOrder || 0, lockChangedAt: r.lockAt || 0, bgType: r.bgType || 'DEFAULT', bgValue: r.bgValue || null,
        wrap: await vc.localWrap(bundle, r.uuid, r.lockType, r.lockValue) };
      locks[r.uuid] = { type: r.lockType, value: r.lockValue };
      await store.putVault(v);
    } else if (replaceLocal) {
      v = { ...v, name: r.name || v.name, sortOrder: r.sortOrder || 0, bgType: r.bgType || 'DEFAULT', bgValue: r.bgValue || null };
      if (r.lockType && r.lockValue) {
        Object.assign(v, { lockType: r.lockType, lockChangedAt: r.lockAt || 0, wrap: await vc.localWrap(bundle, v.uuid, r.lockType, r.lockValue) });
        locks[v.uuid] = { type: r.lockType, value: r.lockValue };
      }
      await store.putVault(v);
    } else if (syncLocks && r.lockType && r.lockValue && (r.lockAt || 0) > (v.lockChangedAt || 0) && !taken(v.uuid, r.lockType, r.lockValue)) {
      v = { ...v, lockType: r.lockType, lockChangedAt: r.lockAt, wrap: await vc.localWrap(bundle, v.uuid, r.lockType, r.lockValue) };
      locks[v.uuid] = { type: r.lockType, value: r.lockValue };
      await store.putVault(v);
    }
    map.set(r.uuid, v.uuid);
  }
  await store.setLocks(session, locks);
  const known = new Set((await store.albums()).map(a => a.uuid));
  for (const a of snap.albums) {
    if (!a.uuid || known.has(a.uuid) || !map.has(a.vaultUuid)) continue;
    await store.putAlbum({ uuid: a.uuid, name: a.name || 'Album', vaultUuid: map.get(a.vaultUuid) });
    known.add(a.uuid);
  }
  const main = await store.mainVault();
  for (const m of snap.media) {
    if ((await store.secret(m.fileName)) || !(await store.media(m.fileName))) continue;
    await store.putSecret({
      fileName: m.fileName, vaultUuid: map.get(m.vaultUuid) || (main && main.uuid), albumUuid: known.has(m.albumUuid) ? m.albumUuid : null,
      isVideo: !!m.isVideo, isFavourite: !!m.isFavourite, dateTaken: m.dateTaken || 0, dateAdded: m.dateAdded || Date.now(),
      originalPath: m.originalPath || null, sha256: m.sha256 || null, durationMs: 0,
      size: ((await store.media(m.fileName)) || { size: 0 }).size,
    });
  }
  // Deleted on another device: gone here too (without a mark of its own).
  for (const s of await store.secrets()) if (s.fileName in snap.removed && !revived.has(s.fileName)) await store.erase(s.fileName);
  for (const a of await store.albums()) if (('album:' + a.uuid) in snap.removed && !revived.has('album:' + a.uuid)) await store.deleteAlbum(a.uuid);
  for (const v of await store.vaults()) {
    const k = 'vault:' + v.uuid;
    if (!v.isMain && k in snap.removed && !revived.has(k) && !(await store.secretsOf(v.uuid)).length) {
      await store.deleteVault(v.uuid);
      delete locks[v.uuid];
      await store.setLocks(session, locks);
    }
  }
  await store.setKv('lockSettings', snap.lockSettings || {});
  if (replaceLocal && snap.lockSettings && typeof snap.lockSettings.auto_lock_time === 'number') {
    await store.setSettings({ autoLock: snap.lockSettings.auto_lock_time });
  }
}

// ------------------------------------------------------------------ jobs (one at a time)

let chain = Promise.resolve();
const exclusive = work => (chain = chain.then(work, work));

/**
 * Brings `snap`'s items down (what this browser lacks), checked against their recorded hashes,
 * then applies it. `pull`: the newest point, in a sync (nothing deleted here comes back, and what
 * the backup says was deleted goes). Otherwise (picked by hand) what it brings back is revived.
 */
async function bringDown(session, st, snap, { pull, replaceLocal }, progress) {
  const index = await loadIndex(session, st);
  const deletedHere = pull ? new Set(Object.keys(await store.removedMarks())) : new Set();
  const missing = [];
  for (const m of snap.media) {
    if (!safe(m.fileName) || deletedHere.has(m.fileName) || (pull && m.fileName in snap.removed)) continue;
    if (!(await store.media(m.fileName))) missing.push(m);
  }
  let failed = 0, done = 0;
  const brought = [];
  for (const m of missing) {
    progress(`Downloading ${done + 1} of ${missing.length}`, done, missing.length);
    done++;
    const e = index.get(m.fileName);
    if (!e) { failed++; continue; }
    try {
      const blob = await download(e.driveId);
      const plain = await vc.decrypt(blob, session.key, session.keyId);
      const expected = m.sha256 || e.sha256;
      if (expected && (await vc.sha256(plain)) !== String(expected).toLowerCase()) throw new Error('hash');
      await store.putMedia(m.fileName, blob);
      brought.push(m.fileName);
    } catch (err) {
      if (String(err.message).startsWith('Drive') || String(err.message).includes('sign')) throw err;
      failed++;
    }
  }
  if (!pull) {
    const marked = await store.removedMarks();
    for (const n of brought) await store.markRevived(n);
    for (const a of snap.albums) if (('album:' + a.uuid) in marked) await store.markRevived('album:' + a.uuid);
    for (const v of snap.vaults) if (('vault:' + v.uuid) in marked) await store.markRevived('vault:' + v.uuid);
  }
  progress('Updating your vaults', missing.length, missing.length);
  const settings = await store.settings();
  await apply(session, snap, { replaceLocal, syncLocks: pull && settings.syncLocks, revived: await store.revivedMarks() });
  return failed;
}

/** Uploads what Drive lacks, then publishes a restore point (objects, then the index, then the point). */
async function push(session, st, progress) {
  const index = await loadIndex(session, st);
  const todo = (await store.secrets()).filter(s => !index.has(s.fileName));
  let since = 0, failed = 0, done = 0;
  for (const s of todo) {
    progress(`Uploading ${done + 1} of ${todo.length}`, done, todo.length);
    done++;
    const blob = await store.media(s.fileName);
    if (!blob || (await vc.keyIdOf(blob)) !== session.keyId) { failed++; continue; }
    const id = await upload(blob, s.fileName, st.objects, BINARY);
    index.set(s.fileName, { name: s.fileName, driveId: id, size: blob.size, sha256: s.sha256 || null, uploadedAt: Date.now() });
    if (++since >= CHECKPOINT_FILES) { await saveIndex(session, st, index); since = 0; }
  }
  progress('Saving the index', todo.length, todo.length);
  await saveIndex(session, st, index);
  progress('Creating a restore point', todo.length, todo.length);
  const snap = await capture(session);
  snap.media = snap.media.filter(m => index.has(m.fileName)).map(m => ({ ...m, sha256: m.sha256 || index.get(m.fileName).sha256 || null }));
  const previous = await newestPoint(session, st);
  const settings = await store.settings();
  settle(snap, previous, new Set(index.keys()), await store.removedMarks(), await store.revivedMarks(), settings.syncLocks, Date.now());
  await writeDoc(session, snap.id + '.json', st.snapshots, snap);
  // Every point stays listed (the apps decide which to keep, and delete the rest).
  const list = await snapshotIndex(session, st);
  let bytes = 0;
  for (const e of index.values()) bytes += Number(e.size) || 0;
  list.unshift({ id: snap.id, createdAt: snap.createdAt, device: snap.device, installId: snap.installId, itemCount: snap.media.length, totalBytes: bytes, pinned: false });
  await writeDoc(session, 'index.json', st.snapshots, { formatVersion: FORMAT_VERSION, snapshots: list });
  await store.confirmMarks();
  await store.setKv('lastSync', Date.now());
  return failed;
}

/** "Sync now": the newest restore point comes down, then what's new here goes up. */
export function sync(session, { password = null, progress = () => {} } = {}) {
  return exclusive(async () => {
    progress('Connecting to Google Drive', 0, 0);
    const st = await openStore(true);
    await settleKey(session, st, password, progress);
    const latest = await newestPoint(session, st);
    let failed = latest ? await bringDown(session, st, latest, { pull: true, replaceLocal: false }, progress) : 0;
    failed += await push(session, st, progress);
    return failed;
  });
}

/** The restore points, newest first. */
export function restorePoints(session, password = null) {
  return exclusive(async () => {
    const st = await openStore(false);
    if (!st) return [];
    await settleKey(session, st, password);
    return snapshotIndex(session, st);
  });
}

/** One restore point, picked by hand: its items come back here (and, at the next sync, everywhere). */
export function restore(session, id, replaceLocal, progress = () => {}) {
  return exclusive(async () => {
    const st = await openStore(false);
    if (!st) throw new Error('There is no backup on this account');
    const doc = await readDoc(session, id + '.json', st.snapshots);
    if (!doc) throw new Error('That restore point is no longer on Drive');
    return bringDown(session, st, normalise(doc), { pull: false, replaceLocal }, progress);
  });
}
