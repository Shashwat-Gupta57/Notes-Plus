// Notes+ on the web: everything is kept in this browser (IndexedDB), like the apps keep
// everything on the device. Notes, the files they use (pictures, recordings, sketches), and a
// few settings. Other tabs of the app hear about changes and redraw.

const NAME = 'notesplus';
const VERSION = 1;
let opening = null;

function open() {
  if (!opening) {
    opening = new Promise((resolve, reject) => {
      const req = indexedDB.open(NAME, VERSION);
      req.onupgradeneeded = () => {
        const d = req.result;
        if (!d.objectStoreNames.contains('notes')) d.createObjectStore('notes', { keyPath: 'uuid' });
        if (!d.objectStoreNames.contains('files')) d.createObjectStore('files');
        if (!d.objectStoreNames.contains('kv')) d.createObjectStore('kv');
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return opening;
}

function run(store, mode, work) {
  return open().then(d => new Promise((resolve, reject) => {
    const t = d.transaction(store, mode);
    const s = t.objectStore(store);
    let result;
    Promise.resolve(work(s)).then(r => { result = r; });
    t.oncomplete = () => resolve(result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  }));
}

function req(r) {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

// ------------------------------------------------------------------ change events

const listeners = new Set();
const channel = 'BroadcastChannel' in window ? new BroadcastChannel('notesplus') : null;
if (channel) channel.onmessage = e => listeners.forEach(f => f(e.data, true));

export function onChange(f) {
  listeners.add(f);
  return () => listeners.delete(f);
}

export function changed(what = 'notes') {
  listeners.forEach(f => f(what, false));
  if (channel) channel.postMessage(what);
}

// --------------------------------------------------------------------- notes

export async function allNotes() {
  return run('notes', 'readonly', s => req(s.getAll()));
}

export async function getNote(uuid) {
  if (!uuid) return null;
  return (await run('notes', 'readonly', s => req(s.get(uuid)))) || null;
}

export async function putNote(note, quiet = false) {
  await run('notes', 'readwrite', s => req(s.put(note)));
  if (!quiet) changed('notes');
}

/** Removes a note and its files from this browser. */
export async function purgeNote(uuid, quiet = false) {
  await run('notes', 'readwrite', s => req(s.delete(uuid)));
  await deleteFilesOf(uuid);
  if (!quiet) changed('notes');
}

// --------------------------------------------------------------------- files

const fileKey = (uuid, name) => uuid + '/' + name;

export async function getFile(uuid, name) {
  return (await run('files', 'readonly', s => req(s.get(fileKey(uuid, name))))) || null;
}

export async function putFile(uuid, name, blob) {
  await run('files', 'readwrite', s => req(s.put(blob, fileKey(uuid, name))));
}

export async function deleteFile(uuid, name) {
  await run('files', 'readwrite', s => req(s.delete(fileKey(uuid, name))));
}

/** Names of the files kept for a note. */
export async function filesOf(uuid) {
  const prefix = uuid + '/';
  const keys = await run('files', 'readonly', s =>
    req(s.getAllKeys(IDBKeyRange.bound(prefix, prefix + '￿'))));
  return keys.map(k => k.slice(prefix.length));
}

export async function deleteFilesOf(uuid) {
  const prefix = uuid + '/';
  await run('files', 'readwrite', s => req(s.delete(IDBKeyRange.bound(prefix, prefix + '￿'))));
}

// ------------------------------------------------------------------- settings

export async function kvGet(key, fallback = null) {
  const v = await run('kv', 'readonly', s => req(s.get(key)));
  return v === undefined ? fallback : v;
}

export async function kvSet(key, value) {
  await run('kv', 'readwrite', s => req(value === null ? s.delete(key) : s.put(value, key)));
}

/** Object URLs for files, made once per file and reused while the page lives. */
const urls = new Map();
export async function fileUrl(uuid, name) {
  const k = fileKey(uuid, name);
  if (urls.has(k)) return urls.get(k);
  const blob = await getFile(uuid, name);
  if (!blob) return null;
  const u = URL.createObjectURL(blob);
  urls.set(k, u);
  return u;
}

export function forgetUrl(uuid, name) {
  const k = fileKey(uuid, name);
  const u = urls.get(k);
  if (u) URL.revokeObjectURL(u);
  urls.delete(k);
}
