// What a link opens: someone's shared note, read-only, for anyone (no account needed), with
// "Save to my notes" for those signed in; and a group note's invite, which a signed-in person
// accepts to join it.

import * as db from './db.js';
import * as sync from './sync.js';
import * as account from './account.js';
import * as links from './links.js';
import { h, iconButton, toast } from './ui.js';
import { newNote, tint, kind, KINDS } from './model.js';

/** Signs in first, then comes back to this link. */
function signInThenBack(go) {
  sessionStorage.setItem('np_return', location.hash);
  go('#/account');
}

function page(go, title, ...content) {
  const toolbar = h('div.toolbar', iconButton('back', 'Notes+', () => go('#/')), h('h1.toolbar-title', title));
  return h('div.editor', toolbar, h('div.editor-body', h('div.page.link-page', ...content)));
}

export async function sharedNoteScreen(token, implFor, go) {
  let link = null;
  try {
    link = links.TOKEN.test(token) ? await links.read(token) : null;
  } catch (e) {
    console.warn(e);
  }
  if (!link || !link.note || !KINDS.some(k => k.type === link.note.type)) {
    return { el: page(go, 'Notes+', h('p.link-message', 'This link doesn\'t open a note any more. Its owner may have stopped sharing it.')) };
  }
  const v = link.note;
  const note = {
    ...newNote(v.type), title: v.title || '', content: v.content || '', data: typeof v.data === 'string' ? v.data : null,
    sketch: typeof v.sketch === 'string' ? v.sketch : null, color: Number(v.color) || 0, timestamp: Number(v.edited) || Date.now(),
  };
  const ctx = {
    note, readOnly: true,
    changed() {}, async putFile() {}, fileUrl: async () => null, save: async () => {},
  };
  const impl = implFor(note.type)(ctx);
  const from = link.fromName ? `@${link.fromName}` : 'someone';

  async function saveCopy() {
    if (!account.uid()) { signInThenBack(go); return; }
    const copy = { ...newNote(note.type), title: note.title, content: note.content, data: note.data, sketch: note.sketch, color: note.color };
    copy.account = account.uid();
    await db.putNote(copy);
    sync.schedule();
    toast('Saved to your notes');
    go('#/n/' + copy.uuid);
  }

  const save = h('button.btn.filled.small', { type: 'button', onClick: saveCopy }, account.uid() ? 'Save to my notes' : 'Sign in to save');
  const toolbar = h('div.toolbar.editor-toolbar', iconButton('back', 'Notes+', () => go('#/')),
    h('span.editor-from', `${kind(note.type).label} from ${from} · read only`), h('div.spacer'), save);
  const root = h('div.editor', toolbar, h('div.editor-body', impl.node));
  const t = tint(note.color, null);
  root.style.setProperty('--editor-bg', t ? `var(--${t})` : 'var(--bg)');
  root.style.setProperty('--card-surface', t ? `var(--${t})` : 'var(--surface)');
  document.title = (note.title || kind(note.type).label) + ' · Notes+';
  return {
    el: root,
    leave() { if (impl.destroy) impl.destroy(); document.title = 'Notes+'; },
  };
}

export async function joinScreen(token, go) {
  if (!links.TOKEN.test(token)) return { el: page(go, 'Join a note', h('p.link-message', 'This invite link isn\'t complete. Ask for it again.')) };
  if (!account.uid()) {
    return {
      el: page(go, 'Join a note',
        h('p.link-message', 'You\'ve been invited to a shared note. Sign in to join it: everyone in it can read and change it.'),
        h('button.btn.filled', { type: 'button', onClick: () => signInThenBack(go) }, 'Sign in to join')),
    };
  }
  let invite = null;
  try {
    await account.load();
    invite = await links.readInvite(token);
  } catch (e) {
    console.warn(e);
  }
  if (!invite || !invite.gid) {
    return { el: page(go, 'Join a note', h('p.link-message', 'This invite link doesn\'t work any more. Ask for a new one.')) };
  }
  const status = h('p.link-message', `@${invite.byName || 'someone'} invited you to “${invite.name || 'a note'}”. Once you join, you can read and change it with everyone in it.`);
  const button = h('button.btn.filled', { type: 'button' }, 'Join');
  const done = text => {
    status.textContent = text;
    button.remove();
  };
  button.addEventListener('click', async () => {
    button.disabled = true;
    try {
      const r = await links.join(token, invite);
      done(r === 'full' ? 'This note already has 20 people, the most it can have.'
        : (r === 'already' ? 'You\'re already in this note. ' : 'You\'re in. ')
          + 'It\'s in your notes now, here and on your phone and computer.');
      if (r !== 'full') sync.schedule(0);
    } catch (e) {
      button.disabled = false;
      toast(String(e.message || '').includes('username') ? e.message : 'Couldn\'t join right now. Try again in a moment.');
    }
  });
  return { el: page(go, 'Join a note', status, button) };
}
