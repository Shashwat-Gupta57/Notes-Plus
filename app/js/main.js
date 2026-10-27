// Notes+ on the web: the notes app (no vaults, no chat), kept in this browser and synced with
// the phone and PC when signed in.
//
//   #/            the notes
//   #/n/<uuid>    a note
//   #/new/<TYPE>  a new note
//   #/account     the account
//   #/s/<token>   a note shared by link (no account needed)
//   #/j/<token>   an invite to a shared note

import * as account from './account.js';
import * as sync from './sync.js';
import { hasModal, closeTop, toast, dialog, button } from './ui.js';
import { homeScreen } from './home.js';
import { accountScreen, VERSION } from './account-ui.js';
import { openEditor } from './editor.js';
import { sharedNoteScreen, joinScreen } from './share-ui.js';
import { forType as textEditors } from './ed-text.js';
import { mindMapEditor } from './ed-mindmap.js';
import { sketchEditor } from './ed-sketch.js';
import { audioEditor } from './ed-audio.js';
import { MINDMAP, SKETCH, AUDIO, KINDS } from './model.js';

const app = document.getElementById('app');
let current = null;
let routing = Promise.resolve();

const implFor = type => (type === MINDMAP ? mindMapEditor : type === SKETCH ? sketchEditor : type === AUDIO ? audioEditor
  : textEditors[type] || textEditors.TEXT);

/** Shows `screen` in place of the current one, leaving the address as it is. */
export function present(make) {
  routing = routing.then(async () => {
    if (current && current.leave) {
      try { await current.leave(); } catch (e) { console.warn(e); }
    }
    current = make(() => route());
    app.replaceChildren(current.el);
    window.scrollTo(0, 0);
  });
}

export function go(hash) {
  if (location.hash === hash) route();
  else location.hash = hash;
}

function route() {
  routing = routing.then(async () => {
    const hash = location.hash || '#/';
    if (current && current.leave) {
      try { await current.leave(); } catch (e) { console.warn(e); }
    }
    current = null;
    let screen = null;
    let m;
    if ((m = /^#\/n\/([0-9a-f]{32})$/.exec(hash))) {
      screen = await openEditor({ uuid: m[1] }, implFor, go);
      if (!screen) { history.replaceState(null, '', '#/'); screen = homeScreen(go); }
    } else if ((m = /^#\/new\/([A-Z]+)$/.exec(hash)) && KINDS.some(k => k.type === m[1])) {
      screen = await openEditor({ type: m[1] }, implFor, go);
    } else if ((m = /^#\/s\/([A-Za-z0-9_-]+)$/.exec(hash))) {
      screen = await sharedNoteScreen(m[1], implFor, go);
    } else if ((m = /^#\/j\/([A-Za-z0-9_-]+)$/.exec(hash))) {
      screen = await joinScreen(m[1], go);
    } else if (hash === '#/talken-admin') {
      location.href = '../talken-admin/';
      return;
    } else if (hash === '#/account') {
      screen = accountScreen(go);
    } else {
      screen = homeScreen(go);
    }
    current = screen;
    app.replaceChildren(screen.el);
    window.scrollTo(0, 0);
  }).catch(e => {
    console.error(e);
    toast('Something went wrong opening that');
  });
}

window.addEventListener('hashchange', route);

document.addEventListener('keydown', e => {
  if (hasModal()) {
    if (e.key === 'Escape') { e.preventDefault(); closeTop(); }
    return;
  }
  if (current && current.onKey && current.onKey(e)) return;
  if (e.key === 'Escape' && (location.hash || '#/') !== '#/') {
    const t = e.target;
    if (t && t.tagName === 'INPUT' && t.type === 'search') return;
    e.preventDefault();
    go('#/');
  }
});

// Leaving the tab (or hiding it): whatever is open saves, and the account hears about it.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') {
    if (current && current.flush) current.flush();
    sync.schedule(300);
  } else {
    sync.schedule(300);
  }
});
window.addEventListener('pagehide', () => { if (current && current.flush) current.flush(); });

const VERSION_URL = 'https://raw.githubusercontent.com/Shashwat-Gupta57/Notes-Plus/master/version.json';

function parseVer(v) {
  return String(v || '').split('.').map(x => parseInt(x, 10) || 0);
}

function isNewer(remote, local) {
  const r = parseVer(remote), l = parseVer(local);
  for (let i = 0; i < Math.max(r.length, l.length); i++) {
    const a = r[i] || 0, b = l[i] || 0;
    if (a !== b) return a > b;
  }
  return false;
}

let updateNotified = false;

async function checkForWebUpdate() {
  try {
    const res = await fetch(`${VERSION_URL}?t=${Date.now()}`, { cache: 'no-cache' });
    if (!res.ok) return;
    const data = await res.json();
    const remoteVer = data.versionName;
    if (remoteVer && isNewer(remoteVer, VERSION) && !updateNotified) {
      updateNotified = true;
      dialog({
        title: `Notes+ v${remoteVer} is available`,
        message: `A new version of Notes+ is out!\n\n${data.changelog ? data.changelog + '\n\n' : ''}Please refresh the page to update to the latest version.\n\n(If a normal refresh doesn't show the new version, perform a hard refresh with Ctrl+F5 or Shift+Refresh.)`,
        buttons: [
          button('Refresh Now', () => location.reload(), 'filled'),
          button('Later', () => closeTop(), 'tonal')
        ]
      });
    }
  } catch (_) {}
}

async function boot() {
  route();
  checkForWebUpdate();
  await account.restore();
  if (account.uid()) {
    sync.startLive();
  }
  account.observe(() => {
    if (account.uid()) sync.startLive(); else sync.stopLive();
    // Signed in from a link ("Sign in to save" / "to join"): back to the link.
    const back = sessionStorage.getItem('np_return');
    if (account.uid() && back) {
      sessionStorage.removeItem('np_return');
      go(back);
    }
  });
  setInterval(() => sync.schedule(0), 5 * 60 * 1000);
  setInterval(checkForWebUpdate, 15 * 60 * 1000);
}

boot();
