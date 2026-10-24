// The gallery, read only, from its Drive backup: the same store the phone and the computer
// back up to (Notes-plus/Saved-Notes/Cloud/Remote/Backup). Nothing here is ever written: no
// item, album, lock or setting changes, and nothing stays in this browser once it closes.
//
// Opening goes as a backup restore does: the Google account that holds the backup, then the
// backup's lock (its backup password, or a vault lock when it has none), then the vaults,
// each with its own lock.
//
// Formats (as CloudSync / CloudBackup write them):
//   keyring.json   plain {keyId, wraps: [{label, lockType?, iterations, salt, iv, wrapped}]}:
//                  the data key, AES-GCM-wrapped under PBKDF2-SHA256(lock)
//   everything else: "NP" 0x03 keyId(8), then Tink's AES-GCM-HKDF streaming (256 KB segments,
//                  HKDF-SHA256 on HMAC-SHA256(dataKey, "notes-plus/portable-stream/v3"), salt and
//                  nonce prefix in its header, the 11-byte NP header as HKDF info)

import { h, icon, iconButton, toast, lockInput } from './ui.js';

const CLIENT_ID = '746909983498-4n7m7cne02j9shb3acm6gial0ergi5s9.apps.googleusercontent.com';
const SCOPE = 'https://www.googleapis.com/auth/drive.file';
const API = 'https://www.googleapis.com/drive/v3/files';
const ROOT_PATH = ['Notes-plus', 'Saved-Notes', 'Cloud', 'Remote', 'Backup'];
const SEGMENT = 256 * 1024;
const enc = new TextEncoder();
const unb64 = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
const hex = b => Array.from(b, x => x.toString(16).padStart(2, '0')).join('');

// ------------------------------------------------------------------ Drive

function driveSession(token) {
  async function call(url) {
    const r = await fetch(url, { headers: { Authorization: 'Bearer ' + token } });
    if (r.status === 401) throw new Error('Google sign-in expired. Open it again.');
    if (!r.ok) throw new Error('Drive: HTTP ' + r.status);
    return r;
  }
  const q = s => s.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  async function find(name, parent, folder) {
    const query = `name = '${q(name)}' and '${q(parent)}' in parents and trashed = false`
      + (folder ? " and mimeType = 'application/vnd.google-apps.folder'" : '');
    const r = await call(`${API}?spaces=drive&orderBy=createdTime%20desc&fields=files(id)&q=${encodeURIComponent(query)}`);
    const files = (await r.json()).files || [];
    return files.length ? files[0].id : null;
  }
  return {
    find,
    async bytes(id) { return new Uint8Array(await (await call(`${API}/${encodeURIComponent(id)}?alt=media`)).arrayBuffer()); },
  };
}

function signIn() {
  return new Promise((resolve, reject) => {
    const go = () => {
      const client = window.google.accounts.oauth2.initTokenClient({
        client_id: CLIENT_ID, scope: SCOPE, prompt: 'select_account',
        callback: r => (r && r.access_token ? resolve(r.access_token) : reject(new Error('cancelled'))),
        error_callback: () => reject(new Error('cancelled')),
      });
      client.requestAccessToken();
    };
    if (window.google && window.google.accounts && window.google.accounts.oauth2) { go(); return; }
    const s = document.createElement('script');
    s.src = 'https://accounts.google.com/gsi/client';
    s.onload = go;
    s.onerror = () => reject(new Error('Couldn\'t reach Google'));
    document.head.appendChild(s);
  });
}

// ----------------------------------------------------------------- crypto

/** The data key from the keyring with `value` (the backup password or a vault lock), or null. */
async function unwrap(keyring, value) {
  for (const w of keyring.wraps || []) {
    try {
      const base = await crypto.subtle.importKey('raw', enc.encode(value), 'PBKDF2', false, ['deriveKey']);
      const k = await crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt: unb64(w.salt), iterations: w.iterations || 210000 },
        base, { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
      return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(w.iv) }, k, unb64(w.wrapped)));
    } catch { /* The next wrap may be the one. */ }
  }
  return null;
}

