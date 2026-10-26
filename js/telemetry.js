// Privacy-preserving anonymous telemetry for Notes+
// Tracks only aggregate counters: daily site visits and app download button clicks.
// Zero cookies, zero PII, zero note data.

(function () {
  const DB_URL = 'https://notes-plus-88985-default-rtdb.asia-southeast1.firebasedatabase.app';
  const today = new Date().toISOString().slice(0, 10); // YYYY-MM-DD

  function increment(path) {
    try {
      // Use Firebase REST API with conditional transaction or shallow counter ping
      const key = 'np_v_' + path + '_' + today;
      if (sessionStorage.getItem(key)) return; // Count unique per session per day
      sessionStorage.setItem(key, '1');

      // Send anonymous increment via Firebase REST transaction or shallow post
      const url = `${DB_URL}/stats/${path}/${today}.json`;
      fetch(url)
        .then(r => r.json())
        .then(current => {
          const count = (typeof current === 'number' ? current : 0) + 1;
          fetch(url, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(count)
          }).catch(() => {});
        })
        .catch(() => {});
    } catch (_) {}
  }

  // Record daily web visit
  increment('web/visits');

  // Track download clicks
  document.addEventListener('click', function (e) {
    const a = e.target.closest('a');
    if (!a) return;
    const href = a.getAttribute('href') || '';
    if (a.hasAttribute('data-version-link') || href.includes('.apk')) {
      increment('web/downloads/android');
    } else if (a.hasAttribute('data-desktop-link') || href.includes('.exe') || href.includes('.zip') || href.includes('desktop')) {
      increment('web/downloads/windows');
    }
  });
})();
