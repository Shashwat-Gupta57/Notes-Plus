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

    // Download links
    document.querySelectorAll('[data-version-link]').forEach(el => {
      el.setAttribute('href', data.apkUrl);
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

// Auto-run on DOM ready
document.addEventListener('DOMContentLoaded', loadVersionInfo);
