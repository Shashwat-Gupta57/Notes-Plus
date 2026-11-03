// Searching every note at once: every word typed must be somewhere in the note (its title, words,
// list items, mind-map ideas and their sub-texts, a journal day's words: what `content` holds), in any
// order, ignoring case. Archived notes and templates are searched too; hidden notes never are. A
// locked note is searched by its title only. Titles that match come first, then the newest.
// Filters narrow it: a kind of note, a colour, a folder, "has pictures", "has dates".
//
// The apps' Search.java, case for case (the same tests).

import { TEXT, LIST, JOURNAL, MINDMAP, SKETCH, todos, journal, mindMap, plain, PICTURE_LINE } from './model.js';
import * as lock from './lock.js';
import * as due from './due.js';
import { shown } from './notelinks.js';

export const emptyFilters = () => ({ type: null, color: -1, folder: null, pictures: false, dates: false });
export const anyFilter = f => !!f && (f.type !== null || f.color >= 0 || f.folder !== null || f.pictures || f.dates);

const PICTURE = /!\[\[[^\]]+\.(png|jpe?g|gif|webp|bmp|heic)\]\]/i;

/** The words typed, each once, lowercased. */
export function terms(query) {
  const out = [];
  for (let w of (query || '').trim().toLowerCase().split(/\s+/)) {
    w = w.replace(/^["'“”]+|["'“”]+$/g, '');
    if (w && !out.includes(w)) out.push(w);
  }
  return out;
}

const locked = n => lock.isLocked(n.data);

export function haystack(n) {
  const title = n.title || '';
  return (locked(n) ? title : title + '\n' + (n.content || '')).toLowerCase();
}

/** How well `n` matches (higher is better), or -1 when a word is missing. */
export function score(n, ts) {
  const hay = haystack(n), title = (n.title || '').toLowerCase();
  let s = 0;
  for (const t of ts) {
    if (!hay.includes(t)) return -1;
    s += title.includes(t) ? (title.startsWith(t) ? 120 : 100) : 10;
  }
  return s;
}

export function hasPictures(n) {
  if (locked(n)) return false;
  if (n.type === SKETCH) return true;
  if (n.type === LIST) return todos(n.data).some(i => PICTURE_LINE.test((i.text || '').trim()));
  if (n.type === MINDMAP) return mindMap(n.data).nodes.some(x => !!x.image);
  if (n.type === JOURNAL && journal(n.data, 0).media.length) return true;
  return PICTURE.test(n.content || '');
}

export const hasDates = n => n.type === LIST && !locked(n) && due.anyDated(todos(n.data));

export function passes(n, f) {
  if (!f) return true;
  if (f.type !== null && f.type !== n.type) return false;
  if (f.color >= 0 && (n.color || 0) !== f.color) return false;
  if (f.folder !== null && (n.folder || '').toLowerCase() !== f.folder.toLowerCase()) return false;
  if (f.pictures && !hasPictures(n)) return false;
  return !f.dates || hasDates(n);
}

/** Every note matching `query` and `filters`, best first; nothing when neither is set. */
export function run(notes, query, filters) {
  const ts = terms(query);
  if (!ts.length && !anyFilter(filters)) return [];
  const ranked = [];
  for (const n of notes) {
    if (n.deleted || n.hidden || !passes(n, filters)) continue;
    const s = ts.length ? score(n, ts) : 0;
    if (s >= 0) ranked.push([s, n]);
  }
  ranked.sort((a, b) => (b[0] - a[0]) || ((b[1].timestamp || 0) - (a[1].timestamp || 0)));
  return ranked.map(r => r[1]);
}

/** Where the typed words are in `text`, merged: [[start, end], …]. */
export function marks(text, ts) {
  const hits = [], low = (text || '').toLowerCase();
  for (const t of ts) for (let i = low.indexOf(t); i >= 0; i = low.indexOf(t, i + t.length)) hits.push([i, i + t.length]);
  hits.sort((a, b) => a[0] - b[0]);
  const merged = [];
  for (const h of hits) {
    const last = merged[merged.length - 1];
    if (last && h[0] <= last[1]) last[1] = Math.max(last[1], h[1]); else merged.push([...h]);
  }
  return merged;
}

/** About `width` characters of the note's words around the first word found there, marked; or null. */
export function snippet(n, ts, width) {
  if (!ts.length || locked(n) || !n.content) return null;
  let words = n.type === TEXT || n.type === JOURNAL ? plain(n.content) : n.content.replace(/\n/g, ' · '); // Items and ideas apart.
  words = shown(words).replace(/\s+/g, ' ').trim();
  const lower = words.toLowerCase();
  let at = -1;
  for (const t of ts) { const i = lower.indexOf(t); if (i >= 0 && (at < 0 || i < at)) at = i; }
  if (at < 0) return null;
  let start = Math.max(0, at - Math.floor(width / 3));
  if (start > 0) { const space = words.indexOf(' ', start); if (space > 0 && space < at) start = space + 1; }
  let end = Math.min(words.length, start + width);
  if (end < words.length) { const space = words.lastIndexOf(' ', end); if (space > at) end = space; }
  const text = (start > 0 ? '…' : '') + words.slice(start, end) + (end < words.length ? '…' : '');
  return { text, hits: marks(text, ts) };
}
