// Google Drive, for notes' pictures and recordings: the apps' layout
// (Notes-plus/Attachments/<note uuid>/<file>), with the drive.file scope, so Notes+ sees only
// the files it made. Connecting is optional: without it, files stay in this browser and files
// from other devices show as placeholders.

const CLIENT_ID = '746909983498-4n7m7cne02j9shb3acm6gial0ergi5s9.apps.googleusercontent.com';
const SCOPE = 'https://www.googleapis.com/auth/drive.file';
const API = 'https://www.googleapis.com/drive/v3/files';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3/files';
const FLAG = 'np_drive_email';

let gis = null;
let token = null;
let tokenExpires = 0;
const folders = new Map();

function loadGis() {
  if (gis) return gis;
  gis = new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = 'https://accounts.google.com/gsi/client';
    s.async = true;
    s.onload = () => resolve(window.google.accounts.oauth2);
    s.onerror = () => reject(new Error('Couldn\'t reach Google'));
    document.head.appendChild(s);
  });
  return gis;
}

export const connectedEmail = () => localStorage.getItem(FLAG);
export const hasToken = () => !!token && Date.now() < tokenExpires - 60000;

/** Asks Google for Drive (a popup). Needs a click to start it. */
export async function connect(hint) {
  const oauth = await loadGis();
  const answer = await new Promise((resolve, reject) => {
    const client = oauth.initTokenClient({
      client_id: CLIENT_ID,
      scope: SCOPE,
      hint,
      callback: r => (r && r.access_token ? resolve(r) : reject(new Error(r && r.error ? r.error : 'cancelled'))),
      error_callback: e => reject(new Error(e && e.type === 'popup_closed' ? 'cancelled' : (e && e.message) || 'cancelled')),
    });
    client.requestAccessToken({ prompt: connectedEmail() ? '' : 'consent' });
  });
  if (!oauth.hasGrantedAllScopes(answer, SCOPE)) {
    throw new Error('Notes+ needs the Google Drive box ticked on Google\'s page. Try again and leave it ticked.');
  }
  token = answer.access_token;
  tokenExpires = Date.now() + (Number(answer.expires_in) || 3600) * 1000;
  localStorage.setItem(FLAG, hint || 'connected');
}

export function disconnect() {
  if (token && window.google) window.google.accounts.oauth2.revoke(token, () => {});
  token = null;
  tokenExpires = 0;
  localStorage.removeItem(FLAG);
  folders.clear();
}

async function call(url, init = {}) {
  if (!hasToken()) throw new Error('drive-token');
  const r = await fetch(url, { ...init, headers: { ...(init.headers || {}), Authorization: 'Bearer ' + token } });
  if (r.status === 401) {
    token = null;
    throw new Error('drive-token');
  }
  if (!r.ok) throw new Error('Drive: HTTP ' + r.status);
  return r;
}

const q = s => s.replace(/\\/g, '\\\\').replace(/'/g, "\\'");

async function find(name, parent, folder) {
  const query = `name = '${q(name)}' and '${q(parent)}' in parents and trashed = false`
    + (folder ? " and mimeType = 'application/vnd.google-apps.folder'" : '');
  const r = await call(`${API}?spaces=drive&fields=files(id)&orderBy=createdTime%20desc&q=${encodeURIComponent(query)}`);
  const files = (await r.json()).files || [];
  return files.length ? files[0].id : null;
}

async function folder(name, parent) {
  const k = parent + '/' + name;
  if (folders.has(k)) return folders.get(k);
  let id = await find(name, parent, true);
  if (!id) {
    const r = await call(API + '?fields=id', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, mimeType: 'application/vnd.google-apps.folder', parents: [parent] }),
    });
    id = (await r.json()).id;
  }
  folders.set(k, id);
  return id;
}

/** Notes-plus/Attachments/<uuid>. */
export async function noteFolder(uuid) {
  return folder(uuid, await folder('Attachments', await folder('Notes-plus', 'root')));
}

export async function upload(blob, name, mime, parent) {
  const boundary = 'np' + Math.random().toString(16).slice(2);
  const meta = JSON.stringify({ name, parents: [parent] });
  const body = new Blob([
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n`,
    `--${boundary}\r\nContent-Type: ${mime || 'application/octet-stream'}\r\n\r\n`, blob, `\r\n--${boundary}--`,
  ]);
  const r = await call(UPLOAD + '?uploadType=multipart&fields=id', {
    method: 'POST', headers: { 'Content-Type': 'multipart/related; boundary=' + boundary }, body,
  });
  return (await r.json()).id;
}

export async function download(id) {
  const r = await call(`${API}/${encodeURIComponent(id)}?alt=media`);
  return r.blob();
}

export async function remove(id) {
  try {
    await call(`${API}/${encodeURIComponent(id)}`, { method: 'DELETE' });
  } catch (e) {
    if (!String(e.message).includes('404')) throw e;
  }
}
