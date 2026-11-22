// The vault's crypto, in the browser. The same formats as the phone and the computer:
//
//   media and documents: "NP" 0x03 keyId(8), then Tink's AES-GCM-HKDF streaming (256 KB segments;
//     header byte 40, 32-byte salt, 7-byte nonce prefix; HKDF-SHA256 on HMAC-SHA256(dataKey,
//     "notes-plus/portable-stream/v3") with the 11-byte NP header as info). Byte for byte what
//     the desktop's Portable and the phone's VaultCrypto write, so files move between them as they are.
//   keyring.json wraps (Drive): AES-GCM (no AAD) under PBKDF2-SHA256(lock value, salt, 210000).
//   This browser's own wraps: the same, keyed on "wrap\0" + type + "\0" + value, the vault's uuid
//     as AAD (as the desktop's local keyring), so a lock of one type never opens with another's.

const subtle = crypto.subtle;
const enc = new TextEncoder();
export const SEGMENT = 256 * 1024;
const HEADER_LENGTH = 40; // Tink: 1 + 32 (salt) + 7 (nonce prefix)
const TAG = 16;
const LABEL = 'notes-plus/portable-stream/v3';
export const ITERATIONS = 210000;

export const rand = n => crypto.getRandomValues(new Uint8Array(n));
export const hex = b => Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
export const unhex = s => Uint8Array.from(s.match(/../g) || [], x => parseInt(x, 16));
export const b64 = u => btoa(String.fromCharCode(...u));
export const unb64 = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));

/** A new data key: {key (32 bytes), keyId (16 hex)}. */
export function newDataKey() {
  return { key: rand(32), keyId: hex(rand(8)) };
}

async function streamKey(key, header, salt, use) {
  const mac = await subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const ikm = new Uint8Array(await subtle.sign('HMAC', mac, enc.encode(LABEL)));
  const hkdf = await subtle.importKey('raw', ikm, 'HKDF', false, ['deriveKey']);
  return subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt, info: header }, hkdf, { name: 'AES-GCM', length: 256 }, false, [use]);
}

function nonce(prefix, i, last) {
  const iv = new Uint8Array(12);
  iv.set(prefix, 0);
  new DataView(iv.buffer).setUint32(7, i);
  iv[11] = last ? 1 : 0;
  return iv;
}

const asBlob = d => (d instanceof Blob ? d : new Blob([d]));

/** `data` (Blob or bytes) encrypted under the data key: a Blob, read and written a segment at a time. */
export async function encrypt(data, key, keyId) {
  const blob = asBlob(data);
  const header = new Uint8Array(11);
  header.set([0x4E, 0x50, 3]);
  header.set(unhex(keyId), 3);
  const salt = rand(32), prefix = rand(7);
  const k = await streamKey(key, header, salt, 'encrypt');
  const parts = [header, Uint8Array.of(HEADER_LENGTH), salt, prefix];
  let pos = 0, i = 0;
  do {
    const end = Math.min(blob.size, pos + (i === 0 ? SEGMENT - HEADER_LENGTH - TAG : SEGMENT - TAG));
    const plain = await blob.slice(pos, end).arrayBuffer();
    parts.push(new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv: nonce(prefix, i, end === blob.size) }, k, plain)));
    pos = end;
    i++;
  } while (pos < blob.size);
  return new Blob(parts);
}

/** The key id a payload was made with (hex), or null when it isn't one. */
export async function keyIdOf(data) {
  const b = new Uint8Array(await asBlob(data).slice(0, 11).arrayBuffer());
  return b.length === 11 && b[0] === 0x4E && b[1] === 0x50 && b[2] === 3 ? hex(b.subarray(3, 11)) : null;
}

