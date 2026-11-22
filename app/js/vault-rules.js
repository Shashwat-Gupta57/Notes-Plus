// Restore-point rules, the same as the desktop's and the phone's Snapshot (mergeFrom, settle) and
// CloudBackup.retain, kept in step by the same SnapshotSettleTest cases (tests/vault-rules.test.mjs).
// A restore point: {formatVersion, id, createdAt, device, installId, lockSettings,
//   vaults: [{uuid, name, lockType, lockValue, isMain, sortOrder, bgType, bgValue, lockAt}],
//   albums: [{uuid, name, vaultUuid, coverFileName}],
//   media: [{fileName, vaultUuid, albumUuid, isVideo, isFavourite, dateTaken, dateAdded, originalPath, sha256}],
//   removed: {fileName | "album:"uuid | "vault:"uuid: removedAt}}

export const FORMAT_VERSION = 3;
/** How long a deletion is remembered: longer than restore points are kept (30 days). */
export const REMOVED_KEEP_MS = 60 * 24 * 60 * 60 * 1000;
const KEEP_RECENT = 3;
const KEEP_DAILY_FOR_MS = 30 * 24 * 60 * 60 * 1000;

/** A restore point read leniently (missing lists are empty; no removed is none). */
export function normalise(s) {
  return {
    ...s,
    vaults: (s.vaults || []).filter(Boolean).map(v => ({ ...v, lockAt: v.lockAt || 0 })),
    albums: (s.albums || []).filter(Boolean),
    media: (s.media || []).filter(m => m && safe(m.fileName)),
    removed: { ...(s.removed || {}) },
    lockSettings: s.lockSettings || {},
  };
}

/** A name from a backup only ever names one item. */
export const safe = name => typeof name === 'string' && name.length > 0 && name.length <= 200 && !/[\\/:]|\.\./.test(name);

/**
 * Add-only: takes in everything `older` describes that `s` doesn't, as long as its bytes are
 * `stored`. The older one's main vault is this one's main vault.
 */
export function mergeFrom(s, older, stored) {
  const vaultIds = new Set(s.vaults.map(v => v.uuid));
  let main = (s.vaults.find(v => v.isMain) || {}).uuid || null;
  const same = new Map();
  for (const v of older.vaults) {
    if (!v.uuid || vaultIds.has(v.uuid)) continue;
    if (v.isMain && main) { same.set(v.uuid, main); continue; }
    s.vaults.push({ ...v });
    vaultIds.add(v.uuid);
    if (v.isMain) main = v.uuid;
  }
  const albumIds = new Set(s.albums.map(a => a.uuid));
  for (const a of older.albums) {
    if (!a.uuid || albumIds.has(a.uuid)) continue;
    s.albums.push({ ...a, vaultUuid: same.get(a.vaultUuid) || a.vaultUuid });
    albumIds.add(a.uuid);
  }
  const files = new Set(s.media.map(m => m.fileName));
  for (const m of older.media) {
    if (!m.fileName || files.has(m.fileName) || (stored && !stored.has(m.fileName))) continue;
    s.media.push({ ...m, vaultUuid: same.get(m.vaultUuid) || m.vaultUuid });
    files.add(m.fileName);
  }
}

/**
 * Makes `s` (just captured) the restore point after `previous`: its deletions plus this device's
 * `marks` ({key: at}), less what it `revived`; then everything else it holds; then the locks:
 * the backup's stay unless `syncLocks` is on and this device's are newer.
 */
export function settle(s, previous, stored, marks, revived, syncLocks, now) {
  const take = (k, at) => { if (!(s.removed[k] >= at)) s.removed[k] = at; };
  if (previous) for (const [k, at] of Object.entries(previous.removed)) take(k, at);
  for (const [k, at] of Object.entries(marks)) take(k, at);
  for (const k of revived) delete s.removed[k];
  for (const [k, at] of Object.entries(s.removed)) if (at < now - REMOVED_KEEP_MS) delete s.removed[k];
  if (previous) {
    const before = new Map(previous.vaults.filter(v => v.uuid).map(v => [v.uuid, v]));
    for (const v of s.vaults) {
      const p = before.get(v.uuid);
      if (!p || !p.lockType || !p.lockValue) continue;
      if (!syncLocks || p.lockAt > v.lockAt) Object.assign(v, { lockType: p.lockType, lockValue: p.lockValue, lockAt: p.lockAt });
    }
    mergeFrom(s, previous, stored);
  }
  s.media = s.media.filter(m => !(m.fileName in s.removed));
  s.albums = s.albums.filter(a => !(('album:' + a.uuid) in s.removed));
  s.vaults = s.vaults.filter(v => v.isMain || !(('vault:' + v.uuid) in s.removed));
}

/** The phone's rule: anything pinned, the 3 newest per installation, one a day for 30 days. */
export function retain(all, now) {
  const groups = new Map();
  for (const x of all) {
    const k = x.installId || x.device || 'unknown';
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(x);
  }
  const day = t => { const d = new Date(t); return d.getFullYear() * 10000 + (d.getMonth() + 1) * 100 + d.getDate(); };
  const kept = [];
  for (const bucket of groups.values()) {
    bucket.sort((a, b) => b.createdAt - a.createdAt);
    const days = new Set();
    bucket.forEach((x, i) => {
      let daily = false;
      if (now - x.createdAt < KEEP_DAILY_FOR_MS && !days.has(day(x.createdAt))) { days.add(day(x.createdAt)); daily = true; }
      if (x.pinned || i < KEEP_RECENT || daily) kept.push(x);
    });
  }
  return kept.sort((a, b) => b.createdAt - a.createdAt);
}
