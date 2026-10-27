// Privacy-preserving anonymous telemetry for Notes+
// Tracks aggregate counters: daily site visits, download button clicks, and anonymous telemetry stream.
// Zero cookies, zero PII, zero note data.

(function () {
  const DB_URL = 'https://notes-plus-88985-default-rtdb.asia-southeast1.firebasedatabase.app';
  const today = new Date().toISOString().slice(0, 10); // YYYY-MM-DD

  let clientIp = sessionStorage.getItem('np_client_ip') || '';
  if (!clientIp) {
    fetch('https://api.ipify.org?format=json')
      .then(r => r.json())
      .then(d => {
        if (d && d.ip) {
          clientIp = d.ip;
          sessionStorage.setItem('np_client_ip', clientIp);
        }
      })
      .catch(() => {});
  }

  function recordLocalEvent(event) {
    try {
      const KEY = 'np_telemetry_events_v1';
      const existing = JSON.parse(localStorage.getItem(KEY) || '[]');
      existing.unshift(event);
      if (existing.length > 50) existing.length = 50;
      localStorage.setItem(KEY, JSON.stringify(existing));
    } catch (_) {}
  }

  function increment(path, eventDetails) {
    try {
      const sessKey = 'np_v_' + path + '_' + today;
      const isNewSession = !sessionStorage.getItem(sessKey);
      if (isNewSession) {
        sessionStorage.setItem(sessKey, '1');
      }

      // Record in local stream
      if (eventDetails) {
        recordLocalEvent({
          time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
          ts: Date.now(),
          type: eventDetails.type,
          platform: eventDetails.platform,
          res: eventDetails.res,
          ip: clientIp || eventDetails.ip || '',
          tag: eventDetails.tag || 'tag-visit'
        });
      }

      // Increment Firebase counter if new session or action
      const url = `${DB_URL}/stats/${path}/${today}.json`;
      fetch(url)
        .then(r => r.json())
        .then(current => {
          if (current && typeof current === 'object' && current.error) return;
          const count = (typeof current === 'number' ? current : 0) + 1;
          fetch(url, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(count)
          }).catch(() => {});
        })
        .catch(() => {});

      // Record event log in Firebase
      if (eventDetails) {
        const evUrl = `${DB_URL}/stats/events.json`;
        fetch(evUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            ts: Date.now(),
            time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
            type: eventDetails.type,
            platform: eventDetails.platform,
            res: eventDetails.res,
            ip: clientIp || eventDetails.ip || '',
            tag: eventDetails.tag || 'tag-visit'
          })
        }).catch(() => {});
      }
    } catch (_) {}
  }

  // Detect platform
  const ua = navigator.userAgent || '';
  const isMobile = /Android|iPhone|iPad|iPod/i.test(ua);
  const platformName = isMobile ? (ua.includes('Android') ? 'Android Browser' : 'iOS Browser') : 'Web Desktop';

  // Record daily web visit
  const isDownloadPage = window.location.pathname.includes('download');
  if (isDownloadPage) {
    increment('web/downloads_page', {
      type: 'Page View',
      platform: platformName,
      res: 'notes.talken.in/download',
      tag: 'tag-visit'
    });
  } else {
    increment('web/visits', {
      type: 'Web Visit',
      platform: platformName,
      res: 'notes.talken.in/',
      tag: 'tag-visit'
    });
  }

  // Track download clicks
  document.addEventListener('click', function (e) {
    const a = e.target.closest('a');
    if (!a) return;
    const href = a.getAttribute('href') || '';
    if (a.hasAttribute('data-version-link') || href.includes('.apk')) {
      increment('web/downloads/android', {
        type: 'App Download',
        platform: 'Android APK',
        res: href.split('/').pop() || 'Notes-Plus.apk',
        tag: 'tag-dl'
      });
    } else if (a.hasAttribute('data-desktop-link') || href.includes('.exe') || href.includes('.zip') || href.includes('desktop')) {
      increment('web/downloads/windows', {
        type: 'App Download',
        platform: 'Windows Setup',
        res: href.split('/').pop() || 'Notes-Plus-Windows.exe',
        tag: 'tag-dl'
      });
    }
  });
})();
