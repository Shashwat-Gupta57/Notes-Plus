// Ctrl+K: one search over notes and commands. Open a note, start one (or one from a template), go to
// a folder or filter, change the theme, without the mouse. main.js opens it only on the notes or in a
// note, never over the gallery, and it lists notes only.
//
// Matching is the desktop's Palette.match, case for case: every typed letter in order, ranked higher
// at the start and after a space, a run of letters together higher still; words inside a note rank
// below its name.

import { h, icon } from './ui.js';
import { KINDS, plain, relative } from './model.js';
import * as lock from './lock.js';
import * as theme from './theme.js';
import * as templates from './templates.js';

const MAX_ROWS = 50;
const isWordChar = c => /[\p{L}\p{N}]/u.test(c);

/** How well `query` matches `text`: { score, at } (the matched letters), or null. */
export function match(query, text, lower = text.toLowerCase()) {
  const q = query.toLowerCase().trim(), t = lower;
  if (!q) return { score: 0, at: [] };
  const i = t.indexOf(q);
  if (i >= 0) {
    const wordStart = i === 0 || !isWordChar(t[i - 1]);
    return { score: 1000 + (i === 0 ? 300 : wordStart ? 200 : 0) - Math.min(i, 100) - Math.floor(Math.min(t.length, 200) / 10), at: [...q].map((_, k) => i + k) };
  }
  const at = [];
  let score = 0, from = 0, last = -2;
  for (const c of q) {
    if (c === ' ') { at.push(-1); continue; }
    const found = t.indexOf(c, from);
    if (found < 0) return null;
    at.push(found);
    score += 10;
    if (found === last + 1) score += 15;
    if (found === 0 || !isWordChar(t[found - 1])) score += 20;
    last = found;
    from = found + 1;
  }
  return { score: score - Math.floor(Math.min(t.length, 200) / 10), at };
}

const kindOf = type => KINDS.find(k => k.type === type) || KINDS[0];

function firstLine(words, fallback) {
  for (const line of (words || '').split('\n')) {
    const t = plain(line).trim();
    if (t) return t.length > 80 ? t.slice(0, 80) + '…' : t;
  }
  return fallback;
}

/**
 * Opens the palette. `notes`: every note; `actions`: { open(uuid), create(type), fromTemplate(t),
 * filter(type|null), folders: [...], search(), account(), shortcuts() }.
 */
