// The lock on hidden notes: the apps' HiddenLock. The phone and the PC start it as their main lock;
// here there is no app lock, so the web uses the account's (set on any device), and with none
// anywhere asks for one. After that it's theirs, changed from the hidden view.
//
// Stored only as "h1:<salt hex>:<hex PBKDF2-HMAC-SHA256(type + ':' + value, salt, 210000, 32)>", in
// this browser and at users/{uid}/hiddenLock = {type, hash, at}; the newest `at` wins. Wrong tries
// lock it out for a while (30 s, doubling to an hour), as the apps' lock screen does.

import * as db from './db.js';
import * as account from './account.js';
import { askLock, newLock, toast } from './ui.js';

const KEY = 'hiddenLock';
const FREE = 5, FIRST_OUT = 30000, MAX_OUT = 3600000;
const enc = new TextEncoder();
const hex = b => [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('');
const unhex = s => new Uint8Array(s.match(/../g).map(x => parseInt(x, 16)));

async function derive(type, value, salt) {
  const key = await crypto.subtle.importKey('raw', enc.encode(type + ':' + value), 'PBKDF2', false, ['deriveBits']);
  return crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 210000 }, key, 256);
}

export async function hash(type, value) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return 'h1:' + hex(salt) + ':' + hex(await derive(type, value, salt));
}

/** Whether `stored` ("h1:salt:hash") is this lock's. The same check as the apps'. */
export async function check(stored, type, value) {
  const p = (stored || '').split(':');
  if (p.length !== 3 || p[0] !== 'h1') return false;
  return hex(await derive(type, value, unhex(p[1]))) === p[2];
}

const local = () => db.kvGet(KEY, null);

async function pull() {
  const uid = account.uid();
  if (!uid) return;
  try {
    const { db: fdb, D } = await account.load();
    const r = (await D.get(D.ref(fdb, `users/${uid}/hiddenLock`))).val();
    const mine = await local();
    if (!r || !r.hash) { if (mine) await push(mine); return; }
    if (!mine || r.at > mine.at) await db.kvSet(KEY, { type: r.type, hash: r.hash, at: r.at });
    else if (r.at < mine.at) await push(mine);
  } catch { /* Offline: this browser's lock (if any) still opens them. */ }
}

async function push(state) {
  const uid = account.uid();
  if (!uid || !state) return;
  try {
    const { db: fdb, D } = await account.load();
    await D.set(D.ref(fdb, `users/${uid}/hiddenLock`), { type: state.type, hash: state.hash, at: state.at });
  } catch { /* Sent the next time it's checked or changed. */ }
}

async function set(type, value) {
  const state = { type, hash: await hash(type, value), at: Date.now() };
  await db.kvSet(KEY, state);
  await push(state);
}

// Wrong tries: counted in this browser, as the apps' lock screen counts on each device.
const out = () => { try { return Number(localStorage.getItem('np_hidden_until')) || 0; } catch { return 0; } };
function failed() {
  try {
    const n = (Number(localStorage.getItem('np_hidden_fails')) || 0) + 1;
    localStorage.setItem('np_hidden_fails', String(n));
    if (n >= FREE) localStorage.setItem('np_hidden_until', String(Date.now() + Math.min(FIRST_OUT * 2 ** Math.min(n - FREE, 20), MAX_OUT)));
  } catch { /* Private window: no count. */ }
}
function passed() {
  try { localStorage.removeItem('np_hidden_fails'); localStorage.removeItem('np_hidden_until'); } catch { /* Nothing kept. */ }
}

/** The lock, then `onOpen()` when it's right; with no lock anywhere yet, one is chosen first. */
export async function open(onOpen) {
  await pull();
  const state = await local();
  if (!state) {
    newLock('Choose a lock for hidden notes', 'It opens them on every device you sign in on.', async (type, value) => {
      await set(type, value);
      toast('Lock saved');
      onOpen();
    });
    return;
  }
  const ask = message => askLock(state.type, 'Enter lock', message, async value => {
    const wait = out() - Date.now();
    if (wait > 0) { toast(`Try again in ${Math.ceil(wait / 1000)}s`); return; }
    if (await check(state.hash, state.type, value)) { passed(); onOpen(); return; }
    failed();
    const w = out() - Date.now();
    ask(w > 0 ? `Try again in ${Math.ceil(w / 1000)}s` : 'Incorrect');
  });
  ask(null);
}

/** "Change lock", from the hidden view. */
export function change() {
  newLock('Change lock', null, async (type, value) => {
    await set(type, value);
    toast('Lock saved');
  });
}