/** Plaintext of a v3 payload under `dataKey`. Throws when it isn't one, or is someone else's. */
async function decrypt(b, dataKey, keyId) {
  if (b.length < 11 + 40 || b[0] !== 0x4E || b[1] !== 0x50 || b[2] !== 3) throw new Error('Not a current Notes+ payload');
  const header = b.subarray(0, 11);
  if (keyId && hex(header.subarray(3, 11)) !== keyId) throw new Error('Made with another key');
  const hl = b[11];
  const salt = b.subarray(12, 12 + 32), prefix = b.subarray(12 + 32, 11 + hl);
  const mac = await crypto.subtle.importKey('raw', dataKey, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const ikm = new Uint8Array(await crypto.subtle.sign('HMAC', mac, enc.encode('notes-plus/portable-stream/v3')));
  const hkdf = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveKey']);
  const key = await crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt, info: header.slice() }, hkdf,
    { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
  const parts = [];
  let pos = 11 + hl, i = 0;
  do {
    const end = Math.min(b.length, pos + (i === 0 ? SEGMENT - hl : SEGMENT));
    const iv = new Uint8Array(12);
    iv.set(prefix, 0);
    new DataView(iv.buffer).setUint32(7, i);
    iv[11] = end === b.length ? 1 : 0;
    parts.push(new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, b.subarray(pos, end))));
    pos = end;
    i++;
  } while (pos < b.length);
  return parts;
}

// ----------------------------------------------------------------- screen

/**
 * The gallery screen: {el, leave}. `close` goes back to the notes. Everything it opened
 * (keys, pictures) is dropped when it is left or the tab is hidden.
 */