/** Plaintext of a payload (Blob or bytes) as a Blob of `type`. Throws when it isn't ours. */
export async function decrypt(data, key, keyId, type = '') {
  const blob = asBlob(data);
  const head = new Uint8Array(await blob.slice(0, 12 + 39).arrayBuffer());
  if (blob.size < 11 + 1 + 39 + TAG || head[0] !== 0x4E || head[1] !== 0x50 || head[2] !== 3) throw new Error('Not a current Notes+ payload');
  const header = head.slice(0, 11);
  if (keyId && hex(header.subarray(3, 11)) !== keyId) throw new Error('Made with another key');
  const hl = head[11];
  const salt = head.slice(12, 12 + 32), prefix = head.slice(12 + 32, 11 + hl);
  const k = await streamKey(key, header, salt, 'decrypt');
  const parts = [];
  let pos = 11 + hl, i = 0;
  do {
    const end = Math.min(blob.size, pos + (i === 0 ? SEGMENT - hl : SEGMENT));
    const cipher = await blob.slice(pos, end).arrayBuffer();
    parts.push(new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: nonce(prefix, i, end === blob.size) }, k, cipher)));
    pos = end;
    i++;
  } while (pos < blob.size);
  return new Blob(parts, { type });
}

export async function encryptJson(value, key, keyId) {
  return encrypt(enc.encode(JSON.stringify(value)), key, keyId);
}

/** A document: plain JSON when it starts with "{" (very old ones), else encrypted. */
export async function decryptJson(data, key, keyId) {
  const blob = asBlob(data);
  const first = new Uint8Array(await blob.slice(0, 1).arrayBuffer())[0];
  const plain = first === 0x7B ? blob : await decrypt(blob, key, keyId);
  return JSON.parse(await plain.text());
}

/** Hex SHA-256 of the plaintext (what restore points and the object index record). */
export async function sha256(data) {
  return hex(new Uint8Array(await subtle.digest('SHA-256', await asBlob(data).arrayBuffer())));
}

async function pbkdf2(secret, salt, iterations, use) {
  const base = await subtle.importKey('raw', enc.encode(secret), 'PBKDF2', false, ['deriveKey']);
  return subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, base, { name: 'AES-GCM', length: 256 }, false, use);
}

// ------------------------------------------------------------------ keyring.json (Drive)

/** The phone's wrap: {label, iterations, salt, iv, wrapped, lockType}. */
export async function backupWrap(key, value, label, lockType) {
  const salt = rand(16), iv = rand(12);
  const k = await pbkdf2(value, salt, ITERATIONS, ['encrypt']);
  const wrapped = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv }, k, key));
  const w = { label: label || 'Vault', iterations: ITERATIONS, salt: b64(salt), iv: b64(iv), wrapped: b64(wrapped) };
  if (lockType) w.lockType = lockType;
  return w;
}

/** The data key from a keyring with `value` (a backup password or a vault lock), or null. */
export async function backupUnwrap(keyring, value) {
  for (const w of (keyring && keyring.wraps) || []) {
    try {
      const k = await pbkdf2(value, unb64(w.salt), w.iterations || ITERATIONS, ['decrypt']);
      return new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: unb64(w.iv) }, k, unb64(w.wrapped)));
    } catch { /* The next wrap may be the one. */ }
  }
  return null;
}

// ------------------------------------------------------------------ this browser's locks

/** {key, keyId} wrapped under one vault's lock, for this browser: {salt, iv, data}. */
export async function localWrap(bundle, uuid, type, value) {
  const salt = rand(16), iv = rand(12);
  const k = await pbkdf2('wrap\0' + type + '\0' + value, salt, ITERATIONS, ['encrypt']);
  const plain = enc.encode(JSON.stringify({ key: b64(bundle.key), keyId: bundle.keyId }));
  const data = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: enc.encode(uuid) }, k, plain));
  return { salt: b64(salt), iv: b64(iv), data: b64(data) };
}

/** The {key, keyId} a vault's wrap holds, when `type`/`value` is its lock; else null. */
export async function localUnwrap(wrap, uuid, type, value) {
  if (!wrap) return null;
  try {
    const k = await pbkdf2('wrap\0' + type + '\0' + value, unb64(wrap.salt), ITERATIONS, ['decrypt']);
    const plain = await subtle.decrypt({ name: 'AES-GCM', iv: unb64(wrap.iv), additionalData: enc.encode(uuid) }, k, unb64(wrap.data));
    const o = JSON.parse(new TextDecoder().decode(plain));
    return { key: unb64(o.key), keyId: o.keyId };
  } catch {
    return null;
  }
}
