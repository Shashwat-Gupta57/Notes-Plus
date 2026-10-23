// The pieces every screen is built from, in the apps' look: icons, buttons, dialogs, menus,
// toasts and the lock entry (PIN, password or pattern).

const P = {
  noteText: 'M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z',
  noteList: 'M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z',
  noteJournal: 'M18,2H6c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM6,4h5v8l-2.5,-1.5L6,12V4z',
  noteMindmap: 'M22,11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z',
  noteSketch: 'M7,14c-1.66,0 -3,1.34 -3,3 0,1.31 -1.16,2 -2,2 0.92,1.22 2.49,2 4,2 2.21,0 4,-1.79 4,-4 0,-1.66 -1.34,-3 -3,-3zM20.71,4.63l-1.34,-1.34c-0.39,-0.39 -1.02,-0.39 -1.41,0L9,12.25 11.75,15l8.96,-8.96c0.39,-0.39 0.39,-1.02 0,-1.41z',
  noteAudio: 'M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z',
  add: 'M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  back: 'M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z',
  search: 'M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z',
  person: 'M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,6c1.93,0 3.5,1.57 3.5,3.5S13.93,13 12,13s-3.5,-1.57 -3.5,-3.5S10.07,6 12,6zM12,20c-2.03,0 -4.43,-0.82 -6.14,-2.88C7.55,15.8 9.68,15 12,15s4.45,0.8 6.14,2.12C16.43,19.18 14.03,20 12,20z',
  more: 'M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z',
  pin: 'M16,9V4h1c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3s0.45,1 1,1h1v5c0,1.66 -1.34,3 -3,3v2h5.97v7l1,1 1,-1v-7H19v-2c-1.66,0 -3,-1.34 -3,-3z',
  palette: 'M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26 -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5H16c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8zM6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8zM14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8zM17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z',
  lock: 'M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z',
  lockOpen: 'M12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6h1.9c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM18,20H6V10h12v10z',
  close: 'M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z',
  check: 'M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z',
  delete: 'M16,9v10H8V9h8m-1.5,-6h-5l-1,1H5v2h14V4h-3.5l-1,-1zM18,7H6v12c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7z',
  photo: 'M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z',
  mic: 'M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z',
  stop: 'M6,6h12v12H6z',
  play: 'M8,5v14l11,-7z',
  pause: 'M6,19h4V5H6v14zM14,5v14h4V5h-4z',
  undo: 'M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z',
  redo: 'M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z',
  brush: 'M7,14c-1.66,0 -3,1.34 -3,3 0,1.31 -1.16,2 -2,2 0.92,1.22 2.49,2 4,2 2.21,0 4,-1.79 4,-4 0,-1.66 -1.34,-3 -3,-3zM20.71,4.63l-1.34,-1.34c-0.39,-0.39 -1.02,-0.39 -1.41,0L9,12.25 11.75,15l8.96,-8.96c0.39,-0.39 0.39,-1.02 0,-1.41z',
  marker: 'M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z',
  eraser: 'M15.14,3c-0.51,0 -1.02,0.2 -1.41,0.59L2.59,14.73c-0.78,0.77 -0.78,2.04 0,2.83L5.03,20h7.66l8.72,-8.73c0.79,-0.77 0.79,-2.04 0,-2.83l-4.85,-4.85C16.17,3.2 15.65,3 15.14,3zM6.86,18l-2.86,-2.86 4.24,-4.24 5.66,5.66L12.04,18H6.86z',
  center: 'M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94V1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11H1v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94V23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94H23v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z',
  tidy: 'M3,13h8V3H3v10zM3,21h8v-6H3v6zM13,21h8V11h-8v10zM13,3v6h8V3h-8z',
  cloud: 'M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z',
  download: 'M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z',
  chevron: 'M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z',
  info: 'M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z',
  logout: 'M17,7l-1.41,1.41L18.17,11H8v2h10.17l-2.58,2.58L17,17l5,-5zM4,5h8V3H4c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h8v-2H4V5z',
  wrap: 'M4,19h6v-2H4v2zM20,5H4v2h16V5zM17,11H4v2h13.25c1.1,0 2,0.9 2,2s-0.9,2 -2,2H15v-2l-3,3 3,3v-2h2c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4z',
  link: 'M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z',
};

/** An icon as inline SVG; `color` is a CSS colour or var(). */
export function icon(name, size = 24, color = 'currentColor') {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.setAttribute('aria-hidden', 'true');
  svg.classList.add('icon');
  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', P[name] || P.noteText);
  path.setAttribute('fill', color);
  svg.appendChild(path);
  return svg;
}