export function galleryScreen(close) {
  const body = h('div.gallery-body');
  const root = h('div.gallery', body);
  let drive = null, dataKey = null, keyId = null, objects = new Map(), snapshot = null;
  const urls = new Map();
  let gone = false;

  const wipe = () => {
    gone = true;
    for (const u of urls.values()) URL.revokeObjectURL(u);
    urls.clear();
    if (dataKey) dataKey.fill(0);
    dataKey = null;
    drive = null;
    snapshot = null;
  };
  const hidden = () => { if (document.visibilityState === 'hidden') close(); };
  document.addEventListener('visibilitychange', hidden);

  function lockScreen(title, message, type, onValue, extra) {
    let input;
    const slot = h('div.gallery-lock-input');
    const show = t => {
      input = lockInput(t, v => onValue(v));
      slot.replaceChildren(input.node);
      setTimeout(() => { const f = slot.querySelector('input'); if (f) f.focus(); }, 30);
    };
    show(type || 'PASSWORD');
    const tabs = h('div.gallery-tabs', ...['PIN', 'PASSWORD', 'PATTERN'].map(t =>
      h('button.chip', { type: 'button', onClick: () => show(t) }, t === 'PIN' ? 'PIN' : t === 'PATTERN' ? 'Pattern' : 'Password')));
    body.replaceChildren(h('div.gallery-lock',
      icon('lock', 40, 'var(--brand)'), h('h2', title), message ? h('p', message) : null, tabs, slot,
      h('button.btn.filled', { type: 'button', onClick: () => { const v = input.value(); if (v) onValue(v); } }, 'Open'),
      extra || null));
  }

  async function connect() {
    body.replaceChildren(h('div.gallery-lock', icon('lock', 40, 'var(--brand)'), h('h2', 'Notes+'),
      h('p', 'Choose the Google account that holds the backup.'),
      h('button.btn.filled', { type: 'button', onClick: async () => {
        try {
          drive = driveSession(await signIn());
          await openKeyring();
        } catch (e) {
          if (String(e.message) !== 'cancelled') toast(e.message, 4000);
        }
      } }, 'Choose account')));
  }

  let folders = null;
  async function openKeyring() {
    body.replaceChildren(h('div.gallery-lock', h('p', 'Looking for the backup…')));
    let parent = 'root';
    for (const name of ROOT_PATH) {
      parent = await drive.find(name, parent, true);
      if (!parent) { body.replaceChildren(h('div.gallery-lock', h('p', 'Nothing here. Choose another account?'))); setTimeout(connect, 2500); return; }
    }
    folders = { root: parent, snapshots: await drive.find('snapshots', parent, true) };
    const kid = await drive.find('keyring.json', parent, false);
    if (!kid) throw new Error('Nothing here to open');
    const keyring = JSON.parse(new TextDecoder().decode(await drive.bytes(kid)));
    keyId = keyring.keyId || null;
    const wraps = keyring.wraps || [];
    const backupLock = wraps.find(w => String(w.label || '').startsWith('Backup'));
    const type = backupLock ? 'PASSWORD' : (wraps[0] && wraps[0].lockType) || 'PASSWORD';
    lockScreen('Enter password', null, type, async v => {
      const k = await unwrap(keyring, v);
      if (!k) { toast('Wrong password'); return; }
      dataKey = k;
      try {
        await loadStore();
        vaultList();
      } catch (e) {
        toast(e.message, 4000);
      }
    });
  }

  async function doc(name, parent) {
    const id = await drive.find(name, parent, false);
    if (!id) return null;
    const raw = await drive.bytes(id);
    if (raw[0] === 0x7B) return JSON.parse(new TextDecoder().decode(raw));
    const parts = await decrypt(raw, dataKey, keyId);
    return JSON.parse(new TextDecoder().decode(await new Blob(parts).arrayBuffer()));
  }

  async function loadStore() {
    body.replaceChildren(h('div.gallery-lock', h('p', 'Opening…')));
    const index = await doc('objects_index.json', folders.root);
    objects = new Map(((index && index.objects) || []).map(o => [o.name, o]));
    const points = folders.snapshots ? await doc('index.json', folders.snapshots) : null;
    const list = ((points && points.snapshots) || []).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    if (!list.length) throw new Error('This backup has nothing in it yet');
    snapshot = await doc(list[0].id + '.json', folders.snapshots);
    if (!snapshot) throw new Error('Couldn\'t read the backup');
  }

  function vaultList() {
    const vaults = [...(snapshot.vaults || [])].sort((a, b) => (b.isMain - a.isMain) || ((a.sortOrder || 0) - (b.sortOrder || 0)));
    const count = v => (snapshot.media || []).filter(m => m.vaultUuid === v.uuid).length;
    body.replaceChildren(h('div.gallery-vaults',
      h('div.toolbar', iconButton('back', 'Close', close), h('h1.toolbar-title', 'Vaults')),
      ...vaults.map(v => h('button.row', { type: 'button', onClick: () => openVault(v) },
        h('span.row-icon', { style: { background: 'var(--tile-purple)' } }, icon('lock', 20, '#fff')),
        h('span.row-texts', h('span.row-title', v.name || 'Vault'), h('span.row-sub', count(v) + ' items · read only')),
        icon('chevron', 22, 'var(--secondary)')))));
  }

  function openVault(v) {
    lockScreen(v.name || 'Vault', null, v.lockType || 'PASSWORD', value => {
      if (value !== v.lockValue) { toast('Wrong ' + (v.lockType === 'PIN' ? 'PIN' : v.lockType === 'PATTERN' ? 'pattern' : 'password')); return; }
      grid(v, null);
    }, h('button.btn.text', { type: 'button', onClick: vaultList }, 'Other vaults'));
  }

  /** Decrypted item as an object URL (kept while the gallery is open). */
  async function url(m) {
    if (urls.has(m.fileName)) return urls.get(m.fileName);
    const o = objects.get(m.fileName);
    if (!o || !drive) return null;
    const parts = await decrypt(await drive.bytes(o.driveId), dataKey, keyId);
    if (gone) return null;
    const u = URL.createObjectURL(new Blob(parts, { type: m.isVideo ? 'video/mp4' : '' }));
    urls.set(m.fileName, u);
    return u;
  }

  // Up to three downloads at once, in the order they were asked for.
  const queue = [];
  let running = 0;
  function fetchSoon(m, then) {
    queue.push([m, then]);
    pump();
  }
  function pump() {
    while (running < 3 && queue.length) {
      const [m, then] = queue.shift();
      running++;
      url(m).then(then, () => then(null)).finally(() => { running--; pump(); });
    }
  }

  function grid(v, albumUuid) {
    const items = (snapshot.media || []).filter(m => m.vaultUuid === v.uuid && (albumUuid === null ? true
      : albumUuid === 'FAV' ? m.isFavourite : m.albumUuid === albumUuid))
      .sort((a, b) => (b.dateTaken || b.dateAdded || 0) - (a.dateTaken || a.dateAdded || 0));
    const albums = (snapshot.albums || []).filter(a => a.vaultUuid === v.uuid);
    const chips = h('div.chips',
      h('button.chip' + (albumUuid === null ? '.on' : ''), { type: 'button', onClick: () => grid(v, null) }, 'All'),
      h('button.chip' + (albumUuid === 'FAV' ? '.on' : ''), { type: 'button', onClick: () => grid(v, 'FAV') }, 'Favourites'),
      ...albums.map(a => h('button.chip' + (albumUuid === a.uuid ? '.on' : ''), { type: 'button', onClick: () => grid(v, a.uuid) }, a.name || 'Album')));
    const tiles = h('div.gallery-grid');
    const observer = new IntersectionObserver(entries => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        observer.unobserve(e.target);
        const m = e.target._item;
        fetchSoon(m, u => {
          if (!u || gone) return;
          if (m.isVideo) e.target.appendChild(h('video', { src: u, muted: true, preload: 'metadata' }));
          else e.target.appendChild(h('img', { src: u, alt: '' }));
        });
      }
    }, { rootMargin: '400px' });
    items.forEach((m, i) => {
      const tile = h('button.gallery-tile', { type: 'button', onClick: () => viewer(items, i) },
        m.isVideo ? h('span.gallery-badge', icon('play', 18, '#fff')) : null);
      tile._item = m;
      tiles.appendChild(tile);
      observer.observe(tile);
    });
    body.replaceChildren(
      h('div.toolbar', iconButton('back', 'Vaults', vaultList), h('h1.toolbar-title', v.name || 'Vault'),
        h('span.editor-from', items.length + ' items · read only')),
      h('div.chips-scroll', chips),
      items.length ? tiles : h('p.link-message', 'Nothing here.'));
  }

  function viewer(items, start) {
    let i = start;
    const stage = h('div.gallery-stage');
    const shade = h('div.gallery-viewer', stage,
      h('button.gallery-nav.prev', { type: 'button', onClick: () => show(i - 1) }, icon('back', 28, '#fff')),
      h('button.gallery-nav.close', { type: 'button', onClick: () => shut() }, icon('close', 28, '#fff')));
    const key = e => {
      if (e.key === 'Escape') { e.stopPropagation(); shut(); }
      else if (e.key === 'ArrowLeft') show(i - 1);
      else if (e.key === 'ArrowRight') show(i + 1);
    };
    function shut() { shade.remove(); document.removeEventListener('keydown', key, true); }
    function show(n) {
      if (n < 0 || n >= items.length) return;
      i = n;
      const m = items[i];
      stage.replaceChildren(h('p', 'Opening…'));
      url(m).then(u => {
        if (!u || i !== n) return;
        stage.replaceChildren(m.isVideo ? h('video', { src: u, controls: true, autoplay: true }) : h('img', { src: u, alt: '' }));
      }, () => stage.replaceChildren(h('p', 'Couldn\'t open this one')));
    }
    stage.addEventListener('click', e => { if (e.target === stage) shut(); });
    let x0 = null;
    stage.addEventListener('pointerdown', e => { x0 = e.clientX; });
    stage.addEventListener('pointerup', e => { if (x0 !== null && Math.abs(e.clientX - x0) > 60) show(i + (e.clientX < x0 ? 1 : -1)); x0 = null; });
    document.addEventListener('keydown', key, true);
    document.body.appendChild(shade);
    show(start);
  }

  connect();
  return {
    el: root,
    leave() {
      document.removeEventListener('visibilitychange', hidden);
      document.querySelectorAll('.gallery-viewer').forEach(n => n.remove());
      wipe();
    },
  };
}

/** The decryption itself, for checking it against payloads the apps wrote. */
export const decryptPayload = decrypt;
