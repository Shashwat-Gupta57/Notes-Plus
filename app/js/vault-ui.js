// The vault on the web: what the desktop's SetupLockView, UnlockView, GalleryView, ViewerView and
// VaultSettingsView are, in a browser tab. Reached only by the hidden gesture on the notes; nothing
// in the notes mentions it, and it never mentions anything else hidden.
//
// First time: one question, Drive or only this browser. Only this browser (or a Drive account
// with no backup yet): a lock must be set before anything else. A backup already on Drive: it
// opens with its backup password or a vault lock, and brings its vaults and their locks.

import { h, icon, iconButton, toast, dialog, confirm, prompt, choices, snackbar, lockInput, menu, closeAllDialogs } from './ui.js';
import * as store from './vault-store.js';
import * as vc from './vault-crypto.js';
import * as vsync from './vault-sync.js';

const TYPES = ['PIN', 'PASSWORD', 'PATTERN'];
const typeWord = t => (t === 'PIN' ? 'PIN' : t === 'PATTERN' ? 'pattern' : 'password');
const AUTO_LOCK = [[0, 'At once'], [30000, 'After 30 seconds'], [60000, 'After 1 minute']];
const problem = (t, v) => (!v ? 'It can\'t be empty' : t === 'PIN' && !/^\d{4,}$/.test(v) ? 'A PIN needs at least 4 digits'
  : t === 'PASSWORD' && v.length < 6 ? 'Use at least 6 characters' : t === 'PATTERN' && v.length < 4 ? 'Connect at least 4 dots' : null);

