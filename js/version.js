/* ============================================
   NOTES+ WEBSITE — VERSION FETCHER
   ============================================ */

const VERSION_URL =
  'https://raw.githubusercontent.com/Shashwat-Gupta57/Notes-Plus/master/version.json';

/**
 * Fetches version.json and updates every element that opts in:
 *   [data-version-text]   → inner text set to "Download v{versionName}"
 *   [data-version-link]   → href set to apkUrl
 *   [data-version-name]   → inner text set to versionName
 *   [data-version-code]   → inner text set to versionCode
 *   [data-changelog]      → inner text set to changelog
 *
 * Windows, once version.json has a "desktop" entry ({versionName, url, sha256, size,
 * changelog}); until then every page keeps its "coming soon" content:
 *   [data-desktop-banner] / [data-desktop-available] → shown
 *   [data-desktop-hide] / [data-desktop-soon]        → hidden
 *   [data-desktop-status]   → "Windows v{versionName}"
 *   [data-desktop-pill]     → turns green; [data-desktop-pill-text] → "Out now · v…"
 *   [data-desktop-cta]      → "Download for Windows"
 *   [data-desktop-link]     → href set to the download
 *   [data-desktop-version] / [data-desktop-sha] / [data-desktop-size] / [data-desktop-changelog]
 */
async function loadVersionInfo() {
  try {
    const res = await fetch(VERSION_URL, { cache: 'no-cache' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();

    // Download button text
    document.querySelectorAll('[data-version-text]').forEach(el => {
      el.textContent = `Download v${data.versionName}`;
    });

    // Download links — use direct redirect for reliable mobile downloads
    document.querySelectorAll('[data-version-link]').forEach(el => {
      el.setAttribute('href', data.apkUrl);
      el.addEventListener('click', (e) => {
        e.preventDefault();
        window.location.href = data.apkUrl;
      });
    });

    // Plain version name
    document.querySelectorAll('[data-version-name]').forEach(el => {
      el.textContent = `v${data.versionName}`;
    });

    // Version code
    document.querySelectorAll('[data-version-code]').forEach(el => {
      el.textContent = data.versionCode;
    });

    // Changelog
    document.querySelectorAll('[data-changelog]').forEach(el => {
      el.textContent = data.changelog;
    });

    document.querySelectorAll('[data-apk-sha]').forEach(el => {
      el.textContent = data.sha256 || '';
    });

    if (data.desktop && data.desktop.url) showDesktop(data.desktop);
    return data;
  } catch (err) {
    console.warn('Notes+ version fetch failed:', err);

    // Fallback text
    document.querySelectorAll('[data-version-text]').forEach(el => {
      el.textContent = 'Download APK';
    });
    document.querySelectorAll('[data-version-name]').forEach(el => {
      el.textContent = '';
    });

    return null;
  }
}

/** Notes+ for Windows is out: every "coming soon" turns into its download. */
function showDesktop(d) {
  const each = (selector, fn) => document.querySelectorAll(selector).forEach(fn);
  each('[data-desktop-banner], [data-desktop-available]', el => { el.hidden = false; });
  each('[data-desktop-hide], [data-desktop-soon]', el => { el.hidden = true; });
  each('[data-desktop-status]', el => { el.textContent = `Windows v${d.versionName}`; });
  each('[data-desktop-pill]', el => el.classList.add('live'));
  each('[data-desktop-pill-text]', el => { el.textContent = `Out now · v${d.versionName}`; });
  each('[data-desktop-cta]', el => { el.textContent = 'Download for Windows'; });
  each('[data-desktop-link]', el => el.setAttribute('href', d.url));
  each('[data-desktop-version]', el => { el.textContent = `v${d.versionName}`; });
  each('[data-desktop-sha]', el => { el.textContent = d.sha256 || ''; });
  each('[data-desktop-size]', el => { el.textContent = d.size || ''; });
  each('[data-desktop-changelog]', el => { el.textContent = d.changelog || ''; });
}

// Auto-run on DOM ready
document.addEventListener('DOMContentLoaded', loadVersionInfo);
