// The account screen (AccountActivity / AccountView): sign in with Google or email, verify the
// email, then profile, @username, sync, Google Drive for pictures, how long locked notes stay
// open, and signing out. Optional: without it, notes live only in this browser.

import * as account from './account.js';
import * as sync from './sync.js';
import * as drive from './drive.js';
import * as lock from './lock.js';
import * as theme from './theme.js';
import { h, icon, iconButton, toast, dialog, prompt, choices, button } from './ui.js';

export const VERSION = '15.2';

function row(ic, color, title, sub, onClick, end) {
  return h('button.row', { type: 'button', onClick, disabled: !onClick },
    h('span.row-icon', { style: { background: `var(--${color})` } }, icon(ic, 20, '#fff')),
    h('span.row-texts', h('span.row-title', title), sub ? (sub instanceof Node ? sub : h('span.row-sub', sub)) : null),
    end === undefined ? (onClick ? icon('chevron', 22, 'var(--secondary)') : null) : end);
}

const card = (...rows) => h('div.settings-card', ...rows);
const header = t => h('h2.settings-header', t);

const LOCK_MINUTES = [0, 1, 5, 15, 30, 60, -1];
const lockLabel = m => (m === 0 ? 'Lock again as soon as I leave the note' : m < 0 ? 'Stay open until this tab closes'
  : `Lock again ${m === 60 ? '1 hour' : m + (m === 1 ? ' minute' : ' minutes')} after leaving`);