/** h('div.card#id', {attrs/on…}, ...children) — a tiny element builder. */
export function h(tag, props, ...kids) {
  const [base, ...rest] = tag.split(/(?=[.#])/);
  const el = document.createElement(base || 'div');
  for (const r of rest) {
    if (r[0] === '.') el.classList.add(r.slice(1));
    else if (r[0] === '#') el.id = r.slice(1);
  }
  if (props && (typeof props !== 'object' || props instanceof Node || Array.isArray(props))) {
    kids.unshift(props);
    props = null;
  }
  for (const [k, v] of Object.entries(props || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k === 'html') el.innerHTML = v;
    else if (k in el && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  append(el, kids);
  return el;
}

function append(el, kids) {
  for (const k of kids.flat(Infinity)) {
    if (k === null || k === undefined || k === false) continue;
    el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
  }
}

export function iconButton(name, title, onClick, color) {
  const b = h('button.icon-btn', { type: 'button', title, 'aria-label': title, onClick }, icon(name, 24, color));
  return b;
}

export function button(text, onClick, kind = 'filled') {
  return h('button.btn.' + kind, { type: 'button', onClick }, text);
}

// ---------------------------------------------------------------------- toast

let toastTimer = null;
export function toast(text, ms = 2600) {
  let t = document.querySelector('.toast');
  if (!t) {
    t = h('div.toast', { role: 'status' });
    document.body.appendChild(t);
  }
  t.textContent = text;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}

/** A snackbar with an action (Undo); `done(byAction)` runs when it goes. */
export function snackbar(text, action, onAction, done) {
  document.querySelectorAll('.snackbar').forEach(s => s.dispatchEvent(new Event('close')));
  let acted = false;
  const bar = h('div.snackbar', h('span', text),
    action ? h('button.snack-action', { type: 'button', onClick: () => { acted = true; onAction && onAction(); close(); } }, action) : null);
  document.body.appendChild(bar);
  requestAnimationFrame(() => bar.classList.add('show'));
  const timer = setTimeout(close, 4500);
  let closed = false;
  function close() {
    if (closed) return;
    closed = true;
    clearTimeout(timer);
    bar.classList.remove('show');
    setTimeout(() => bar.remove(), 250);
    done && done(acted);
  }
  bar.addEventListener('close', close);
}

// --------------------------------------------------------------------- dialogs

const stack = [];

export function hasModal() {
  return stack.length > 0;
}

export function closeTop() {
  const m = stack[stack.length - 1];
  if (m) m.cancel();
}

/**
 * A dialog: {title, message, content, buttons:[{text, kind, onClick, keep}], cancel, width}.
 * Returns {close}. Buttons close it unless `keep`.
 */
export function dialog({ title, message, content, buttons = [], cancel, width = 420, dismissable = true }) {
  const box = h('div.dialog', { role: 'dialog', 'aria-modal': 'true', style: { maxWidth: width + 'px' } });
  if (title) box.appendChild(h('h2.dialog-title', title));
  if (message) box.appendChild(h('p.dialog-message', message));
  if (content) box.appendChild(h('div.dialog-content', content));
  const row = h('div.dialog-buttons');
  const scrim = h('div.scrim', box);
  const handle = {
    close() {
      const i = stack.indexOf(handle);
      if (i >= 0) stack.splice(i, 1);
      scrim.classList.remove('show');
      setTimeout(() => scrim.remove(), 180);
    },
    cancel() {
      if (!dismissable) return;
      handle.close();
      cancel && cancel();
    },
    box,
  };
  for (const b of buttons) {
    row.appendChild(h('button.btn.' + (b.kind || 'text'), {
      type: 'button',
      onClick: () => {
        if (!b.keep) handle.close();
        b.onClick && b.onClick(handle);
      },
    }, b.text));
  }
  if (buttons.length) box.appendChild(row);
  scrim.addEventListener('mousedown', e => { if (e.target === scrim) handle.cancel(); });
  document.body.appendChild(scrim);
  stack.push(handle);
  requestAnimationFrame(() => scrim.classList.add('show'));
  setTimeout(() => {
    const f = box.querySelector('input, textarea');
    if (f) { f.focus(); if (f.select) f.select(); }
  }, 60);
  return handle;
}

export function confirm(title, message, okText, onOk, danger = false) {
  return dialog({
    title, message,
    buttons: [{ text: 'Cancel' }, { text: okText, kind: danger ? 'danger' : 'text', onClick: onOk }],
  });
}

/** One line of text: {title, message, hint, value, ok, onOk, multiline, allowEmpty, type}. */
export function prompt({ title, message, hint = '', value = '', ok = 'Done', onOk, onCancel, multiline = false, allowEmpty = false, type = 'text' }) {
  const field = multiline
    ? h('textarea.field', { rows: 4, placeholder: hint })
    : h('input.field', { type, placeholder: hint, autocomplete: 'off' });
  field.value = value || '';
  let d;
  const submit = () => {
    const v = field.value.trim();
    if (!v && !allowEmpty) return;
    d.close();
    onOk(field.value);
  };
  if (!multiline) field.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); submit(); } });
  d = dialog({
    title, message, content: field, cancel: onCancel,
    buttons: [{ text: 'Cancel', onClick: onCancel }, { text: ok, keep: true, onClick: submit }],
  });
  return d;
}

/** A list to choose from: items [{text, onClick, danger, icon}]. */
export function choices(title, items, message) {
  const list = h('div.choice-list');
  let d;
  for (const it of items) {
    list.appendChild(h('button.choice' + (it.danger ? '.danger' : ''), {
      type: 'button',
      onClick: () => { d.close(); it.onClick(); },
    }, it.icon ? icon(it.icon, 20) : null, h('span', it.text)));
  }
  d = dialog({ title, message, content: list, buttons: [{ text: 'Cancel' }] });
  return d;
}

/** A small menu dropping from `anchor`. */
export function menu(anchor, items) {
  document.querySelectorAll('.menu').forEach(m => m.remove());
  const m = h('div.menu', { role: 'menu' });
  for (const it of items) {
    if (!it) continue;
    m.appendChild(h('button.menu-item' + (it.danger ? '.danger' : ''), {
      type: 'button', role: 'menuitem',
      onClick: () => { m.remove(); it.onClick(); },
    }, it.text));
  }
  document.body.appendChild(m);
  const r = anchor.getBoundingClientRect();
  const w = m.offsetWidth, hgt = m.offsetHeight;
  m.style.left = Math.max(8, Math.min(window.innerWidth - w - 8, r.right - w)) + 'px';
  m.style.top = Math.max(8, Math.min(window.innerHeight - hgt - 8, r.bottom + 4)) + 'px';
  const away = e => {
    if (!m.contains(e.target)) {
      m.remove();
      document.removeEventListener('mousedown', away, true);
    }
  };
  setTimeout(() => document.addEventListener('mousedown', away, true));
}

/** The colour swatches (the apps' seven), calling `onPick(index)`. */
export function pickColor(current, onPick, names) {
  const row = h('div.swatches');
  let d;
  names.forEach((name, i) => {
    row.appendChild(h('button.swatch' + (i === current ? '.on' : ''), {
      type: 'button', title: name, 'aria-label': name,
      style: { background: i === 0 ? 'var(--surface)' : `var(--note-${['', 'yellow', 'green', 'blue', 'purple', 'pink', 'gray'][i]})` },
      onClick: () => { d.close(); onPick(i); },
    }));
  });
  d = dialog({ title: 'Colour', content: row, width: 380 });
}

// --------------------------------------------------------------- lock entry

/** A 3×3 pattern pad; `onDone(string of dot indexes)` when the pointer lifts. */
export function patternPad(onDone, size = 240) {
  const canvas = h('canvas.pattern', { width: size * 2, height: size * 2, style: { width: size + 'px', height: size + 'px' } });
  const g = canvas.getContext('2d');
  const dots = [];
  for (let r = 0; r < 3; r++) for (let c = 0; c < 3; c++) dots.push({ x: (c + 0.5) * size * 2 / 3, y: (r + 0.5) * size * 2 / 3, id: r * 3 + c });
  let path = [], at = null, drawing = false;
  const ink = () => getComputedStyle(document.body).getPropertyValue('--brand').trim() || '#FFCC00';
  const dim = () => getComputedStyle(document.body).getPropertyValue('--secondary').trim() || '#8E8E93';
  function draw() {
    g.clearRect(0, 0, canvas.width, canvas.height);
    g.lineWidth = 10;
    g.lineCap = 'round';
    g.strokeStyle = ink();
    if (path.length) {
      g.beginPath();
      g.moveTo(path[0].x, path[0].y);
      for (const d of path.slice(1)) g.lineTo(d.x, d.y);
      if (at && drawing) g.lineTo(at.x, at.y);
      g.stroke();
    }
    for (const d of dots) {
      g.beginPath();
      g.fillStyle = path.includes(d) ? ink() : dim();
      g.arc(d.x, d.y, path.includes(d) ? 18 : 12, 0, Math.PI * 2);
      g.fill();
    }
  }
  const pos = e => {
    const r = canvas.getBoundingClientRect();
    return { x: (e.clientX - r.left) * canvas.width / r.width, y: (e.clientY - r.top) * canvas.height / r.height };
  };
  function hit(p) {
    for (const d of dots) if (!path.includes(d) && Math.hypot(d.x - p.x, d.y - p.y) < size * 0.22) path.push(d);
  }
  canvas.addEventListener('pointerdown', e => {
    canvas.setPointerCapture(e.pointerId);
    path = [];
    drawing = true;
    at = pos(e);
    hit(at);
    draw();
  });
  canvas.addEventListener('pointermove', e => {
    if (!drawing) return;
    at = pos(e);
    hit(at);
    draw();
  });
  canvas.addEventListener('pointerup', () => {
    if (!drawing) return;
    drawing = false;
    draw();
    const value = path.map(d => d.id).join('');
    setTimeout(() => { path = []; draw(); }, 300);
    if (value) onDone(value);
  });
  draw();
  return canvas;
}

/** One lock of a known `type`: a field (PIN/password) or a pattern pad. */
function lockInput(type, onSubmit) {
  if (type === 'PATTERN') return { node: h('div.pattern-wrap', patternPad(onSubmit)), value: () => '' };
  const field = h('input.field.lock-field', {
    type: 'password',
    inputmode: type === 'PIN' ? 'numeric' : undefined,
    placeholder: type === 'PIN' ? 'PIN' : 'Password',
    autocomplete: 'off',
  });
  field.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); onSubmit(field.value); } });
  return { node: field, value: () => field.value };
}

