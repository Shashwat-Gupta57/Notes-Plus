// The vault in this browser: IndexedDB stores (db.js v2) that the notes never read.
//
//   vaults     {uuid, name, lockType, isMain, sortOrder, lockChangedAt, bgType, bgValue, wrap}
//              wrap: the data key under this vault's lock (vault-crypto localWrap)
//   secrets    {fileName, vaultUuid, albumUuid, isVideo, isFavourite, dateTaken, dateAdded,
//               originalPath, sha256, size, durationMs, mime}
//   albums     {uuid, name, vaultUuid}
//   vaultData  "m/<fileName>": the item, encrypted (the backup's own format, so Drive gets these
//              bytes as they are); "t/<fileName>": its thumbnail, encrypted the same way
//   kv         vault:mode ("local" | "drive"), vault:locks (encrypted {uuid: {type, value}}: the
//              keyring and restore points need them), vault:marks, vault:settings, vault:drive
//              (encrypted {email}), vault:passwordWrap, vault:lockSettings, vault:lockout,
//              vault:install, vault:lastSync, vault:cell
//
// Nothing here is ever plaintext media. The desktop's VaultStore keeps the same rows.

import * as db from './db.js';
import { encryptJson, decryptJson } from './vault-crypto.js';

// ------------------------------------------------------------------ vaults, albums, items

export const vaults = async () => (await db.storeAll('vaults')).sort((a, b) => (b.isMain - a.isMain) || (a.sortOrder || 0) - (b.sortOrder || 0));
export const vault = uuid => db.storeGet('vaults', uuid);
export const putVault = v => db.storePut('vaults', v);
export const mainVault = async () => (await vaults()).find(v => v.isMain) || null;

export const secrets = () => db.storeAll('secrets');
export const secret = name => db.storeGet('secrets', name);
export const putSecret = s => db.storePut('secrets', s);
export const secretsOf = async vaultUuid => (await secrets()).filter(s => s.vaultUuid === vaultUuid);

export const albums = () => db.storeAll('albums');
export const putAlbum = a => db.storePut('albums', a);
export const albumsOf = async vaultUuid => (await albums()).filter(a => a.vaultUuid === vaultUuid);

/** An album goes; its items stay (out of any album). */
export async function deleteAlbum(uuid) {
  for (const s of await secrets()) {
    if (s.albumUuid === uuid) await putSecret({ ...s, albumUuid: null });
  }
  await db.storeDelete('albums', uuid);
}

export async function deleteVault(uuid) {
  for (const a of await albums()) if (a.vaultUuid === uuid) await db.storeDelete('albums', a.uuid);
  await db.storeDelete('vaults', uuid);
}

export const media = name => db.storeGet('vaultData', 'm/' + name);
export const putMedia = (name, blob) => db.storePut('vaultData', blob, 'm/' + name);
export const thumb = name => db.storeGet('vaultData', 't/' + name);
export const putThumb = (name, blob) => db.storePut('vaultData', blob, 't/' + name);

/** An item gone from here: its row, file and thumbnail (no mark: see `remove`). */
export async function erase(name) {
  await db.storeDelete('secrets', name);
  await db.storeDelete('vaultData', 'm/' + name);
  await db.storeDelete('vaultData', 't/' + name);
}

/** Deleted here: gone, and marked so the deletion reaches the backup and every other device. */
export async function remove(name) {
  await erase(name);
  await markRemoved(name);
}

/** Everything of the vault in this browser, gone (the notes untouched). */
export async function wipe() {
  for (const s of db.VAULT_STORES) await db.storeClear(s);
  for (const k of ['mode', 'locks', 'marks', 'settings', 'drive', 'passwordWrap', 'lockSettings', 'lastSync', 'cell']) await db.kvSet('vault:' + k, null);
}

// ------------------------------------------------------------------ settings

export const mode = () => db.kvGet('vault:mode', null);
export const setMode = m => db.kvSet('vault:mode', m);
const DEFAULTS = { autoLock: 0, syncOnUnlock: true, syncLocks: false };
export const settings = async () => ({ ...DEFAULTS, ...((await db.kvGet('vault:settings', null)) || {}) });
export const setSettings = async patch => db.kvSet('vault:settings', { ...(await settings()), ...patch });
export const kv = (k, fallback = null) => db.kvGet('vault:' + k, fallback);
export const setKv = (k, v) => db.kvSet('vault:' + k, v);

export async function installId() {
  let id = await kv('install');
  if (!id) {
    id = crypto.randomUUID();
    await setKv('install', id);
  }
  return id;
}

// ------------------------------------------------------------------ lock values (encrypted)

/** {uuid: {type, value}} of every vault, under the data key. */
export async function locks(session) {
  const blob = await kv('locks');
  return blob ? decryptJson(blob, session.key, session.keyId) : {};
}

export async function setLocks(session, map) {
  await setKv('locks', await encryptJson(map, session.key, session.keyId));
}

/** The vault's Google account (its email, under the data key), or null. */
export async function driveEmail(session) {
  const blob = await kv('drive');
  try {
    return blob ? (await decryptJson(blob, session.key, session.keyId)).email || null : null;
  } catch {
    return null;
  }
}

export async function setDriveEmail(session, email) {
  await setKv('drive', email ? await encryptJson({ email }, session.key, session.keyId) : null);
}

// ------------------------------------------------------------------ marks
// What this browser deleted (items, "album:" / "vault:" + uuid) or brought back from an older
// restore point, kept until three backups have carried it: the desktop's and the phone's
// VaultMarks, the same rules. {removed: {key: [at, n]}, revived: {key: [at, n]}}.

const CONFIRMATIONS = 3;
const marks = async () => {
  const m = (await kv('marks')) || {};
  return { removed: m.removed || {}, revived: m.revived || {} };
};

async function mark(key, into, outOf) {
  const m = await marks();
  delete m[outOf][key];
  m[into][key] = [Date.now(), 0];
  await setKv('marks', m);
}

export const markRemoved = key => mark(key, 'removed', 'revived');
export const markRevived = key => mark(key, 'revived', 'removed');

export async function removedMarks() {
  const out = {};
  for (const [k, e] of Object.entries((await marks()).removed)) out[k] = e[0];
  return out;
}

export async function revivedMarks() {
  return new Set(Object.keys((await marks()).revived));
}

/** A backup carrying the marks was written: each counts once more, and goes after three. */
export async function confirmMarks() {
  const m = await marks();
  for (const part of ['removed', 'revived']) {
    for (const k of Object.keys(m[part])) {
      const n = m[part][k][1] + 1;
      if (n >= CONFIRMATIONS) delete m[part][k];
      else m[part][k][1] = n;
    }
  }
  await setKv('marks', m);
}
