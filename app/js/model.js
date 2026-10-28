// What Notes+ knows about its note types, byte for byte the apps' NoteData: the kinds, the
// colours, and the JSON each type keeps in a note's `data`. Parsing is lenient: a damaged
// note opens empty rather than breaking the grid.

import { plainLine, repair } from './md.js';

export const TEXT = 'TEXT', LIST = 'LIST', MINDMAP = 'MINDMAP', JOURNAL = 'JOURNAL', SKETCH = 'SKETCH', AUDIO = 'AUDIO';

export const KINDS = [
  { type: TEXT, label: 'Note', plural: 'Notes', description: 'Plain words, fast', icon: 'noteText', accent: 'tile-orange' },
  { type: LIST, label: 'List', plural: 'Lists', description: 'Things to do, tick them off', icon: 'noteList', accent: 'tile-green' },
  { type: JOURNAL, label: 'Journal', plural: 'Journal', description: 'Today, with photos', icon: 'noteJournal', accent: 'tile-purple' },
  { type: MINDMAP, label: 'Mind map', plural: 'Mind maps', description: 'Ideas and how they connect', icon: 'noteMindmap', accent: 'tile-blue' },
  { type: SKETCH, label: 'Sketch', plural: 'Sketches', description: 'Draw with your mouse, pen or finger', icon: 'noteSketch', accent: 'tile-red' },
  { type: AUDIO, label: 'Audio', plural: 'Audio', description: 'Record your voice', icon: 'noteAudio', accent: 'tile-teal' },
];

export const kind = type => KINDS.find(k => k.type === type) || KINDS[0];

/** Card tints; index 0 is "no colour". */
export const COLORS = [null, 'note-yellow', 'note-green', 'note-blue', 'note-purple', 'note-pink', 'note-gray',
  'note-orange', 'note-red', 'note-teal', 'note-indigo', 'note-brown', 'note-lime'];
export const COLOR_NAMES = ['None', 'Yellow', 'Green', 'Blue', 'Purple', 'Pink', 'Grey', 'Orange', 'Red', 'Teal', 'Indigo', 'Brown', 'Lime'];
export const tint = (color, fallback) => (color > 0 && color < COLORS.length ? COLORS[color] : fallback);

/** A note's identity everywhere: 32 lowercase hex characters, like the apps make. */
export function newUuid() {
  const b = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
}

export function newNote(type) {
  return {
    uuid: newUuid(), type, title: '', content: '', data: null, color: 0, pinned: false,
    timestamp: Date.now(), rev: 0, dirty: true, deleted: false, account: null, media: null, sharedFrom: null,
  };
}

function object(json) {
  if (!json) return {};
  try {
    const o = JSON.parse(json);
    return o && typeof o === 'object' ? o : {};
  } catch {
    return {};
  }
}

const array = (json, key) => (Array.isArray(object(json)[key]) ? object(json)[key] : []);

/** Whether a list's or a mind map's long lines wrap (the default). */
export const wraps = json => object(json).wrap !== false;

// ------------------------------------------------------------------------ list

export function todos(json) {
  return array(json, 'items').filter(o => o && typeof o === 'object')
    .map(o => ({ text: typeof o.text === 'string' ? o.text : '', done: o.done === true }));
}

export function todosJson(items, wrap = true) {
  const o = { items: items.map(i => ({ text: i.text, done: !!i.done })) };
  if (!wrap) o.wrap = false;
  return JSON.stringify(o);
}

// -------------------------------------------------------------------- mind map

export function mindMap(json) {
  const nodes = array(json, 'nodes').filter(o => o && typeof o === 'object').map(o => ({
    id: Number(o.id) || 0,
    parent: o.parent === undefined ? -1 : Number(o.parent),
    text: typeof o.text === 'string' ? o.text : '',
    x: Number(o.x) || 0,
    y: Number(o.y) || 0,
    color: Number(o.color) || 0,
    collapsed: o.collapsed === true,
    link: Number(o.link) || 0,
    linkUuid: typeof o.linkUuid === 'string' && o.linkUuid ? o.linkUuid : null,
    image: typeof o.image === 'string' && o.image ? o.image : null,
    sub: typeof o.sub === 'string' ? o.sub : '',
  }));
  const links = array(json, 'links').filter(p => Array.isArray(p) && p.length === 2).map(p => [Number(p[0]), Number(p[1])]);
  return { nodes, links, wrap: wraps(json) };
}