/** Asks for an existing lock of `type`. */
export function askLock(type, title, message, onValue, onCancel) {
  let d;
  const input = lockInput(type, v => { d.close(); onValue(v); });
  const buttons = [{ text: 'Cancel', onClick: onCancel }];
  if (type !== 'PATTERN') buttons.push({ text: 'Open', onClick: () => onValue(input.value()) });
  d = dialog({ title, message, content: input.node, buttons, cancel: onCancel, width: 380 });
  return d;
}

/** A new lock: PIN, password or pattern, entered twice. `onLock(type, value)`. */
export function newLock(title, message, onLock) {
  const types = ['PIN', 'PASSWORD', 'PATTERN'];
  let kind = 'PIN', first = null;
  const tabs = h('div.tabs');
  const slot = h('div.lock-slot');
  let d;
  function show() {
    tabs.querySelectorAll('.tab').forEach((t, i) => t.classList.toggle('on', types[i] === kind));
    const input = lockInput(kind, v => submit(v));
    slot.replaceChildren(input.node);
    slot._value = input.value;
    setTimeout(() => { const f = slot.querySelector('input'); if (f) f.focus(); }, 30);
  }
  ['PIN', 'Password', 'Pattern'].forEach((label, i) => tabs.appendChild(h('button.tab', { type: 'button', onClick: () => { kind = types[i]; show(); } }, label)));
  const problem = (t, v) => (!v ? 'It can\'t be empty' : t === 'PIN' && !/^\d{4,}$/.test(v) ? 'A PIN needs at least 4 digits'
    : t === 'PASSWORD' && v.length < 6 ? 'Use at least 6 characters' : t === 'PATTERN' && v.length < 4 ? 'Connect at least 4 dots' : null);
  function submit(v) {
    const p = problem(kind, v);
    if (p) { toast(p); return; }
    if (first === null) {
      first = v;
      d.box.querySelector('.dialog-title').textContent = 'Once more, to confirm';
      tabs.style.display = 'none';
      show();
      return;
    }
    if (first !== v) {
      toast('Those didn\'t match. Try again.');
      first = null;
      d.box.querySelector('.dialog-title').textContent = title;
      tabs.style.display = '';
      show();
      return;
    }
    d.close();
    onLock(kind, v);
  }
  d = dialog({
    title, message, content: h('div', tabs, slot), width: 400,
    buttons: [{ text: 'Cancel' }, { text: 'Next', keep: true, onClick: () => submit(slot._value()) }],
  });
  show();
  return d;
}

/** Is the app being used on a phone-sized screen? */
export const narrow = () => window.matchMedia('(max-width: 640px)').matches;
