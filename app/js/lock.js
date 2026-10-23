// Locked notes, byte for byte the apps' NoteLock: a note's content and data sealed under its
// own PIN, password or pattern, so the same lock opens it on the phone, the PC and here.
//
//   note.data    = {"lock":1,"type":"PIN|PASSWORD|PATTERN","iter":210000,"salt":b64,"check":hex,"iv":b64,"ct":b64}
//   note.content = ""
//   ct    = AES-256-GCM(key, iv, {"content":…,"data":…}), AAD "notes-plus/note-lock/v1"
//   key   = PBKDF2-HMAC-SHA256(lock, salt, iter)
//   check = HMAC-SHA256(key, "notes-plus/note-lock/check")
//
// Unlocked keys stay in memory for the time chosen on the account screen, counted from
// leaving the note. Each note's last lock (type, salt, check; never the key) is remembered in
// this browser, so locking it again is one click or one entry.

import { SKETCH, AUDIO } from './model.js';

export const ITERATIONS = 210000;
const enc = new TextEncoder();
const AAD = enc.encode('notes-plus/note-lock/v1');
const CHECK = enc.encode('notes-plus/note-lock/check');

function b64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
const unb64 = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
const hex = bytes => Array.from(new Uint8Array(bytes), x => x.toString(16).padStart(2, '0')).join('');

export class WrongLock extends Error {
  constructor() { super("That isn't this note's lock"); }
}

export function isLocked(data) {
  if (!data || data[0] !== '{' || !data.includes('"lock"')) return false;
  try {
    const o = JSON.parse(data);
    return o.lock === 1 && typeof o.ct === 'string';
  } catch {
    return false;
  }
}

export function typeOf(data) {
  try { return JSON.parse(data).type || 'PASSWORD'; } catch { return 'PASSWORD'; }
}

export const lockable = type => type !== SKETCH && type !== AUDIO;
export const typeWord = t => (t === 'PIN' ? 'PIN' : t === 'PATTERN' ? 'pattern' : 'password');

/** A derived key: {type, salt, iter, key (CryptoKey), check (hex)}. Slow on purpose (PBKDF2). */
async function derive(type, value, salt, iter) {
  const base = await crypto.subtle.importKey('raw', enc.encode(value), 'PBKDF2', false, ['deriveBits']);
  const raw = new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, base, 256));
  const key = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
  const mac = await crypto.subtle.importKey('raw', raw, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const check = hex(await crypto.subtle.sign('HMAC', mac, CHECK));
  raw.fill(0);
  return { type, salt, iter, key, check };
}

export async function newKey(type, value) {
  return derive(type, value, crypto.getRandomValues(new Uint8Array(16)), ITERATIONS);
}

/** The key of a locked note from its lock; throws WrongLock. */
export async function open(envelope, value) {
  const o = JSON.parse(envelope);
  const k = await derive(o.type || 'PASSWORD', value, unb64(o.salt), o.iter || ITERATIONS);
  if (k.check !== o.check) throw new WrongLock();
  return k;
}

export function fits(k, envelope) {
  try { return JSON.parse(envelope).check === k.check; } catch { return false; }
}

export async function seal(k, content, data) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plain = { content: content || '' };
  if (data !== null && data !== undefined) plain.data = data;
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: AAD }, k.key, enc.encode(JSON.stringify(plain))));
  return JSON.stringify({ lock: 1, type: k.type, iter: k.iter, salt: b64(k.salt), check: k.check, iv: b64(iv), ct: b64(ct) });
}

/** {content, data|null}; throws WrongLock. */
export async function unseal(k, envelope) {
  const o = JSON.parse(envelope);
  let plain;
  try {
    plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(o.iv), additionalData: AAD }, k.key, unb64(o.ct));
  } catch {
    throw new WrongLock();
  }
  const p = JSON.parse(new TextDecoder().decode(plain));
  return { content: p.content || '', data: typeof p.data === 'string' ? p.data : null };
}

// -------------------------------------------------------------------- session

const session = new Map();

/** Minutes a note stays open after leaving it: 0 locks on leaving, -1 until the tab closes. */
export function timeoutMinutes() {
  const v = Number(localStorage.getItem('np_note_unlock_minutes'));
  return Number.isFinite(v) && localStorage.getItem('np_note_unlock_minutes') !== null ? v : 5;
}

export function setTimeoutMinutes(m) {
  localStorage.setItem('np_note_unlock_minutes', String(m));
  if (m === 0) for (const [u, o] of session) if (!o.inUse) session.delete(u);
}

export function sessionKey(uuid) {
  const o = session.get(uuid);
  if (!o) return null;
  const m = timeoutMinutes();
  if (!o.inUse && m >= 0 && Date.now() - o.lastUse > m * 60000) {
    session.delete(uuid);
    return null;
  }
  return o.key;
}

export function opened(uuid, key) {
  session.set(uuid, { key, inUse: true, lastUse: Date.now() });
}

export function left(uuid) {
  const o = session.get(uuid);
  if (!o) return;
  if (timeoutMinutes() === 0) {
    session.delete(uuid);
    return;
  }
  o.inUse = false;
  o.lastUse = Date.now();
}

export const forget = uuid => session.delete(uuid);

// ------------------------------------------------------------------ last lock

export function remember(uuid, k) {
  localStorage.setItem('np_lock_last_' + uuid, JSON.stringify({ type: k.type, salt: b64(k.salt), iter: k.iter, check: k.check }));
}

export function lastType(uuid) {
  try { return JSON.parse(localStorage.getItem('np_lock_last_' + uuid)).type || null; } catch { return null; }
}

export async function lastKey(uuid, value) {
  const s = localStorage.getItem('np_lock_last_' + uuid);
  if (!s) throw new WrongLock();
  const o = JSON.parse(s);
  const k = await derive(o.type, value, unb64(o.salt), o.iter || ITERATIONS);
  if (k.check !== o.check) throw new WrongLock();
  return k;
}
