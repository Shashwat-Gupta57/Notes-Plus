// Notes+ on the web: the notes app (no vaults, no chat), kept in this browser and synced with
// the phone and PC when signed in.
//
//   #/            the notes
//   #/n/<uuid>    a note
//   #/new/<TYPE>  a new note
//   #/account     the account

import * as account from './account.js';
import * as sync from './sync.js';
import { hasModal, closeTop, toast } from './ui.js';
import { homeScreen } from './home.js';
import { accountScreen } from './account-ui.js';
import { openEditor } from './editor.js';
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

async function boot() {
  route();
  await account.restore();
  if (account.uid()) {
    sync.startLive();
  }
  account.observe(() => { if (account.uid()) sync.startLive(); else sync.stopLive(); });
  setInterval(() => sync.schedule(0), 5 * 60 * 1000);
}

boot();