export function vaultScreen(close) {
  const body = h('div.vault-body');
  const root = h('div.vault', body);
  let session = null; // {key, keyId, vault}
  let gone = false, syncedOnce = false, lockTimer = null;
  const urls = new Map();

  function wipe() {
    for (const u of urls.values()) URL.revokeObjectURL(u);
    urls.clear();
    if (session && session.key) session.key.fill(0);
    session = null;
    document.querySelectorAll('.vault-viewer, .vault-selbar').forEach(n => n.remove());
    closeAllDialogs();
  }
  function leave() {
    if (gone) return;
    gone = true;
    wipe();
    close();
  }
  // Hidden tab: locked at once, or after the chosen delay (back to the notes, as the phone does).
  const onVisibility = async () => {
    clearTimeout(lockTimer);
    if (document.visibilityState !== 'hidden' || !session) {
      if (document.visibilityState === 'hidden' && !session) leave();
      return;
    }
    const { autoLock } = await store.settings();
    if (!autoLock) leave();
    else lockTimer = setTimeout(leave, autoLock);
  };
  document.addEventListener('visibilitychange', onVisibility);

  const page = (...kids) => body.replaceChildren(h('div.vault-page', ...kids));
  const busyPage = text => page(h('div.vault-center', h('div.spinner'), h('p', text)));

  // ---------------------------------------------------------------- lock entry

  /** Tabs PIN / Password / Pattern and the field: calls `onValue(type, value)`. */
  function lockEntry(startType, onValue) {
    let type = TYPES.includes(startType) ? startType : 'PIN';
    const slot = h('div.vault-lock-input');
    let input;
    const tabs = h('div.tabs');
    const show = () => {
      tabs.querySelectorAll('.tab').forEach((t, i) => t.classList.toggle('on', TYPES[i] === type));
      input = lockInput(type, v => onValue(type, v));
      slot.replaceChildren(input.node);
      setTimeout(() => { const f = slot.querySelector('input'); if (f) f.focus(); }, 30);
    };
    ['PIN', 'Password', 'Pattern'].forEach((label, i) => tabs.appendChild(h('button.tab', { type: 'button', onClick: () => { type = TYPES[i]; show(); } }, label)));
    show();
    const go = h('button.btn.filled', { type: 'button', onClick: () => { const v = input.value(); if (v) onValue(type, v); } }, 'Open');
    return { node: h('div.vault-lock-entry', tabs, slot), go, type: () => type };
  }

  /** A new lock, entered twice; `compulsory`: no way out until it's set. Resolves {type, value}. */
  function setLock(title, message, compulsory) {
    return new Promise(resolve => {
      let first = null, firstType = null, d;
      const entry = lockEntry('PIN', submit);
      function submit(type, v) {
        const p = problem(type, v);
        if (p) { toast(p); return; }
        if (first === null) {
          first = v;
          firstType = type;
          d.box.querySelector('.dialog-title').textContent = 'Once more, to confirm';
          entry.node.querySelector('.tabs').style.display = 'none';
          const slot = entry.node.querySelector('.vault-lock-input');
          const again = lockInput(type, w => submit(type, w));
          slot.replaceChildren(again.node);
          entry.go.onclick = () => { const w = again.value(); if (w) submit(type, w); };
          setTimeout(() => { const f = slot.querySelector('input'); if (f) f.focus(); }, 30);
          return;
        }
        if (first !== v || firstType !== type) {
          toast('Those didn\'t match. Try again.');
          d.close();
          setLock(title, message, compulsory).then(resolve);
          return;
        }
        d.close();
        resolve({ type, value: v });
      }
      entry.go.textContent = 'Next';
      d = dialog({
        title, message, content: h('div', entry.node, h('div.vault-dialog-go', entry.go)), width: 400, dismissable: !compulsory,
        buttons: compulsory ? [] : [{ text: 'Cancel', onClick: () => resolve(null) }], cancel: () => resolve(null),
      });
    });
  }

  // ---------------------------------------------------------------- start

  async function start() {
    const mode = await store.mode();
    const vaults = await store.vaults();
    if (!mode || !vaults.length) firstTime();
    else unlockScreen();
  }

  function firstTime() {
    const option = (ic, color, title, sub, onClick) => h('button.row.vault-choice', { type: 'button', onClick },
      h('span.row-icon', { style: { background: `var(--${color})` } }, icon(ic, 20, '#fff')),
      h('span.row-texts', h('span.row-title', title), h('span.row-sub', sub)), icon('chevron', 22, 'var(--secondary)'));
    page(h('div.toolbar', iconButton('back', 'Back', leave)),
      h('div.vault-center', icon('lock', 44, 'var(--brand)'), h('h2', 'Your vault'),
        h('p', 'Where should it be kept?'),
        h('div.settings-card.vault-choices',
          option('cloud', 'tile-teal', 'Keep it in sync with Google Drive', 'The same vault as on your phone and computer, encrypted on your Drive', chooseDrive),
          option('lock', 'tile-purple', 'Only in this browser', 'Encrypted here, and never leaves this browser', chooseLocal))));
  }

  async function createMain(lock) {
    const bundle = vc.newDataKey();
    const uuid = crypto.randomUUID();
    const v = { uuid, name: 'Main Vault', lockType: lock.type, isMain: true, sortOrder: 0, lockChangedAt: Date.now(),
      bgType: 'DEFAULT', bgValue: null, wrap: await vc.localWrap(bundle, uuid, lock.type, lock.value) };
    await store.putVault(v);
    session = { ...bundle, vault: v };
    await store.setLocks(session, { [uuid]: { type: lock.type, value: lock.value } });
    try { await navigator.storage.persist(); } catch { /* Kept anyway, just evictable. */ }
    return v;
  }

  async function chooseLocal() {
    // The vault opens, and asks for its lock before anything else.
    page(h('div.toolbar', h('h1.toolbar-title', 'Main Vault')), h('div.empty', icon('photo', 64, 'var(--secondary)'), h('p', 'Nothing here yet')));
    const lock = await setLock('Set a lock for your vault', 'It opens your vault, here in this browser. There is no way to open it without this lock.', true);
    const v = await createMain(lock);
    await store.setMode('local');
    gallery(v);
  }

  async function chooseDrive() {
    let email;
    try {
      email = await vsync.signIn(null);
    } catch (e) {
      if (String(e.message) !== 'cancelled') toast(e.message, 4000);
      return;
    }
    busyPage('Looking for your backup…');
    let found;
    try {
      found = await vsync.lookForBackup();
    } catch (e) {
      toast(e.message, 4000);
      firstTime();
      return;
    }
    if (!found) {
      page(h('div.toolbar', h('h1.toolbar-title', 'Main Vault')), h('div.empty', icon('photo', 64, 'var(--secondary)'), h('p', 'Nothing here yet')));
      const lock = await setLock('Set a lock for your vault', 'It opens your vault here, and your backup on Google Drive from your other devices.', true);
      const v = await createMain(lock);
      await store.setMode('drive');
      await store.setDriveEmail(session, email);
      gallery(v);
      runSync(true);
      return;
    }
    openBackup(found.keyring, email);
  }

  /** A backup the phone or computer made: opened with its backup password or a vault lock. */
  function openBackup(keyring, email) {
    const wraps = keyring.wraps || [];
    const backupLock = wraps.some(w => String(w.label || '').startsWith('Backup'));
    const entry = lockEntry(backupLock ? 'PASSWORD' : (wraps[0] && wraps[0].lockType) || 'PIN', async (type, value) => {
      busyPage('Opening your backup…');
      const opened = await vsync.openKeyring(keyring, value);
      if (!opened) {
        toast('That doesn\'t open this backup');
        openBackup(keyring, email);
        return;
      }
      session = { key: opened.key, keyId: opened.keyId, vault: null };
      await store.setKv('passwordWrap', opened.passwordWrap || null);
      await store.setLocks(session, {});
      await store.setMode('drive');
      await store.setDriveEmail(session, email);
      try { await navigator.storage.persist(); } catch { /* Fine. */ }
      const progress = progressDialog('Bringing your vault here');
      try {
        await vsync.sync(session, { password: value, progress: progress.update });
      } catch (e) {
        toast(e.message, 5000);
      } finally {
        progress.close();
      }
      syncedOnce = true;
      const main = await store.mainVault();
      if (!main) {
        toast('That backup has no vault to open yet');
        await store.wipe();
        firstTime();
        return;
      }
      session.vault = main;
      gallery(main);
    });
    page(h('div.toolbar', iconButton('back', 'Back', firstTime)),
      h('div.vault-center', icon('cloud', 44, 'var(--brand)'), h('h2', 'Open your backup'), h('p', vsync.describe(keyring)), entry.node, entry.go));
  }

  // ---------------------------------------------------------------- lock screen

  async function lockout() {
    return (await store.kv('lockout')) || { fails: 0, until: 0 };
  }

  async function unlockScreen(message) {
    const main = await store.mainVault();
    const note = h('p.vault-lock-note', message || '');
    const entry = lockEntry(main ? main.lockType : 'PIN', async (type, value) => {
      const lo = await lockout();
      if (lo.until > Date.now()) {
        note.textContent = `Try again in ${Math.ceil((lo.until - Date.now()) / 1000)} s`;
        return;
      }
      note.textContent = 'Checking…';
      for (const v of await store.vaults()) {
        const bundle = await vc.localUnwrap(v.wrap, v.uuid, type, value);
        if (!bundle) continue;
        await store.setKv('lockout', { fails: 0, until: 0 });
        session = { ...bundle, vault: v };
        gallery(v);
        const s = await store.settings();
        if ((await store.mode()) === 'drive' && s.syncOnUnlock && !syncedOnce) {
          // Google's sign-in opens only from a tap: without a session yet, it's offered.
          if (vsync.hasToken()) runSync(false);
          else snackbar('Sync your vault with Google Drive?', 'Sync', () => runSync(true));
        }
        return;
      }
      const fails = lo.fails + 1;
      const until = fails >= 5 ? Date.now() + Math.min(3600000, 30000 * 2 ** (fails - 5)) : 0;
      await store.setKv('lockout', { fails, until });
      note.textContent = until ? `Try again in ${Math.ceil((until - Date.now()) / 1000)} s` : 'Incorrect';
      unlockScreenEntryReset();
    });
    let unlockScreenEntryReset = () => {
      const f = entry.node.querySelector('input');
      if (f) { f.value = ''; f.focus(); }
    };
    page(h('div.toolbar', iconButton('back', 'Back', leave)),
      h('div.vault-center', icon('lock', 44, 'var(--brand)'), h('h2', 'Enter your lock'), entry.node, entry.go, note));
  }

  // ---------------------------------------------------------------- sync

  function progressDialog(title) {
    const text = h('p.dialog-message', 'Starting…');
    const fill = h('div.progress-fill');
    const d = dialog({ title, content: h('div', text, h('div.progress-track', fill)), dismissable: false });
    return {
      update(message, done, total) {
        text.textContent = message;
        fill.style.width = total ? Math.round(100 * done / total) + '%' : '0';
      },
      close: () => d.close(),
    };
  }

  let syncing = null;
  /** `shown`: a progress dialog; otherwise quietly, as at unlock. Signs in when needed. */
  async function runSync(shown, password = null) {
    if (syncing || !session) return syncing;
    const progress = shown ? progressDialog('Syncing with Google Drive') : null;
    syncing = (async () => {
      try {
        if (!vsync.hasToken()) await vsync.signIn(await store.driveEmail(session));
        const failed = await vsync.sync(session, { password, progress: progress ? progress.update : () => {} });
        syncedOnce = true;
        if (shown) toast(failed ? `Synced; ${failed} item${failed === 1 ? '' : 's'} couldn't be moved this time` : 'Synced');
      } catch (e) {
        if (e instanceof vsync.WrongPassword) {
          if (progress) progress.close();
          askBackupKey(e.message);
          return;
        }
        if (shown && String(e.message) !== 'cancelled') toast(e.message, 5000);
      } finally {
        if (progress) progress.close();
        syncing = null;
        if (session && current) current();
      }
    })();
    return syncing;
  }

  function askBackupKey(message) {
    const entry = lockEntry('PASSWORD', (type, value) => { d.close(); runSync(true, value); });
    const d = dialog({ title: 'Your backup has another key', message: message + ' Its items will be re-encrypted for it here.',
      content: entry.node, buttons: [{ text: 'Cancel' }, entry.go], width: 400 });
    entry.go.addEventListener('click', () => d.close());
  }

  // ---------------------------------------------------------------- gallery

  let current = null; // Redraws what's on screen (after a sync or a change).
  const cell = { size: 150 };
  store.kv('cell', 150).then(n => { cell.size = n || 150; });

  async function thumbUrl(s) {
    if (urls.has('t/' + s.fileName)) return urls.get('t/' + s.fileName);
    let blob = await store.thumb(s.fileName);
    let plain;
    if (blob) {
      plain = await vc.decrypt(blob, session.key, session.keyId, 'image/jpeg');
    } else {
      const m = await store.media(s.fileName);
      if (!m) return null;
      const made = await makeThumb(await vc.decrypt(m, session.key, session.keyId, s.mime || (s.isVideo ? 'video/mp4' : '')), s.isVideo);
      if (!made) return null;
      plain = made.blob;
      await store.putThumb(s.fileName, await vc.encrypt(plain, session.key, session.keyId));
    }
    if (gone || !session) return null;
    const u = URL.createObjectURL(plain);
    urls.set('t/' + s.fileName, u);
    return u;
  }

  async function mediaUrl(s) {
    if (urls.has('m/' + s.fileName)) return urls.get('m/' + s.fileName);
    const m = await store.media(s.fileName);
    if (!m) return null;
    const plain = await vc.decrypt(m, session.key, session.keyId, s.mime || (s.isVideo ? 'video/mp4' : 'image/jpeg'));
    if (gone || !session) return null;
    const u = URL.createObjectURL(plain);
    urls.set('m/' + s.fileName, u);
    return u;
  }

  // Up to three at once, in the order asked.
  const queue = [];
  let running = 0;
  function soon(work, then) {
    queue.push([work, then]);
    pump();
  }
  function pump() {
    while (running < 3 && queue.length) {
      const [work, then] = queue.shift();
      running++;
      work().then(then, () => then(null)).finally(() => { running--; pump(); });
    }
  }

  function dayLabel(t) {
    const d = new Date(t), today = new Date();
    const start = x => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
    const diff = Math.round((start(today) - start(d)) / 86400000);
    if (diff === 0) return 'Today';
    if (diff === 1) return 'Yesterday';
    if (diff < 7 && diff > 0) return d.toLocaleDateString(undefined, { weekday: 'long' });
    return d.toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: d.getFullYear() === today.getFullYear() ? undefined : 'numeric' });
  }

  /** The vault's items: `filter` null (all), "FAV", or an album's uuid. */
  async function gallery(v, filter = null, tab = 'all') {
    if (!session) { unlockScreen(); return; }
    session.vault = v;
    current = () => (tab === 'albums' ? albumsTab(v) : gallery(v, filter, tab));
    if (tab === 'albums') { albumsTab(v); return; }
    const all = (await store.secretsOf(v.uuid)).sort((a, b) => (b.dateTaken || b.dateAdded) - (a.dateTaken || a.dateAdded));
    const albumList = await store.albumsOf(v.uuid);
    const items = all.filter(s => (filter === null ? true : filter === 'FAV' ? s.isFavourite : s.albumUuid === filter));
    const title = filter === null ? v.name : filter === 'FAV' ? 'Favourites' : ((albumList.find(a => a.uuid === filter) || {}).name || 'Album');
    const selected = new Set();
    let anchor = -1;
    const grid = h('div.vault-grid', { style: { '--cell': cell.size + 'px' } });
    const tiles = [];
    const observer = new IntersectionObserver(entries => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        observer.unobserve(e.target);
        const s = e.target._item;
        soon(() => thumbUrl(s), u => { if (u && !gone) e.target.prepend(h('img', { src: u, alt: '', draggable: false })); });
      }
    }, { rootMargin: '600px' });
    let lastDay = null;
    items.forEach((s, i) => {
      const label = dayLabel(s.dateTaken || s.dateAdded);
      if (label !== lastDay) {
        grid.appendChild(h('h3.vault-day', label));
        lastDay = label;
      }
      const tile = h('button.vault-tile', { type: 'button' },
        s.isVideo ? h('span.vault-badge', icon('play', 16, '#fff'), s.durationMs ? h('span', clock(s.durationMs)) : null) : null,
        s.isFavourite ? h('span.vault-fav', icon('star', 16, '#fff')) : null,
        h('span.vault-check', icon('check', 18, '#fff')));
      tile._item = s;
      tile._index = i;
      tiles.push(tile);
      grid.appendChild(tile);
      observer.observe(tile);
    });

    const paintSelection = () => {
      tiles.forEach(t => t.classList.toggle('on', selected.has(t._item.fileName)));
      root.classList.toggle('selecting', selected.size > 0);
      selbar();
    };
    const toggle = (i, on) => {
      const n = items[i].fileName;
      if (on === undefined ? !selected.has(n) : on) selected.add(n);
      else selected.delete(n);
    };
    // Click: open, or (selecting, Ctrl/Cmd) toggle; Shift: a range. Long press: start selecting.
    let pressTimer = null, pressed = false, dragging = false;
    grid.addEventListener('click', e => {
      const t = e.target.closest('.vault-tile');
      if (!t) return;
      if (pressed) { pressed = false; return; }
      const i = t._index;
      if (e.shiftKey && anchor >= 0) {
        for (let k = Math.min(anchor, i); k <= Math.max(anchor, i); k++) toggle(k, true);
      } else if (e.ctrlKey || e.metaKey || selected.size) {
        toggle(i);
        anchor = i;
      } else {
        viewer(v, items, i);
        return;
      }
      paintSelection();
    });
    grid.addEventListener('contextmenu', e => {
      const t = e.target.closest('.vault-tile');
      if (!t) return;
      e.preventDefault();
      toggle(t._index, true);
      anchor = t._index;
      paintSelection();
    });
    grid.addEventListener('pointerdown', e => {
      const t = e.target.closest('.vault-tile');
      if (!t) return;
      if (selected.size && e.pointerType === 'mouse' && e.button === 0) dragging = true;
      if (e.pointerType !== 'mouse') {
        pressTimer = setTimeout(() => { pressed = true; toggle(t._index, true); anchor = t._index; paintSelection(); }, 500);
      }
    });
    grid.addEventListener('pointerover', e => {
      if (!dragging) return;
      const t = e.target.closest('.vault-tile');
      if (t && !selected.has(t._item.fileName)) { toggle(t._index, true); paintSelection(); }
    });
    const endPress = () => { clearTimeout(pressTimer); dragging = false; };
    grid.addEventListener('pointerup', endPress);
    grid.addEventListener('pointercancel', endPress);
    grid.addEventListener('pointermove', e => { if (e.pointerType !== 'mouse') clearTimeout(pressTimer); });
    // Ctrl+wheel (or a trackpad pinch): bigger or smaller cells.
    grid.addEventListener('wheel', e => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      cell.size = Math.max(70, Math.min(420, Math.round(cell.size * (e.deltaY < 0 ? 1.08 : 0.92))));
      grid.style.setProperty('--cell', cell.size + 'px');
      clearTimeout(cell.save);
      cell.save = setTimeout(() => store.setKv('cell', cell.size), 400);
    }, { passive: false });

    function selbar() {
      document.querySelectorAll('.vault-selbar').forEach(n => n.remove());
      if (!selected.size) return;
      const chosen = () => items.filter(s => selected.has(s.fileName));
      const bar = h('div.vault-selbar',
        iconButton('close', 'Stop selecting (Esc)', () => { selected.clear(); paintSelection(); }),
        h('span.vault-selcount', `${selected.size} selected`),
        iconButton('star', 'Favourite', async () => { const c = chosen(); const on = !c.every(s => s.isFavourite); for (const s of c) await store.putSecret({ ...s, isFavourite: on }); gallery(v, filter); }),
        iconButton('album', 'Add to album', () => pickAlbum(v, chosen(), () => gallery(v, filter))),
        iconButton('download', 'Download', () => downloadAll(chosen())),
        iconButton('logout', 'Take out of the vault', () => takeOut(chosen(), () => gallery(v, filter))),
        iconButton('delete', 'Delete', () => deleteWithUndo(chosen(), () => gallery(v, filter))));
      document.body.appendChild(bar);
    }

    keys = e => {
      if (document.querySelector('.vault-viewer') || document.querySelector('.scrim')) return;
      if (e.key === 'Escape') {
        if (selected.size) { selected.clear(); paintSelection(); } else if (filter !== null) gallery(v, null, filter === 'FAV' || albumList.some(a => a.uuid === filter) ? 'albums' : 'all'); else leave();
        e.preventDefault();
      } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
        e.preventDefault();
        items.forEach((s, i) => toggle(i, true));
        paintSelection();
      } else if (e.key === 'Delete' && selected.size) {
        deleteWithUndo(items.filter(s => selected.has(s.fileName)), () => gallery(v, filter));
      }
    };

    const tabs = filter === null ? h('div.tabs.vault-tabs',
      h('button.tab.on', { type: 'button' }, 'All'),
      h('button.tab', { type: 'button', onClick: () => gallery(v, null, 'albums') }, 'Albums')) : null;
    const picker = h('input', { type: 'file', multiple: true, accept: 'image/*,video/*', style: { display: 'none' } });
    picker.addEventListener('change', () => { if (picker.files.length) addFiles(v, [...picker.files], filter); });
    page(
      h('div.toolbar',
        iconButton('back', filter === null ? 'Close (Esc)' : 'Back (Esc)', () => (filter === null ? leave() : gallery(v, null, 'albums'))),
        h('h1.toolbar-title', title), h('span.vault-count', `${items.length} ${items.length === 1 ? 'item' : 'items'}`),
        h('span.vault-spacer'),
        (await store.mode()) === 'drive' ? iconButton('cloud', 'Sync now', () => runSync(true)) : null,
        iconButton('settings', 'Vault settings', () => settingsPage(v))),
      tabs,
      items.length ? grid : h('div.empty', icon('photo', 64, 'var(--secondary)'), h('p', filter === null ? 'Nothing here yet. Add photos and videos with +, or drop them here.' : 'Nothing here.')),
      h('button.fab.new-fab', { type: 'button', onClick: () => picker.click() }, icon('add', 24), 'Add'),
      picker);
    paintSelection();
  }

  let keys = null;
  const onKey = e => keys && keys(e);
  document.addEventListener('keydown', onKey);

  // Drop or paste photos and videos onto the open vault.
  root.addEventListener('dragover', e => { if (session && session.vault) { e.preventDefault(); root.classList.add('dropping'); } });
  root.addEventListener('dragleave', e => { if (e.target === root || !root.contains(e.relatedTarget)) root.classList.remove('dropping'); });
  root.addEventListener('drop', e => {
    root.classList.remove('dropping');
    if (!session || !session.vault) return;
    e.preventDefault();
    const files = [...e.dataTransfer.files].filter(f => /^(image|video)\//.test(f.type));
    if (files.length) addFiles(session.vault, files, null);
  });
  const onPaste = e => {
    if (!session || !session.vault || document.querySelector('.scrim')) return;
    const files = [...(e.clipboardData ? e.clipboardData.files : [])].filter(f => /^(image|video)\//.test(f.type));
    if (files.length) { e.preventDefault(); addFiles(session.vault, files, null); }
  };
  document.addEventListener('paste', onPaste);

  const clock = ms => { const s = Math.round(ms / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };

  // ---------------------------------------------------------------- adding

  async function addFiles(v, files, filter) {
    const progress = progressDialog(files.length === 1 ? 'Adding 1 item' : `Adding ${files.length} items`);
    let added = 0, failed = 0;
    for (const [i, f] of files.entries()) {
      progress.update(`${i + 1} of ${files.length}: ${f.name}`, i, files.length);
      try {
        const video = f.type.startsWith('video/');
        const name = (video ? 'VID_' : 'IMG_') + Date.now() + '_' + vc.hex(vc.rand(4));
        const [sha, sealed, made] = [await vc.sha256(f), await vc.encrypt(f, session.key, session.keyId), await makeThumb(f, video)];
        await store.putMedia(name, sealed);
        if (made) await store.putThumb(name, await vc.encrypt(made.blob, session.key, session.keyId));
        await store.putSecret({
          fileName: name, vaultUuid: v.uuid, albumUuid: filter && filter !== 'FAV' ? filter : null, isVideo: video, isFavourite: filter === 'FAV',
          dateTaken: f.lastModified || Date.now(), dateAdded: Date.now(), originalPath: f.name, sha256: sha, size: sealed.size,
          durationMs: made ? made.durationMs : 0, mime: f.type,
        });
        added++;
      } catch (e) {
        console.warn('Not added', f.name, e);
        failed++;
      }
    }
    progress.close();
    toast(failed ? `Added ${added}; ${failed} couldn't be added` : added === 1 ? 'Added' : `Added ${added} items`);
    gallery(v, filter);
    if ((await store.mode()) === 'drive' && vsync.hasToken()) runSync(false);
  }

  /** A 512 px JPEG of a picture or a video's frame at about 1 s (+ its length): {blob, durationMs}, or null. */
  async function makeThumb(blob, video) {
    const MAX = 512;
    const draw = (src, w, ht) => {
      const k = Math.min(1, MAX / Math.max(w, ht));
      const c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(w * k));
      c.height = Math.max(1, Math.round(ht * k));
      c.getContext('2d').drawImage(src, 0, 0, c.width, c.height);
      return new Promise(r => c.toBlob(r, 'image/jpeg', 0.82));
    };
    try {
      if (!video) {
        const bmp = await createImageBitmap(blob, { imageOrientation: 'from-image' });
        const out = await draw(bmp, bmp.width, bmp.height);
        bmp.close();
        return out ? { blob: out, durationMs: 0 } : null;
      }
      const url = URL.createObjectURL(blob);
      try {
        const el = document.createElement('video');
        el.muted = true;
        el.preload = 'auto';
        el.src = url;
        await new Promise((res, rej) => { el.onloadedmetadata = res; el.onerror = rej; setTimeout(rej, 15000); });
        const durationMs = isFinite(el.duration) ? Math.round(el.duration * 1000) : 0;
        el.currentTime = Math.min(1, (el.duration || 2) / 2);
        await new Promise((res, rej) => { el.onseeked = res; el.onerror = rej; setTimeout(rej, 15000); });
        const out = await draw(el, el.videoWidth, el.videoHeight);
        return out ? { blob: out, durationMs } : { blob: null, durationMs };
      } finally {
        URL.revokeObjectURL(url);
      }
    } catch {
      return null;
    }
  }

  // ---------------------------------------------------------------- acting on items

  const fileNameOf = s => {
    if (s.originalPath) return s.originalPath.split(/[\\/]/).pop();
    return s.fileName + (s.isVideo ? '.mp4' : '.jpg');
  };

  async function downloadAll(list) {
    for (const s of list) {
      const m = await store.media(s.fileName);
      if (!m) continue;
      const plain = await vc.decrypt(m, session.key, session.keyId, s.mime || '');
      const u = URL.createObjectURL(plain);
      const a = h('a', { href: u, download: fileNameOf(s) });
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(u), 30000);
    }
  }

  /** Saved to this computer, then out of the vault (the backup forgets it too). */
  function takeOut(list, then) {
    confirm(list.length === 1 ? 'Take this item out of the vault?' : `Take ${list.length} items out of the vault?`,
      'They are saved to your downloads, then removed from the vault, here and on your other devices.', 'Take out', async () => {
        await downloadAll(list);
        for (const s of list) await store.remove(s.fileName);
        toast(list.length === 1 ? 'Taken out' : `${list.length} taken out`);
        then();
      });
  }

  /** Hidden at once; erased when Undo has gone (5 s), then the deletion reaches the backup. */
  function deleteWithUndo(list, then) {
    const names = new Set(list.map(s => s.fileName));
    document.querySelectorAll('.vault-tile').forEach(t => { if (t._item && names.has(t._item.fileName)) t.classList.add('gone'); });
    document.querySelectorAll('.vault-selbar').forEach(n => n.remove());
    root.classList.remove('selecting');
    snackbar(list.length === 1 ? 'Item deleted' : `${list.length} items deleted`, 'Undo', null, async acted => {
      if (!acted) for (const s of list) await store.remove(s.fileName);
      if (session) then();
    });
  }

  function pickAlbum(v, list, then) {
    store.albumsOf(v.uuid).then(albumList => {
      const put = async uuid => {
        for (const s of list) await store.putSecret({ ...(await store.secret(s.fileName)), albumUuid: uuid });
        toast(uuid ? 'Added to the album' : 'Out of its album');
        then();
      };
      choices('Add to album', [
        ...albumList.map(a => ({ text: a.name, icon: 'album', onClick: () => put(a.uuid) })),
        { text: 'New album…', icon: 'add', onClick: () => newAlbum(v, uuid => put(uuid)) },
        { text: 'No album', icon: 'close', onClick: () => put(null) },
      ]);
    });
  }

  function newAlbum(v, then) {
    prompt({ title: 'New album', hint: 'Album name', ok: 'Create', onOk: async name => {
      const a = { uuid: crypto.randomUUID(), name: name.trim() || 'Album', vaultUuid: v.uuid };
      await store.putAlbum(a);
      then(a.uuid);
    } });
  }

  // ---------------------------------------------------------------- albums

  async function albumsTab(v) {
    current = () => albumsTab(v);
    const items = await store.secretsOf(v.uuid);
    const newestOf = list => list.sort((a, b) => (b.dateTaken || b.dateAdded) - (a.dateTaken || a.dateAdded))[0] || null;
    const favs = items.filter(s => s.isFavourite);
    const albumList = (await store.albumsOf(v.uuid)).sort((a, b) => a.name.localeCompare(b.name));
    const tile = (name, list, onClick, onMenu) => {
      const cover = h('div.vault-album-cover');
      const c = newestOf([...list]);
      if (c) soon(() => thumbUrl(c), u => { if (u) cover.appendChild(h('img', { src: u, alt: '' })); });
      else cover.appendChild(icon('album', 40, 'var(--secondary)'));
      const t = h('button.vault-album', { type: 'button', onClick }, cover, h('span.vault-album-name', name), h('span.vault-album-count', String(list.length)));
      if (onMenu) t.addEventListener('contextmenu', e => { e.preventDefault(); onMenu(t); });
      return t;
    };
    keys = e => {
      if (document.querySelector('.scrim') || document.querySelector('.vault-viewer')) return;
      if (e.key === 'Escape') { e.preventDefault(); gallery(v); }
    };
    page(
      h('div.toolbar', iconButton('back', 'Close (Esc)', leave), h('h1.toolbar-title', v.name), h('span.vault-spacer'),
        iconButton('settings', 'Vault settings', () => settingsPage(v))),
      h('div.tabs.vault-tabs', h('button.tab', { type: 'button', onClick: () => gallery(v) }, 'All'), h('button.tab.on', { type: 'button' }, 'Albums')),
      h('div.vault-albums',
        favs.length ? tile('Favourites', favs, () => gallery(v, 'FAV')) : null,
        ...albumList.map(a => tile(a.name, items.filter(s => s.albumUuid === a.uuid), () => gallery(v, a.uuid), anchor => menu(anchor, [
          { text: 'Rename', onClick: () => prompt({ title: 'Rename album', hint: 'Album name', value: a.name, ok: 'Save', onOk: async n => { await store.putAlbum({ ...a, name: n.trim() || a.name }); albumsTab(v); } }) },
          { text: 'Delete', danger: true, onClick: () => confirm('Delete this album?', 'Items inside will not be deleted.', 'Delete', async () => {
            await store.deleteAlbum(a.uuid);
            await store.markRemoved('album:' + a.uuid);
            albumsTab(v);
          }, true) },
        ]))),
        h('button.vault-album.vault-album-new', { type: 'button', onClick: () => newAlbum(v, () => albumsTab(v)) },
          h('div.vault-album-cover', icon('add', 40, 'var(--secondary)')), h('span.vault-album-name', 'New album'))));
  }

  // ---------------------------------------------------------------- viewer

  function viewer(v, items, start) {
    let i = start;
    const stage = h('div.vault-stage');
    const favBtn = iconButton('starOutline', 'Favourite (F)', () => favourite(), '#fff');
    const top = h('div.vault-viewer-bar',
      iconButton('back', 'Back (Esc)', () => shut(), '#fff'), h('span.vault-viewer-title'), h('span.vault-spacer'),
      favBtn,
      iconButton('info', 'Details', () => details(), '#fff'),
      iconButton('album', 'Album', () => pickAlbum(v, [items[i]], refresh), '#fff'),
      iconButton('download', 'Download', () => downloadAll([items[i]]), '#fff'),
      iconButton('logout', 'Take out of the vault', () => takeOut([items[i]], () => { shut(); gallery(v); }), '#fff'),
      iconButton('delete', 'Delete', () => { const s = items[i]; shut(); deleteWithUndo([s], () => gallery(v)); }, '#fff'));
    const shade = h('div.vault-viewer', stage, top,
      h('button.vault-nav.prev', { type: 'button', 'aria-label': 'Previous', onClick: () => show(i - 1) }, icon('back', 28, '#fff')),
      h('button.vault-nav.next', { type: 'button', 'aria-label': 'Next', onClick: () => show(i + 1) }, icon('chevron', 32, '#fff')));
    const key = e => {
      if (document.querySelector('.scrim')) return;
      if (e.key === 'Escape') { e.stopPropagation(); e.preventDefault(); shut(); }
      else if (e.key === 'ArrowLeft') show(i - 1);
      else if (e.key === 'ArrowRight') show(i + 1);
      else if (e.key.toLowerCase() === 'f' && !e.ctrlKey && !e.metaKey) favourite();
    };
    async function refresh() {
      const fresh = await store.secret(items[i].fileName);
      if (fresh) items[i] = fresh;
      paintFav();
    }
    function paintFav() {
      favBtn.replaceChildren(icon(items[i].isFavourite ? 'star' : 'starOutline', 24, items[i].isFavourite ? 'var(--brand)' : '#fff'));
    }
    async function favourite() {
      const s = { ...(await store.secret(items[i].fileName)) };
      s.isFavourite = !s.isFavourite;
      await store.putSecret(s);
      items[i] = s;
      paintFav();
    }
    function details() {
      const s = items[i];
      const rows = [['Name', fileNameOf(s)], ['Taken', new Date(s.dateTaken || s.dateAdded).toLocaleString()],
        ['Added', new Date(s.dateAdded).toLocaleString()], ['Size', ((s.size || 0) / 1048576).toFixed(1) + ' MB']];
      if (s.durationMs) rows.push(['Length', clock(s.durationMs)]);
      dialog({ title: 'Details', content: h('div.vault-details', ...rows.map(([k, val]) => h('div', h('span', k), h('span', val)))), buttons: [{ text: 'Done' }] });
    }
    function shut() {
      shade.remove();
      document.removeEventListener('keydown', key, true);
      for (const [k, u] of urls) if (k.startsWith('m/')) { URL.revokeObjectURL(u); urls.delete(k); }
    }
    function show(n) {
      if (n < 0 || n >= items.length) return;
      i = n;
      const s = items[i];
      top.querySelector('.vault-viewer-title').textContent = `${i + 1} of ${items.length}`;
      paintFav();
      stage.replaceChildren(h('div.spinner'));
      mediaUrl(s).then(u => {
        if (!u || i !== n) return;
        stage.replaceChildren(s.isVideo ? h('video', { src: u, controls: true, autoplay: true, playsinline: true }) : h('img', { src: u, alt: '' }));
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

  // ---------------------------------------------------------------- settings

  const row = (ic, color, title, sub, onClick, end) => h('button.row', { type: 'button', onClick, disabled: !onClick },
    h('span.row-icon', { style: { background: `var(--${color})` } }, icon(ic, 20, '#fff')),
    h('span.row-texts', h('span.row-title', title), sub ? h('span.row-sub', sub) : null),
    end === undefined ? (onClick ? icon('chevron', 22, 'var(--secondary)') : null) : end);
  const card = (...rows) => h('div.settings-card', ...rows.filter(Boolean));
  const header = t => h('h2.settings-header', t);
  const toggleEnd = on => h('span.vault-toggle' + (on ? '.on' : ''));

  async function lockTaken(exceptUuid, type, value) {
    const locks = await store.locks(session);
    return Object.entries(locks).some(([u, l]) => u !== exceptUuid && l.type === type && l.value === value);
  }

  async function changeLock(v, then) {
    const lock = await setLock('New lock for ' + v.name, 'It replaces this vault\'s lock in this browser' + ((await store.settings()).syncLocks ? ', and on your other devices at their next sync.' : '.'), false);
    if (!lock) return;
    if (await lockTaken(v.uuid, lock.type, lock.value)) { toast('Another vault already uses that lock'); return; }
    const locks = await store.locks(session);
    locks[v.uuid] = { type: lock.type, value: lock.value };
    const next = { ...v, lockType: lock.type, lockChangedAt: Date.now(), wrap: await vc.localWrap(session, v.uuid, lock.type, lock.value) };
    await store.putVault(next);
    await store.setLocks(session, locks);
    toast('Lock changed');
    then(next);
  }

  async function settingsPage(v) {
    current = () => settingsPage(v);
    keys = e => { if (e.key === 'Escape' && !document.querySelector('.scrim')) { e.preventDefault(); gallery(v); } };
    const s = await store.settings();
    const mode = await store.mode();
    const column = h('div.settings');
    column.append(header('This vault'), card(
      row('lock', 'tile-purple', 'Name', v.name, () => prompt({ title: 'Rename vault', hint: 'Vault name', value: v.name, ok: 'Save', onOk: async n => {
        const next = { ...v, name: n.trim() || v.name };
        await store.putVault(next);
        settingsPage(next);
      } })),
      row('lock', 'tile-orange', 'Change lock', `Opens with a ${typeWord(v.lockType)}`, () => changeLock(v, next => settingsPage(next))),
      row('clock', 'tile-blue', 'Lock again', (AUTO_LOCK.find(([ms]) => ms === s.autoLock) || AUTO_LOCK[0])[1] + ' after leaving this tab',
        () => choices('Lock again', AUTO_LOCK.map(([ms, label]) => ({ text: label, onClick: async () => { await store.setSettings({ autoLock: ms }); settingsPage(v); } }))))));

    if (v.isMain) {
      const vaults = await store.vaults();
      const counts = new Map();
      for (const x of await store.secrets()) counts.set(x.vaultUuid, (counts.get(x.vaultUuid) || 0) + 1);
      column.append(header('Vaults'), card(
        ...vaults.map(x => row('lock', x.isMain ? 'tile-purple' : 'tile-gray', x.name, `${counts.get(x.uuid) || 0} items${x.isMain ? ' · main' : ''}`,
          x.isMain ? null : () => vaultOptions(x), x.isMain ? null : undefined)),
        row('add', 'tile-green', 'New vault', 'Its own lock opens it, from the same lock screen', () => newVault(v))));
    }

    if (mode === 'drive') {
      const email = await store.driveEmail(session);
      const last = await store.kv('lastSync');
      column.append(header('Google Drive'), card(
        row('cloud', 'tile-teal', 'Sync now', last ? 'Last synced ' + new Date(last).toLocaleString() : 'Not synced from this browser yet', () => runSync(true)),
        row('clock', 'tile-orange', 'Sync when unlocked', s.syncOnUnlock ? 'On: each time the vault is opened here' : 'Off',
          async () => { await store.setSettings({ syncOnUnlock: !s.syncOnUnlock }); settingsPage(v); }, toggleEnd(s.syncOnUnlock)),
        row('lock', 'tile-purple', 'Sync vault locks across devices', s.syncLocks
          ? 'On: a lock changed on any device with this on becomes the lock everywhere' : 'Off: this browser\'s locks are its own',
          async () => { await store.setSettings({ syncLocks: !s.syncLocks }); settingsPage(v); }, toggleEnd(s.syncLocks)),
        row('download', 'tile-green', 'Restore from Drive', 'Bring back what an earlier restore point held', () => restoreFromDrive(v)),
        row('person', 'tile-gray', 'Google account', email || 'Connected', () => confirm('Disconnect Google Drive?',
          'Everything stays in this browser, and your backup stays on Drive. You can connect again any time.', 'Disconnect', async () => {
            vsync.signOut();
            await store.setMode('local');
            await store.setDriveEmail(session, null);
            settingsPage(v);
          }))),
        h('p.settings-footer', 'Only new items are uploaded. Drive holds them encrypted, in the same backup as your phone\'s and computer\'s.'));
    } else {
      column.append(header('Google Drive'), card(
        row('cloud', 'tile-teal', 'Connect Google Drive', 'Keep this vault in sync with your phone and computer', () => connectDrive(v))));
    }
    column.append(header('This browser'), card(
      row('delete', 'tile-red', 'Remove the vault from this browser', mode === 'drive' ? 'Your backup on Drive stays' : 'Everything in it is erased for good',
        () => confirm('Remove the vault from this browser?', mode === 'drive' ? 'Everything here is erased. Your backup on Google Drive is untouched, and comes back when you connect again.'
          : 'Everything in it is erased for good. Nothing else has a copy.', 'Remove', async () => {
          await store.wipe();
          leave();
        }, true))));
    page(h('div.toolbar', iconButton('back', 'Back (Esc)', () => gallery(v)), h('h1.toolbar-title', 'Vault settings')), h('div.settings-scroll', column));
  }

  async function newVault(main) {
    prompt({ title: 'New vault', hint: 'Vault name', ok: 'Next', onOk: async name => {
      const lock = await setLock('A lock for ' + (name.trim() || 'the new vault'), 'This lock opens it, from the same lock screen.', false);
      if (!lock) return;
      if (await lockTaken(null, lock.type, lock.value)) { toast('Another vault already uses that lock'); return; }
      const uuid = crypto.randomUUID();
      const vaults = await store.vaults();
      await store.putVault({ uuid, name: name.trim() || 'New Vault', lockType: lock.type, isMain: false, sortOrder: vaults.length,
        lockChangedAt: Date.now(), bgType: 'DEFAULT', bgValue: null, wrap: await vc.localWrap(session, uuid, lock.type, lock.value) });
      const locks = await store.locks(session);
      locks[uuid] = { type: lock.type, value: lock.value };
      await store.setLocks(session, locks);
      toast('Vault created');
      settingsPage(main);
    } });
  }

  function vaultOptions(x) {
    choices(x.name, [
      { text: 'Rename', onClick: () => prompt({ title: 'Rename vault', value: x.name, ok: 'Save', onOk: async n => { await store.putVault({ ...x, name: n.trim() || x.name }); current(); } }) },
      { text: 'Change lock', onClick: () => changeLock(x, () => current()) },
      { text: 'Delete vault', danger: true, onClick: () => deleteVault(x) },
    ]);
  }

  async function deleteVault(x) {
    const items = await store.secretsOf(x.uuid);
    const finish = async () => {
      await store.deleteVault(x.uuid);
      await store.markRemoved('vault:' + x.uuid);
      const locks = await store.locks(session);
      delete locks[x.uuid];
      await store.setLocks(session, locks);
      toast('Vault deleted');
      current();
    };
    if (!items.length) {
      confirm('Delete this empty vault?', null, 'Delete', finish, true);
      return;
    }
    dialog({ title: `Delete ${x.name}?`, message: `There are ${items.length} items still inside. What would you like to do?`, buttons: [
      { text: 'Cancel' },
      { text: 'Delete everything', onClick: async () => { for (const s of items) await store.remove(s.fileName); await finish(); } },
      { text: 'Move to main vault, then delete', kind: 'filled', onClick: async () => {
        const main = await store.mainVault();
        for (const s of items) await store.putSecret({ ...s, vaultUuid: main.uuid, albumUuid: null });
        await finish();
      } },
    ], width: 460 });
  }

  async function connectDrive(v) {
    let email;
    try {
      email = await vsync.signIn(null);
    } catch (e) {
      if (String(e.message) !== 'cancelled') toast(e.message, 4000);
      return;
    }
    await store.setMode('drive');
    await store.setDriveEmail(session, email);
    await runSync(true);
    settingsPage((await store.vault(v.uuid)) || v);
  }

  async function restoreFromDrive(v) {
    let points;
    try {
      if (!vsync.hasToken()) await vsync.signIn(await store.driveEmail(session));
      points = await vsync.restorePoints(session);
    } catch (e) {
      if (e instanceof vsync.WrongPassword) { askBackupKey(e.message); return; }
      if (String(e.message) !== 'cancelled') toast(e.message, 4000);
      return;
    }
    if (!points.length) { toast('There are no restore points yet'); return; }
    choices('Restore from Drive', points.slice(0, 30).map(p => ({
      text: `${new Date(p.createdAt).toLocaleString()} · ${p.device || 'Device'} · ${p.itemCount || 0} items`, icon: 'clock',
      onClick: () => dialog({ title: 'Restore this point?', message: 'What it holds comes back here, and to your other devices at their next sync. Nothing here is removed.',
        buttons: [{ text: 'Cancel' },
          { text: 'Use its settings too', onClick: () => doRestore(p, true) },
          { text: 'Restore', kind: 'filled', onClick: () => doRestore(p, false) }], width: 460 }),
    })));
    async function doRestore(p, replace) {
      const progress = progressDialog('Restoring from Google Drive');
      try {
        const failed = await vsync.restore(session, p.id, replace, progress.update);
        toast(failed ? `Restored; ${failed} couldn't come back` : 'Restored');
      } catch (e) {
        toast(e.message, 5000);
      } finally {
        progress.close();
      }
      gallery((await store.vault(v.uuid)) || v);
    }
  }

  start();
  return {
    el: root,
    leave() {
      document.removeEventListener('visibilitychange', onVisibility);
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('paste', onPaste);
      clearTimeout(lockTimer);
      gone = true;
      wipe();
    },
  };
}
