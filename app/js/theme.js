// The whole app's look: the system's choice, light, dark, or high contrast (dark or light).
// Kept in this browser; index.html applies it before the first paint.

export const MODES = ['system', 'light', 'dark', 'contrast-dark', 'contrast-light'];
export const NAMES = ['Same as this device', 'Light', 'Dark', 'High contrast, dark', 'High contrast, light'];

export function mode() {
  try { return localStorage.getItem('np_theme') || 'system'; } catch { return 'system'; }
}

export function apply(m = mode()) {
  if (m === 'system') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = m;
}

export function set(m) {
  try { localStorage.setItem('np_theme', m); } catch { /* private mode: this visit */ }
  apply(m);
}