export function openPalette(notes, actions) {
  if (document.querySelector('.palette-scrim')) return;
  const items = [];
  for (const n of notes) {
    if (n.deleted) continue;
    const k = kindOf(n.type), locked = lock.isLocked(n.data);
    const words = locked ? '' : (n.content || '');
    const label = (n.title || '').trim() || (locked ? 'Locked note' : firstLine(words.slice(0, 600), k.label));
    const where = n.template ? 'Template' : n.archived ? 'Archived' : n.folder || k.label;
    // The date is worked out only for the rows shown (formatting 2,000 dates made opening slow).
    items.push({ group: n.template ? 'Templates' : 'Notes', label, detail: () => `${where}  ·  ${relative(n.timestamp)}`,
      icon: n.template ? 'template' : k.icon, accent: k.accent, words: words.slice(0, 4000).toLowerCase(), when: n.timestamp,
      run: () => actions.open(n.uuid) });
  }
  for (const k of KINDS) {
    items.push({ group: 'New', label: 'New ' + k.label.toLowerCase(), detail: k.description, icon: k.icon, accent: k.accent, words: '', run: () => actions.create(k.type) });
  }
  const tpls = templates.of(notes);
  for (const t of tpls) {
    const k = kindOf(t.type);
    items.push({ group: 'New', label: 'New from template: ' + templates.name(t), detail: k.label + ' template', icon: 'template', accent: k.accent, words: '', run: () => actions.fromTemplate(t) });
  }
  const goTo = (label, type, ic, accent) => ({ group: 'Go to', label, detail: 'Show on the notes grid', icon: ic, accent, words: '', run: () => actions.filter(type) });
  items.push(goTo('All notes', null, 'noteText', 'brand'));
  for (const f of actions.folders) items.push(goTo(f, 'F:' + f, 'folder', 'brand'));
  if (tpls.length) items.push(goTo('Templates', 'TEMPLATES', 'template', 'tile-purple'));
  items.push(goTo('Shared with me', 'SHARED', 'person', 'tile-teal'), goTo('Archived', 'ARCHIVED', 'archive', 'secondary'));
  for (const k of KINDS) items.push(goTo(k.plural, k.type, k.icon, k.accent));
  theme.MODES.forEach((m, i) => items.push({ group: 'Theme', label: 'Theme: ' + theme.NAMES[i], detail: m === theme.mode() ? 'In use' : 'Colours',
    icon: 'palette', accent: 'tile-purple', words: 'appearance dark light colour color', run: () => theme.set(m) }));
  items.push({ group: 'Other', label: 'Search notes', detail: 'Ctrl+F', icon: 'search', accent: 'secondary', words: 'find', run: () => actions.search() });
  items.push({ group: 'Other', label: 'Account', detail: 'Profile, sync, sign in or out', icon: 'person', accent: 'brand', words: 'sign in profile sync', run: () => actions.account() });
  items.push({ group: 'Other', label: 'Import notes…', detail: 'From Google Keep, Apple Notes or Markdown', icon: 'cloud', accent: 'tile-blue',
    words: 'keep apple obsidian markdown takeout', run: () => import('./importexport.js').then(m => m.chooseImport()) });
  items.push({ group: 'Other', label: 'Export all notes…', detail: 'A zip of Markdown', icon: 'cloud', accent: 'tile-blue',
    words: 'backup markdown zip download', run: () => import('./importexport.js').then(m => m.exportAll()) });
  items.push({ group: 'Other', label: 'Keyboard shortcuts', detail: 'Ctrl+K, Ctrl+N, Ctrl+F…', icon: 'search', accent: 'secondary', words: 'keys help', run: () => actions.shortcuts() });

  const field = h('input.palette-field', { type: 'text', placeholder: 'Search notes, or type a command…', 'aria-label': 'Search notes and commands', autocomplete: 'off', spellcheck: false });
  const list = h('div.palette-rows', { role: 'listbox' });
  const card = h('div.palette', { role: 'dialog', 'aria-label': 'Search notes and commands' }, field, list,
    h('div.palette-hint', '↑↓ to move  ·  Enter to open  ·  Esc to close'));
  const scrim = h('div.palette-scrim', card);
  let shown = [], selected = 0;

  function close() {
    scrim.remove();
    document.removeEventListener('keydown', keys, true);
  }
  function run(i) {
    const it = shown[i];
    if (!it) return;
    close();
    it.run();
  }
  function select(i) {
    if (!shown.length) return;
    selected = Math.max(0, Math.min(i, shown.length - 1));
    list.querySelectorAll('.palette-row').forEach((r, k) => r.classList.toggle('selected', k === selected));
    const row = list.querySelectorAll('.palette-row')[selected];
    if (row) row.scrollIntoView({ block: 'nearest' });
  }
  function label(text, at) {
    const hit = new Set(at.filter(a => a >= 0));
    const out = h('span.palette-label');
    let run = '', on = false;
    const flush = () => { if (run) out.appendChild(on ? h('b', run) : document.createTextNode(run)); run = ''; };
    [...text].forEach((c, k) => { const b = hit.has(k); if (b !== on) { flush(); on = b; } run += c; });
    flush();
    return out;
  }
  function render() {
    const q = field.value.trim().toLowerCase();
    let found = [];
    if (!q) {
      found = items.filter(it => it.group === 'Notes').sort((a, b) => b.when - a.when).slice(0, 6).map(it => ({ it, m: { at: [] }, score: 0 }))
        .concat(items.filter(it => it.group === 'New').map(it => ({ it, m: { at: [] }, score: 0 })));
    } else {
      for (const it of items) {
        let m = match(q, it.label, it.lower ||= it.label.toLowerCase()), score;
        if (m) score = m.score;
        else if (it.words && it.words.includes(q)) { m = { at: [] }; score = 100; } // In its words, not its name.
        else continue;
        found.push({ it, m, score });
      }
      // Groups ordered by their best match; within a group, best first.
      const best = new Map();
      for (const f of found) best.set(f.it.group, Math.max(best.get(f.it.group) ?? -1e9, f.score));
      found.sort((a, b) => a.it.group !== b.it.group ? best.get(b.it.group) - best.get(a.it.group) : b.score - a.score);
    }
    found = found.slice(0, MAX_ROWS);
    shown = found.map(f => f.it);
    const frag = document.createDocumentFragment();
    let group = null;
    found.forEach(({ it, m }, i) => {
      if (it.group !== group) { group = it.group; frag.appendChild(h('div.palette-group', group)); }
      const row = h('div.palette-row', { role: 'option' },
        h('span.palette-icon', { style: { background: `var(--${it.accent})` } }, icon(it.icon, 18, '#fff')),
        h('span.palette-texts', label(it.label, m.at), h('small', typeof it.detail === 'function' ? it.detail() : it.detail)));
      row.addEventListener('mousemove', () => { if (selected !== i) select(i); });
      row.addEventListener('click', () => run(i));
      frag.appendChild(row);
    });
    if (!found.length) frag.appendChild(h('div.palette-none', `Nothing matches “${field.value.trim()}”`));
    list.replaceChildren(frag);
    list.scrollTop = 0;
    select(0);
  }
  function keys(e) {
    if (e.key === 'ArrowDown') { e.preventDefault(); select(selected + 1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); select(selected - 1); }
    else if (e.key === 'Enter') { e.preventDefault(); run(selected); }
    else if (e.key === 'Escape' || ((e.key === 'k' || e.key === 'K') && (e.ctrlKey || e.metaKey))) { e.preventDefault(); e.stopPropagation(); close(); }
  }

  field.addEventListener('input', render);
  scrim.addEventListener('mousedown', e => { if (e.target === scrim) close(); });
  document.addEventListener('keydown', keys, true);
  render();
  document.body.appendChild(scrim);
  card.animate([{ opacity: 0, transform: 'translateY(-8px)' }, { opacity: 1, transform: 'none' }], { duration: 140, easing: 'ease-out' });
  field.focus();
}