export function mindMapJson(map) {
  const nodes = map.nodes.map(n => {
    const o = { id: n.id, parent: n.parent, text: n.text, x: Math.round(n.x * 10) / 10, y: Math.round(n.y * 10) / 10 };
    if (n.color) o.color = n.color;
    if (n.collapsed) o.collapsed = true;
    if (n.link) o.link = n.link;
    if (n.linkUuid) o.linkUuid = n.linkUuid;
    if (n.image) o.image = n.image;
    if (n.sub) o.sub = n.sub;
    return o;
  });
  const o = { nodes, links: map.links.map(l => [l[0], l[1]]) };
  if (map.wrap === false) o.wrap = false;
  return JSON.stringify(o);
}

// --------------------------------------------------------------------- journal

export function journal(json, fallbackDay) {
  const o = object(json);
  return {
    day: Number(o.day) || fallbackDay,
    mood: typeof o.mood === 'string' ? o.mood : '',
    media: Array.isArray(o.media) ? o.media.filter(m => typeof m === 'string') : [],
  };
}

export const journalJson = j => JSON.stringify({ day: j.day, mood: j.mood, media: j.media });

export const MOODS = ['😄', '🙂', '😐', '😔', '😢', '😡', '😴', '🤩', '❤️'];

export function isVideo(name) {
  const l = name.toLowerCase();
  return /\.(mp4|3gp|webm|mkv|mov|m4v)$/.test(l) || l.startsWith('vid_');
}

// ----------------------------------------------------------------------- audio

export function recordings(json) {
  return array(json, 'recordings').filter(o => o && typeof o === 'object').map(o => ({
    file: typeof o.file === 'string' ? o.file : '',
    label: typeof o.label === 'string' ? o.label : '',
    duration: Number(o.duration) || 0,
    createdAt: Number(o.createdAt) || 0,
    wave: Array.isArray(o.wave) ? o.wave.map(Number) : [],
  }));
}

export function recordingsJson(list) {
  return JSON.stringify({
    recordings: list.map(r => ({
      file: r.file, label: r.label, duration: r.duration, createdAt: r.createdAt,
      wave: r.wave.map(v => Math.round(v * 100) / 100),
    })),
  });
}

export function formatDuration(ms) {
  const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}` : `${m}:${String(sec).padStart(2, '0')}`;
}

// ---------------------------------------------------------- pictures in text

/** A picture between the words of a text note or journal: a line of its own, `![[name]]`. */
export const PICTURE_LINE = /^!\[\[([^\]\/\\]+)\]\]$/;

/** The words of a note for its card: picture lines left out. */
export function plain(content) {
  if (!content) return '';
  // The words without their Markdown marks (cards never show ** or #).
  return repair(content).split('\n').filter(l => !PICTURE_LINE.test(l.trim())).map(plainLine).join('\n').trim();
}

export function firstPicture(content) {
  if (!content) return null;
  for (const l of content.split('\n')) {
    const m = PICTURE_LINE.exec(l.trim());
    if (m) return m[1];
  }
  return null;
}

/** A file name like the apps make: IMG_<millis>_<8 hex>.<ext>. */
export function mediaName(prefix, ext) {
  const hex = Array.from(crypto.getRandomValues(new Uint8Array(4)), x => x.toString(16).padStart(2, '0')).join('');
  return `${prefix}_${Date.now()}_${hex}.${ext}`;
}

/** Search text: what the apps keep in `content` for each type (mind maps and lists put their words there). */
export function searchable(note) {
  return ((note.title || '') + '\n' + (note.content || '')).toLowerCase();
}

export const dayStart = t => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };

export function relative(t) {
  const now = Date.now(), d = new Date(t), today = dayStart(now);
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  if (t >= today) return time;
  if (t >= today - 86400000) return 'Yesterday';
  if (now - t < 6 * 86400000) return d.toLocaleDateString([], { weekday: 'long' });
  return d.toLocaleDateString([], { day: 'numeric', month: 'short', year: d.getFullYear() === new Date().getFullYear() ? undefined : 'numeric' });
}
