// Links that open a note in any browser (no account needed to read it, one to save it), and
// group notes' invite links (whoever opens one, signed in, joins). The same records as the apps:
//
//   links/{token}            {from, fromName, uuid, at, note: {type, title, content, data, sketch, color, edited}}
//                            readable by anyone with the token, never listable
//   users/{uid}/links/{uuid} = token: which of my notes have a link; each push publishes it again
//   groupInvites/{token}     {gid, by, byName, name, at}; groupNotes/{gid}/invite = token

import * as account from './account.js';
import { dialog, toast } from './ui.js';
import { AUDIO } from './model.js';
import * as lock from './lock.js';

export const VIEW = location.origin + '/app/#/s/';
export const JOIN = location.origin + '/app/#/j/';
export const TOKEN = /^[A-Za-z0-9_-]{20,40}$/;

const tokens = new Map();
let loadedFor = null;

export function newToken() {
  const raw = crypto.getRandomValues(new Uint8Array(18));
  return btoa(String.fromCharCode(...raw)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Words and structure only: not someone else's, not a recording, not locked. */
export const shareable = n => !n.sharedFrom && !n.groupId && n.type !== AUDIO && !lock.isLocked(n.data);

/** Which of my notes have links (once per sync pass). */
export async function refresh(uid) {
  const { db, D } = account.firebase();
  const s = await D.get(D.ref(db, `users/${uid}/links`));
  tokens.clear();
  s.forEach(c => { if (typeof c.val() === 'string') tokens.set(c.key, c.val()); });
  loadedFor = uid;
}

function noteValue(n) {
  const v = { type: n.type, title: n.title || '', content: n.content || '', color: n.color || 0, edited: n.timestamp };
  if (n.data !== null && n.data !== undefined) v.data = n.data;
  if (n.sketch) v.sketch = n.sketch;
  return v;
}

async function myName(uid) {
  const { db, D } = account.firebase();
  const name = (await D.get(D.ref(db, `users/${uid}/profile/username`))).val();
  if (!name) throw new Error('Choose a username in your account first');
  return name;
}

export async function create(uid, n) {
  const { db, D } = account.firebase();
  const token = newToken();
  await D.update(D.ref(db), {
    ['links/' + token]: { from: uid, fromName: await myName(uid), uuid: n.uuid, at: D.serverTimestamp(), note: noteValue(n) },
    [`users/${uid}/links/${n.uuid}`]: token,
  });
  tokens.set(n.uuid, token);
  return VIEW + token;
}

export async function stop(uid, uuid) {
  const { db, D } = account.firebase();
  let token = tokens.get(uuid);
  if (!token) token = (await D.get(D.ref(db, `users/${uid}/links/${uuid}`))).val();
  const update = { [`users/${uid}/links/${uuid}`]: null };
  if (token) update['links/' + token] = null;
  await D.update(D.ref(db), update);
  tokens.delete(uuid);
}

/** After a push (or a delete) of one of my notes: its link, if it has one, follows. Never throws. */
export async function afterPush(uid, n) {
  const token = tokens.get(n.uuid);
  if (!token || loadedFor !== uid) return;
  try {
    if (n.deleted || !shareable(n)) await stop(uid, n.uuid);
    else {
      const { db, D } = account.firebase();
      await D.update(D.ref(db, 'links/' + token), { note: noteValue(n), at: D.serverTimestamp() });
    }
  } catch (e) {
    console.warn('The link catches up on the next sync', e);
  }
}

/** A shared note, by its link's token: {fromName, note} or null. Needs no account. */
export async function read(token) {
  const { db, D } = await account.load();
  const s = await D.get(D.ref(db, 'links/' + token));
  return s.exists() ? s.val() : null;
}

/** An invite, by its token (signed in): {gid, byName, name} or null. */
export async function readInvite(token) {
  const { db, D } = account.firebase();
  const s = await D.get(D.ref(db, 'groupInvites/' + token));
  return s.exists() ? s.val() : null;
}

/** Joins the invite's group note. Resolves to 'joined', 'already' or 'full'. */
export async function join(token, invite) {
  const uid = account.uid();
  const { db, D } = account.firebase();
  const name = await myName(uid);
  const gid = invite.gid;
  try {
    if ((await D.get(D.ref(db, `groupNotes/${gid}/members/${uid}`))).exists()) return 'already';
  } catch { /* Not readable: not a member yet. */ }
  for (let attempt = 0; attempt < 4; attempt++) {
    const size = Number((await D.get(D.ref(db, `groupNotes/${gid}/size`))).val()) || 0;
    if (size >= 20) return 'full';
    try {
      await D.update(D.ref(db), {
        [`groupNotes/${gid}/joins/${uid}`]: token,
        [`groupNotes/${gid}/members/${uid}`]: name,
        [`groupNotes/${gid}/size`]: size + 1,
        [`groupIndex/${uid}/${gid}`]: { at: D.serverTimestamp() },
      });
      return 'joined';
    } catch (e) {
      // Someone joined at the same moment (the size moved on), or the link was stopped.
      console.warn('Join', e);
    }
  }
  throw new Error('Couldn\'t join right now');
}

/** The owner's sheet: make a link, or copy or stop the one there is. */
export async function sheet(n) {
  const uid = account.uid();
  if (!uid) { toast('Sign in to your account to share notes by link'); return; }
  if (!shareable(n)) {
    toast(lock.isLocked(n.data) ? 'A locked note can\'t be shared by link' : 'Recordings can\'t be shared by link yet');
    return;
  }
  try {
    await account.load();
    if (loadedFor !== uid) await refresh(uid);
  } catch {
    toast('Couldn\'t reach your account. Try again when you\'re online.');
    return;
  }
  const token = tokens.get(n.uuid);
  const copy = async url => {
    try { await navigator.clipboard.writeText(url); toast('Link copied'); } catch { toast(url, 6000); }
  };
  const fail = e => toast(String(e.message || '').includes('username') ? e.message : 'Couldn\'t do that. Check your connection and try again.');
  if (!token) {
    dialog({
      title: 'Share by link',
      message: 'Anyone with the link can read this note in their browser, without an account, and always sees your latest version. To keep a copy they sign in. Pictures and files stay in the apps for now.',
      buttons: [{ text: 'Cancel' }, { text: 'Create link', onClick: () => create(uid, n).then(copy, fail) }],
    });
  } else {
    const url = VIEW + token;
    dialog({
      title: 'Shared by link',
      message: url + '\n\nAnyone with this link can read the note.',
      buttons: [
        { text: 'Stop sharing', onClick: () => stop(uid, n.uuid).then(() => toast('The link no longer opens this note'), fail) },
        { text: 'Close' },
        { text: 'Copy link', onClick: () => copy(url) },
      ],
    });
  }
}
