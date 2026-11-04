// Notes+ service worker: the web app opens, reads and edits with no network at all once it has been
// opened online. Notes live in IndexedDB (db.js); this keeps everything the app needs to start.
//
//   The app's own files   pre-cached under CACHE_NAME, looked up without their ?v= (index.html
//                         asks for main.js?v=17.0), refreshed in the background when online.
//   Firebase SDK, fonts   cached on first use and served from the cache from then on: the SDK URL
//                         is pinned to a version and never changes, and with it cached a signed-in
//                         person's account comes back offline (Firebase keeps the session itself).
//   Pages                 the network first, the cached index.html when it can't be reached.
//   Live data             database, sign-in, Drive, version.json: never cached.
//
// Bump CACHE_NAME with every release (docs/Push to production.md), so the new files replace the old.
const CACHE_NAME = 'notes-plus-pwa-v19.4';

const SHELL_ASSETS = [
  './',
  './index.html',
  './app.css',
  './manifest.webmanifest',
  ...['account-ui', 'account', 'db', 'drive', 'due', 'ed-audio', 'ed-mindmap', 'ed-sketch', 'ed-text', 'editor',
    'gallery', 'home', 'importexport', 'interchange', 'links', 'notelinks', 'lock', 'main', 'md', 'mindmap-draw', 'model', 'palette', 'rich', 'share-ui',
    'masonry', 'reminders', 'search', 'sync', 'templates', 'theme', 'ui', 'zip'].map(m => `./js/${m}.js`),
  '../res/notes_icon.png',
  '../res/icon-192.png',
  '../res/icon-512.png',
  '../res/icon-maskable-192.png',
  '../res/icon-maskable-512.png',
  '../res/apple-touch-icon.png',
];

// Kept once fetched: versioned and unchanging.
const KEEP = ['https://www.gstatic.com/firebasejs/', 'https://fonts.googleapis.com/', 'https://fonts.gstatic.com/'];

// Never cached: live data and anything that must be fresh.
const NETWORK_ONLY = [
  'firebasedatabase.app',
  'identitytoolkit.googleapis.com',
  'securetoken.googleapis.com',
  'www.googleapis.com',
  'accounts.google.com',
  'apis.google.com',
  'releases.talken.in',
  'version.json',
  '/feedback',
  'telemetry',
  'ipapi.co',
  'ipify.org',
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      // One by one, so a single missing file doesn't leave the whole cache empty.
      .then(cache => Promise.all(SHELL_ASSETS.map(u => cache.add(u).catch(e => console.warn('[sw] not cached', u, e)))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const req = event.request;
  const url = req.url;
  if (req.method !== 'GET' || !url.startsWith('http')) return;
  if (NETWORK_ONLY.some(p => url.includes(p))) return;

  if (KEEP.some(p => url.startsWith(p))) {
    event.respondWith(caches.match(req).then(hit => hit || fetch(req).then(res => {
      if (res && (res.ok || res.type === 'opaque')) {
        const copy = res.clone();
        caches.open(CACHE_NAME).then(c => c.put(req, copy));
      }
      return res;
    })));
    return;
  }

  const sameOrigin = new URL(url).origin === self.location.origin;
  if (req.mode === 'navigate') {
    event.respondWith(fetch(req).catch(async () =>
      (await caches.match('./index.html', { ignoreSearch: true })) || (await caches.match('./', { ignoreSearch: true }))
      || new Response('Offline', { status: 503, headers: { 'Content-Type': 'text/plain' } })));
    return;
  }
  if (!sameOrigin) return;

  // The app's own files: from the cache at once, refreshed in the background.
  event.respondWith(caches.match(req, { ignoreSearch: true }).then(cached => {
    const fresh = fetch(req).then(res => {
      if (res && res.ok && res.type === 'basic') {
        const copy = res.clone();
        caches.open(CACHE_NAME).then(c => c.put(req, copy));
      }
      return res;
    }).catch(() => cached || new Response('', { status: 504 }));
    if (cached) event.waitUntil(fresh.catch(() => {}));
    return cached || fresh;
  }));
});

// A reminder clicked: the app forward (or opened), on that list.
self.addEventListener('notificationclick', event => {
  event.notification.close();
  const uuid = event.notification.data && event.notification.data.uuid;
  const url = new URL('./' + (uuid ? '#/n/' + uuid : ''), self.registration.scope).href;
  event.waitUntil((async () => {
    const open = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of open) {
      if (c.url.startsWith(self.registration.scope)) {
        await c.focus();
        if (uuid) c.navigate(url).catch(() => {});
        return;
      }
    }
    await self.clients.openWindow(url);
  })());
});
