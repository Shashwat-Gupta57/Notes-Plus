// Visit logs for the admin panel: daily counters (visits once per browser session a day, download-page
// views, download clicks) and one event per page view with where it came from: the IP address and
// what it says about place and network (ipapi.co), the system (OS, browser, device, screen,
// language, time zone), the referrer, a random visitor ID kept in this browser (new or returning),
// and how long the page stayed open. Disclosed in privacy.html. Only admins can read any of it.
//
// <script src="js/telemetry.js" data-quiet> records nothing and only offers window.npContext()
// (the admin panel uses it for its own access log).

(function () {
  const DB_URL = 'https://notes-plus-88985-default-rtdb.asia-southeast1.firebasedatabase.app';
  const today = new Date().toISOString().slice(0, 10); // YYYY-MM-DD, UTC
  const quiet = document.currentScript && document.currentScript.hasAttribute('data-quiet');

  const store = (kind, key, value) => {
    try {
      const s = kind === 'local' ? localStorage : sessionStorage;
      if (value === undefined) return s.getItem(key);
      s.setItem(key, value);
    } catch (_) { /* Storage blocked: nothing remembered. */ }
    return null;
  };
  const cut = (s, n) => String(s == null ? '' : s).slice(0, n);

  /** OS, browser and device from the user agent. */
  function system() {
    const ua = navigator.userAgent || '';
    let os = 'Other', m;
    if ((m = /Windows NT ([\d.]+)/.exec(ua))) os = 'Windows ' + ({ '10.0': '10/11', '6.3': '8.1', '6.2': '8', '6.1': '7' }[m[1]] || m[1]);
    else if ((m = /Android ([\d.]+)/.exec(ua))) os = 'Android ' + m[1].split('.')[0];
    else if ((m = /(?:iPhone|iPad|iPod).*? OS ([\d_]+)/.exec(ua))) os = 'iOS ' + m[1].split('_')[0];
    else if ((m = /Mac OS X ([\d_]+)/.exec(ua))) os = navigator.maxTouchPoints > 1 ? 'iPadOS' : 'macOS ' + m[1].split('_').slice(0, 2).join('.');
    else if (/CrOS/.test(ua)) os = 'ChromeOS';
    else if (/Linux/.test(ua)) os = 'Linux';
    let browser = 'Other';
    for (const [name, re] of [['Edge', /Edg(?:e|A|iOS)?\/(\d+)/], ['Opera', /OPR\/(\d+)/], ['Samsung Internet', /SamsungBrowser\/(\d+)/],
      ['Firefox', /(?:Firefox|FxiOS)\/(\d+)/], ['Chrome', /(?:Chrome|CriOS)\/(\d+)/], ['Safari', /Version\/(\d+).*Safari/]]) {
      if ((m = re.exec(ua))) { browser = name + ' ' + m[1]; break; }
    }
    const device = /iPad|Tablet/i.test(ua) || (/Android/i.test(ua) && !/Mobile/i.test(ua)) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1) ? 'tablet'
      : /Mobi|iPhone|iPod|Android/i.test(ua) ? 'mobile' : 'desktop';
    let tz = '';
    try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (_) { /* old browser */ }
    return {
      os, browser, device,
      screen: `${screen.width}x${screen.height}@${Math.round((window.devicePixelRatio || 1) * 100) / 100}`,
      lang: navigator.language || '', tz,
    };
  }

  /** The IP address and what it says (once per browser session). */
  async function place() {
    const cached = store('session', 'np_geo');
    if (cached) { try { return JSON.parse(cached); } catch (_) { /* fetch again */ } }
    const timeout = ms => new Promise((_, no) => setTimeout(() => no(new Error('timeout')), ms));
    let geo = {};
    try {
      const d = await Promise.race([fetch('https://ipapi.co/json/').then(r => r.json()), timeout(3500)]);
      if (d && d.ip && !d.error) geo = { ip: d.ip, city: d.city || '', region: d.region || '', country: d.country_name || '', cc: d.country_code || '', isp: d.org || '' };
    } catch (_) { /* fall back */ }
    if (!geo.ip) {
      try {
        const d = await Promise.race([fetch('https://api.ipify.org?format=json').then(r => r.json()), timeout(3000)]);
        if (d && d.ip) geo = { ip: d.ip };
      } catch (_) { /* no IP */ }
    }
    if (geo.ip) store('session', 'np_geo', JSON.stringify(geo));
    return geo;
  }

  /** Everything an event carries about where it came from. */
  async function context() {
    let vid = store('local', 'np_vid'), first = false;
    if (!vid) {
      vid = Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
      store('local', 'np_vid', vid);
      first = store('local', 'np_vid') === vid; // Without storage every view would look new: say nothing.
    }
    let ref = '';
    try { if (document.referrer && new URL(document.referrer).host !== location.host) ref = new URL(document.referrer).host; } catch (_) { /* none */ }
    const utm = new URLSearchParams(location.search).get('utm_source') || '';
    return { ...(await place()), ...system(), vid, first, ref: utm ? 'utm:' + utm : ref, page: location.pathname || '/' };
  }
  window.npContext = context;
  if (quiet) return;

  /** Adds one to stats/<path>/<today>. The rules accept exactly +1, so a race retries once. */
  function addOne(path, retry = true) {
    const url = `${DB_URL}/stats/${path}/${today}.json`;
    fetch(url)
      .then(r => (r.ok ? r.json() : null))
      .then(current => fetch(url, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify((typeof current === 'number' ? current : 0) + 1),
      }))
      .then(r => { if (r && !r.ok && retry) setTimeout(() => addOne(path, false), 400 + Math.random() * 800); })
      .catch(() => {});
  }

  /** Logs one event with its context; resolves to the event's key. */
  async function event(type, platform, res, tag) {
    const c = await context();
    const body = { ts: Date.now(), type, platform, res: cut(res, 200), tag };
    for (const [k, v] of Object.entries(c)) if (v !== '' && v != null) body[k] = typeof v === 'string' ? cut(v, 200) : v;
    try {
      const r = await fetch(`${DB_URL}/stats/events.json`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      return r.ok ? (await r.json()).name : null;
    } catch (_) { return null; }
  }

  const ua = navigator.userAgent || '';
  const platform = /Android/i.test(ua) ? 'Android browser' : /iPhone|iPad|iPod/i.test(ua) ? 'iOS browser' : 'Desktop browser';
  const path = location.pathname || '/';
  const isApp = /\/app(\/|$)/.test(path);

  // A page view: counted once per browser session a day, logged once per session per page.
  let seenKey = null;
  const shown = Date.now();
  const counter = isApp ? null : path.includes('download') ? 'web/downloads_page' : 'web/visits';
  if (counter && !store('session', 'np_seen_' + counter + '_' + today)) {
    store('session', 'np_seen_' + counter + '_' + today, '1');
    addOne(counter);
  }
  if (!store('session', 'np_logged_' + path)) {
    store('session', 'np_logged_' + path, '1');
    event(isApp ? 'App open' : path.includes('download') ? 'Page view' : 'Web visit', platform, isApp ? 'web app' : path, 'tag-visit')
      .then(k => { seenKey = k; });
  }

  // How long the page stayed open: written once, the first time it's hidden.
  let timed = false;
  const stamp = () => {
    if (timed || !seenKey || document.visibilityState !== 'hidden') return;
    timed = true;
    fetch(`${DB_URL}/stats/events/${seenKey}/dur.json`, {
      method: 'PUT', keepalive: true, headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(Math.min(86400, Math.round((Date.now() - shown) / 1000))),
    }).catch(() => {});
  };
  document.addEventListener('visibilitychange', stamp); // Also fires when the tab closes.

  // Download clicks: every one counts.
  document.addEventListener('click', e => {
    const a = e.target.closest && e.target.closest('a');
    if (!a) return;
    const href = a.getAttribute('href') || '';
    if (a.hasAttribute('data-version-link') || href.includes('.apk')) {
      addOne('web/downloads/android');
      event('App download', 'Android APK', href.split('/').pop() || 'apk', 'tag-dl');
    } else if (a.hasAttribute('data-desktop-link') || href.includes('.exe') || href.includes('.zip')) {
      addOne('web/downloads/windows');
      event('App download', 'Windows setup', href.split('/').pop() || 'exe', 'tag-dl');
    }
  });
})();