export function accountScreen(go) {
  const column = h('div.settings');
  const root = h('div.account', h('div.toolbar', iconButton('back', 'Back (Esc)', () => go('#/')), h('h1.toolbar-title', 'Account')), h('div.settings-scroll', column));
  let busy = false;
  let wasUid = account.uid();

  async function render() {
    if (!account.signedIn()) column.replaceChildren(signedOut());
    else if (!account.uid()) column.replaceChildren(verify());
    else column.replaceChildren(await signedIn());
    column.append(appCard());
  }

  async function act(work, after) {
    if (busy) return;
    busy = true;
    column.classList.add('busy');
    try {
      await work();
      if (after) after();
    } catch (e) {
      const m = account.friendly(e);
      if (m) toast(m, 4500);
    } finally {
      busy = false;
      column.classList.remove('busy');
      render();
    }
  }

  // ------------------------------------------------------------ signed out
  function signedOut() {
    let creating = false;
    const mail = h('input.field', { type: 'email', placeholder: 'Email', autocomplete: 'email' });
    const pass = h('input.field', { type: 'password', placeholder: 'Password', autocomplete: 'current-password' });
    const submit = button('Sign in', () => {
      if (!mail.value.trim() || !pass.value) { toast('Enter your email and a password'); return; }
      act(() => (creating ? account.signUp(mail.value, pass.value) : account.signIn(mail.value, pass.value)));
    });
    const toggle = button('New here? Create an account', () => {
      creating = !creating;
      submit.textContent = creating ? 'Create account' : 'Sign in';
      toggle.textContent = creating ? 'Have an account? Sign in' : 'New here? Create an account';
      pass.autocomplete = creating ? 'new-password' : 'current-password';
    }, 'text');
    pass.addEventListener('keydown', e => { if (e.key === 'Enter') submit.click(); });
    const forgot = button('Forgot your password?', () => {
      if (!mail.value.trim()) { toast('Enter your email first'); return; }
      act(() => account.resetPassword(mail.value), () => toast('Sent. Check your inbox.'));
    }, 'text');
    const google = h('button.btn.google', { type: 'button', onClick: () => act(() => account.signInWithGoogle()) },
      h('span.g', 'G'), 'Continue with Google');
    return h('div.signin',
      h('div.signin-art', icon('cloud', 56, 'var(--brand)')),
      h('h2.signin-title', 'Your notes, everywhere'),
      h('p.signin-text', 'Sign in to sync with Notes+ on your phone and your PC. Optional: without an account, your notes stay in this browser.'),
      google, h('div.or', h('span', 'or')), mail, pass, submit, toggle, forgot);
  }

  // ---------------------------------------------------------------- verify
  function verify() {
    return h('div.signin',
      h('div.signin-art', icon('info', 56, 'var(--brand)')),
      h('h2.signin-title', 'Check your inbox'),
      h('p.signin-text', `We sent a link to ${account.email()}. Open it, then come back here.`),
      button('I\'ve verified it', () => act(async () => {
        if (!(await account.checkVerified())) toast('Not verified yet. Open the link in the email first.');
      })),
      button('Send the link again', () => act(() => account.resendVerification(), () => toast('Sent again')), 'text'),
      button('Use another account', () => act(() => account.signOut()), 'text'));
  }

  // ------------------------------------------------------------- signed in
  async function signedIn() {
    let p = { email: account.email(), displayName: account.name(), photoUrl: account.photo(), username: null };
    try { p = await account.profile(); } catch (e) { console.warn(e); }
    const avatar = p.photoUrl ? h('img.avatar', { src: p.photoUrl, alt: '', referrerpolicy: 'no-referrer' })
      : h('div.avatar', (p.displayName || p.email || '?').trim()[0].toUpperCase());
    const last = sync.lastSync();
    const syncSub = h('span.row-sub', sync.error() ? 'Couldn\'t sync. Tap to try again.'
      : last ? 'Synced ' + new Date(last).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : 'Not synced yet');
    const driveEmail = drive.connectedEmail();
    const driveSub = !driveEmail ? 'Connect to sync pictures and recordings'
      : drive.hasToken() ? 'Connected' : 'Connected: reconnect to sync files this visit';
    return h('div',
      h('div.profile', avatar, h('div.profile-name', p.displayName || p.email || 'Your account'),
        h('div.profile-handle', p.username ? '@' + p.username : p.email || '')),
      header('Sync'),
      card(
        row('cloud', 'tile-blue', 'Notes', syncSub, () => { toast('Syncing…'); sync.run().then(() => render()); },
          h('span.row-action', 'Sync now')),
        row('photo', 'tile-green', 'Pictures and recordings (Google Drive)', driveSub, () => driveOptions()),
        row('folder', 'tile-purple', 'Folders and archive',
          p.syncFoldersArchive ? 'Sync across devices' : 'Stored in this browser only',
          async () => {
            const next = !account.isSyncFoldersArchive();
            await account.setSyncFoldersArchive(next);
            sync.schedule(200);
            render();
          },
          h('span.row-action', account.isSyncFoldersArchive() ? 'On' : 'Off'))),
      header('Profile'),
      card(
        row('person', 'tile-orange', 'Username', p.username ? '@' + p.username : 'Choose one: people find you by it', () => askUsername(p.username)),
        row('lock', 'tile-orange', 'Locked notes', lockLabel(lock.timeoutMinutes()), () => chooseLockTime()),
        row('logout', 'tile-gray', 'Sign out', p.email, () => confirmSignOut())),
      h('p.settings-footer', 'Your notes are stored in the Notes+ cloud and only your account can open them. Pictures and recordings stay in your own Google Drive.'));
  }

  function driveOptions() {
    if (!drive.connectedEmail() || !drive.hasToken()) {
      act(async () => {
        await drive.connect(account.email());
        toast('Google Drive connected');
        sync.schedule(200);
      });
      return;
    }
    dialog({
      title: 'Google Drive',
      message: 'Pictures and recordings in your notes sync through your own Google Drive.',
      buttons: [{ text: 'Done' }, { text: 'Disconnect', onClick: () => { drive.disconnect(); render(); } }],
    });
  }

  function askUsername(current) {
    prompt({
      title: 'Username', message: '3 to 20 letters, numbers, dots or underscores. It\'s how people find you.',
      hint: 'username', value: current || '', ok: 'Save',
      onOk: v => {
        const name = account.normaliseUsername(v);
        if (!name) { toast('Use 3 to 20 letters, numbers, dots or underscores'); return; }
        act(() => account.claimUsername(name, current), () => toast('You\'re @' + name));
      },
    });
  }

  function chooseLockTime() {
    const now = lock.timeoutMinutes();
    choices('Locked notes', LOCK_MINUTES.map(m => ({ text: (m === now ? '✓  ' : '') + lockLabel(m), onClick: () => { lock.setTimeoutMinutes(m); render(); } })),
      'Once unlocked, a locked note stays open for this long after you leave it.');
  }

  function confirmSignOut() {
    dialog({
      title: 'Sign out?',
      message: 'Your notes stay safe in your account. Keep a copy in this browser, or remove them from it?',
      buttons: [
        { text: 'Cancel' },
        { text: 'Remove from this browser', onClick: () => signOut(false) },
        { text: 'Keep a copy', onClick: () => signOut(true) },
      ],
    });
  }

  function signOut(keep) {
    const uid = account.uid();
    act(async () => {
      if (uid) await sync.leave(uid, keep);
      drive.disconnect();
      await account.signOut();
    }, () => toast('Signed out'));
  }

  // -------------------------------------------------------------- the app
  function appCard() {
    return h('div',
      header('Notes+'),
      card(
        row('palette', 'tile-purple', 'Theme', theme.NAMES[Math.max(0, theme.MODES.indexOf(theme.mode()))], () =>
          choices('Theme', theme.MODES.map((m, i) => ({ text: (m === theme.mode() ? '✓  ' : '') + theme.NAMES[i], onClick: () => { theme.set(m); render(); } })))),
        row('info', 'tile-gray', 'About', `Notes+ on the web, version ${VERSION}`, () => dialog({
          title: 'Notes+',
          message: `Version ${VERSION} on the web. Your notes stay in this browser; sign in to sync them with your phone and PC.`,
          buttons: [{ text: 'OK' }],
        })),
        row('download', 'tile-green', 'Get the apps', 'Android and Windows', () => { location.href = '../download.html'; })),
      h('p.settings-footer', 'Flex Studios · notes.talken.in'));
  }

  /** On first becoming able to sync: notes made here can join the account. */
  const off = account.observe(async () => {
    const uid = account.uid();
    if (uid && uid !== wasUid) {
      wasUid = uid;
      const count = await sync.localCount();
      if (count) {
        dialog({
          title: `Add your ${count} note${count === 1 ? '' : 's'} to this account?`,
          message: 'They\'ll sync to your phone and PC. Or keep them only in this browser.',
          buttons: [{ text: 'Keep only here' }, { text: 'Add them', onClick: () => sync.adoptLocal(uid) }],
        });
      }
      sync.startLive();
      sync.schedule(200);
    }
    if (!uid) wasUid = null;
    render();
  });
  render();

  return {
    el: root,
    leave() { off(); },
  };
}
