// The notes account, the apps' Account: optional. Signed out, Notes+ on the web keeps
// everything in this browser and never touches the network. Signed in (Google, or email and
// password once the email is verified), notes sync with the phone and the PC.
//
// Firebase is loaded only when an account is used, so the app starts at once without it.

const SDK = 'https://www.gstatic.com/firebasejs/10.12.2/';
const CONFIG = {
  apiKey: 'AIzaSyAPJ5EG0CzDI--yJY9yAVDArzUn3v1tEss',
  authDomain: 'notes-plus-88985.firebaseapp.com',
  databaseURL: 'https://notes-plus-88985-default-rtdb.asia-southeast1.firebasedatabase.app',
  projectId: 'notes-plus-88985',
};
const FLAG = 'np_account';
export const USERNAME = /^[a-z0-9_.]{3,20}$/;

let fb = null;
let loading = null;
let user = null;
const listeners = new Set();

export function observe(f) {
  listeners.add(f);
  return () => listeners.delete(f);
}

function changed() {
  listeners.forEach(f => f(user));
}

/** Firebase, loaded on first use: {auth, db, A (auth module), D (database module)}. */
export function load() {
  if (fb) return Promise.resolve(fb);
  if (!loading) {
    loading = (async () => {
      const [appM, A, D] = await Promise.all([
        import(SDK + 'firebase-app.js'), import(SDK + 'firebase-auth.js'), import(SDK + 'firebase-database.js'),
      ]);
      const app = appM.initializeApp(CONFIG);
      const auth = A.getAuth(app);
      await A.setPersistence(auth, A.browserLocalPersistence);
      const db = D.getDatabase(app);
      fb = { app, auth, db, A, D };
      await new Promise(resolve => {
        let first = true;
        A.onAuthStateChanged(auth, u => {
          user = u;
          if (u) localStorage.setItem(FLAG, '1');
          if (first) { first = false; resolve(); }
          changed();
        });
      });
      return fb;
    })();
  }
  return loading;
}

/** At start: brings an account back from the last visit, if there was one. */
export async function restore() {
  if (localStorage.getItem(FLAG)) {
    try {
      await load();
    } catch (e) {
      console.warn('Account not available offline', e);
    }
  }
}

export const firebase = () => fb;
export const signedIn = () => !!user;
export const email = () => (user ? user.email : null);
export const isGoogle = () => !!user && user.providerData.some(p => p.providerId === 'google.com');
export const verified = () => !!user && (user.emailVerified || isGoogle());
/** The account's id once it may sync (Google, or a verified email); else null. */
export const uid = () => (verified() ? user.uid : null);
export const name = () => (user ? user.displayName : null);
export const photo = () => (user ? user.photoURL : null);

// ------------------------------------------------------------------- sign in

export async function signInWithGoogle() {
  const { auth, A } = await load();
  const provider = new A.GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  await A.signInWithPopup(auth, provider);
  await finishSignIn();
}

export async function signUp(mail, password) {
  const { auth, A } = await load();
  const c = await A.createUserWithEmailAndPassword(auth, mail.trim(), password);
  await A.sendEmailVerification(c.user);
  changed();
}

export async function signIn(mail, password) {
  const { auth, A } = await load();
  await A.signInWithEmailAndPassword(auth, mail.trim(), password);
  await finishSignIn();
}

export async function resetPassword(mail) {
  const { auth, A } = await load();
  await A.sendPasswordResetEmail(auth, mail.trim());
}

export async function resendVerification() {
  const { A } = await load();
  if (user) await A.sendEmailVerification(user);
}

/** After the link in the email: true once the account may sync. */
export async function checkVerified() {
  if (!user) return false;
  await user.reload();
  await user.getIdToken(true);
  user = fb.auth.currentUser;
  if (verified()) await finishSignIn();
  changed();
  return verified();
}

/** Fills the profile the first time, as the apps do. */
async function finishSignIn() {
  const id = uid();
  if (!id) { changed(); return; }
  const { db, D } = fb;
  const base = `users/${id}/profile`;
  const fields = {};
  if (user.displayName) fields.displayName = user.displayName.slice(0, 100);
  if (user.photoURL) fields.photoUrl = user.photoURL.slice(0, 2000);
  const created = await D.get(D.ref(db, base + '/createdAt'));
  if (!created.exists()) fields.createdAt = D.serverTimestamp();
  if (Object.keys(fields).length) await D.update(D.ref(db, base), fields);
  changed();
}

export async function signOut() {
  const { auth, A } = await load();
  await A.signOut(auth);
  localStorage.removeItem(FLAG);
  user = null;
  changed();
}

// -------------------------------------------------------------------- profile

export async function profile() {
  const id = uid();
  const p = { email: email(), displayName: name(), photoUrl: photo(), username: null };
  if (!id) return p;
  const { db, D } = fb;
  const s = await D.get(D.ref(db, `users/${id}/profile`));
  if (s.exists()) {
    const v = s.val();
    p.username = v.username || null;
    if (v.displayName) p.displayName = v.displayName;
  }
  const ph = await D.get(D.ref(db, `profiles/${id}/photo`));
  if (ph.exists() && ph.val().url) p.photoUrl = ph.val().url;
  return p;
}

export function normaliseUsername(raw) {
  let n = (raw || '').trim().toLowerCase();
  if (n.startsWith('@')) n = n.slice(1);
  return USERNAME.test(n) ? n : null;
}

/** Claims a @username (and releases the old one) in one update; the rules refuse a taken one. */
export async function claimUsername(nameWanted, previous) {
  const id = uid();
  if (!id) throw new Error('Not signed in');
  const { db, D } = fb;
  const owner = await D.get(D.ref(db, 'usernames/' + nameWanted));
  if (owner.exists() && owner.val() !== id) throw new Error(`@${nameWanted} is taken. Try another.`);
  const upd = { ['usernames/' + nameWanted]: id, [`users/${id}/profile/username`]: nameWanted };
  if (previous && previous !== nameWanted) upd['usernames/' + previous] = null;
  try {
    await D.update(D.ref(db), upd);
  } catch (e) {
    if (String(e.code || e.message).includes('PERMISSION')) throw new Error(`@${nameWanted} is taken. Try another.`);
    throw e;
  }
}

/** Plain words for what went wrong. */
export function friendly(e) {
  const code = (e && (e.code || e.message)) || '';
  if (code.includes('popup-closed') || code.includes('cancelled-popup')) return '';
  if (code.includes('popup-blocked')) return 'Your browser blocked the sign-in window. Allow pop-ups for this site and try again.';
  if (code.includes('unauthorized-domain')) return 'Google sign-in isn\'t enabled for this address yet.';
  if (code.includes('invalid-credential') || code.includes('wrong-password') || code.includes('user-not-found')) return 'That email and password don\'t match an account.';
  if (code.includes('email-already-in-use')) return 'There\'s already an account with that email. Sign in instead.';
  if (code.includes('weak-password')) return 'Use at least 6 characters for the password.';
  if (code.includes('invalid-email')) return 'That doesn\'t look like an email address.';
  if (code.includes('too-many-requests')) return 'Too many tries. Wait a little, then try again.';
  if (code.includes('network')) return 'You\'re offline. Try again when you\'re connected.';
  return e && e.message ? e.message : 'Something went wrong.';
}

/** A name for this browser, shown in "(conflict, …)" and device lists. */
export function device() {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
    : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS'
    : /Mac OS/.test(ua) ? 'Mac' : /Linux/.test(ua) ? 'Linux' : '';
  return ('Web (' + browser + (os ? ' on ' + os : '') + ')').slice(0, 64);
}
