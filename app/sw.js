// Notes+ Service Worker (PWA v16.4)
const CACHE_NAME = 'notes-plus-pwa-v16.4';

const SHELL_ASSETS = [
  './',
  './index.html',
  './app.css',
  './manifest.webmanifest',
  './js/main.js',
  './js/account-ui.js',
  './js/account.js',
  './js/sync.js',
  './js/model.js',
  './js/ui.js',
  './js/theme.js',
  './js/lock.js',
  './js/drive.js',
  './js/editor.js',
  './js/ed-text.js',
  './js/md.js',
  './js/rich.js',
  './js/ed-mindmap.js',
  './js/ed-sketch.js',
  './js/ed-audio.js',
  './js/home.js',
  './js/share-ui.js',
  '../res/notes_icon.png',
  '../res/icon-192.png',
  '../res/icon-512.png',
  '../res/apple-touch-icon.png'
];

// Domains and endpoints that must NEVER be cached by the service worker
const NETWORK_ONLY_PATTERNS = [
  'firebasedatabase.app',
  'identitytoolkit.googleapis.com',
  'googleapis.com',
  'releases.talken.in',
  'version.json',
  '/feedback',
  'ipapi.co',
  'ipify.org'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      return cache.addAll(SHELL_ASSETS).catch(err => {
        console.warn('[PWA SW] Pre-cache partial warning:', err);
      });
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys => {
      return Promise.all(
        keys.map(key => {
          if (key !== CACHE_NAME) {
            console.log('[PWA SW] Removing old cache:', key);
            return caches.delete(key);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const req = event.request;
  const url = req.url;

  // Non-GET requests (PUT, POST, DELETE) go direct to network
  if (req.method !== 'GET') {
    return;
  }

  // Network-only endpoints (telemetry, live RTDB, auth, version feeds)
  if (NETWORK_ONLY_PATTERNS.some(pat => url.includes(pat))) {
    return;
  }

  // HTML navigation requests: Network-first, fallback to cached index.html
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).catch(() => {
        return caches.match('./index.html') || caches.match('./');
      })
    );
    return;
  }

  // Static assets: Stale-While-Revalidate
  event.respondWith(
    caches.match(req).then(cached => {
      const fetchPromise = fetch(req).then(res => {
        if (res && res.status === 200 && res.type === 'basic') {
          const clone = res.clone();
          caches.open(CACHE_NAME).then(cache => cache.put(req, clone));
        }
        return res;
      }).catch(err => {
        // Network error; if cached exists, it will be used
        return cached;
      });

      return cached || fetchPromise;
    })
  );
});
