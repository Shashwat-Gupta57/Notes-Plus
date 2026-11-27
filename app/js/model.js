// What Notes+ knows about its note types, byte for byte the apps' NoteData: the kinds, the
// colours, and the JSON each type keeps in a note's `data`. Parsing is lenient: a damaged
// note opens empty rather than breaking the grid.

import { plainLine, repair } from './md.js';

export const TEXT = 'TEXT', LIST = 'LIST', MINDMAP = 'MINDMAP', JOURNAL = 'JOURNAL', SKETCH = 'SKETCH', AUDIO = 'AUDIO', ULTIMATE = 'ULTIMATE';

export const KINDS = [
  { type: TEXT, label: 'Note', plural: 'Notes', description: 'Plain words, fast', icon: 'noteText', accent: 'tile-orange' },
  { type: LIST, label: 'List', plural: 'Lists', description: 'Things to do, tick them off', icon: 'noteList', accent: 'tile-green' },
  { type: JOURNAL, label: 'Journal', plural: 'Journal', description: 'Today, with photos', icon: 'noteJournal', accent: 'tile-purple' },
  { type: MINDMAP, label: 'Mind map', plural: 'Mind maps', description: 'Ideas and how they connect', icon: 'noteMindmap', accent: 'tile-blue' },
  { type: SKETCH, label: 'Sketch', plural: 'Sketches', description: 'Draw with your mouse, pen or finger', icon: 'noteSketch', accent: 'tile-red' },
  { type: AUDIO, label: 'Audio', plural: 'Audio', description: 'Record your voice', icon: 'noteAudio', accent: 'tile-teal' },
  { type: ULTIMATE, label: 'Ultimate', plural: 'Ultimate', description: 'Words, lists, maps, voice and days in one', icon: 'noteUltimate', accent: 'tile-indigo' },
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
    // Made while an account is remembered here but still loading (the first seconds after opening,
    // or offline): it joins that account at the next sync instead of staying only in this browser.
    pendingAccount: typeof localStorage !== 'undefined' && !!localStorage.getItem('np_account'),
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
  return array(json, 'items').filter(o => o && typeof o === 'object').map(o => {
    const item = { text: typeof o.text === 'string' ? o.text : '', done: o.done === true };
    // An item's day, time and repeat (see due.js); kept only when well formed.
    if (typeof o.due === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(o.due)) {
      item.due = o.due;
      if (typeof o.time === 'string' && /^\d{2}:\d{2}$/.test(o.time)) item.time = o.time;
      if (typeof o.repeat === 'string' && o.repeat) item.repeat = o.repeat;
    }
    return item;
  });
}

export function todosJson(items, wrap = true) {
  const o = {
    items: items.map(i => {
      const out = { text: i.text, done: !!i.done };
      if (i.due) {
        out.due = i.due;
        if (i.time) out.time = i.time;
        if (i.repeat) out.repeat = i.repeat;
      }
      return out;
    }),
  };
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
    // The idea's own colour (filling it); older maps coloured a central idea with `color`.
    fill: o.fill !== undefined ? Number(o.fill) || 0 : (o.parent === undefined || Number(o.parent) < 0) ? Number(o.color) || 0 : 0,
    collapsed: o.collapsed === true,
    link: Number(o.link) || 0,
    linkUuid: typeof o.linkUuid === 'string' && o.linkUuid ? o.linkUuid : null,
    image: typeof o.image === 'string' && o.image ? o.image : null,
    sub: typeof o.sub === 'string' ? o.sub : '',
    // What's inside the idea (Ultimate notes): canvases of its own, kept as they are.
    blocks: Array.isArray(o.blocks) && o.blocks.length ? o.blocks : null,
  }));
  const links = array(json, 'links').filter(p => Array.isArray(p) && p.length === 2).map(p => [Number(p[0]), Number(p[1])]);
  return { nodes, links, wrap: wraps(json) };
}

export function mindMapJson(map) {
  const nodes = map.nodes.map(n => {
    const o = { id: n.id, parent: n.parent, text: n.text, x: Math.round(n.x * 10) / 10, y: Math.round(n.y * 10) / 10 };
    if (n.color) o.color = n.color;
    if (n.fill || (n.parent < 0 && n.color)) o.fill = n.fill || 0;
    if (n.collapsed) o.collapsed = true;
    if (n.link) o.link = n.link;
    if (n.linkUuid) o.linkUuid = n.linkUuid;
    if (n.image) o.image = n.image;
    if (n.sub) o.sub = n.sub;
    if (n.blocks && n.blocks.length) o.blocks = n.blocks;
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

// -------------------------------------------------------------------- ultimate

// An Ultimate note is a column of canvases, `{"blocks": [...]}`. Each block is the data of the type it
// borrows from plus its `kind` and `id`: a list's `items`, a mind map's `nodes`/`links` (and `h`, its
// height), an audio note's `recordings`; text keeps `md` (pictures as `![[…]]` lines in it), a day
// keeps `day` (its midnight, ms) and `mood`. Kinds this version doesn't know are kept as they are.
export const MAP_H = 320, MAP_MIN_H = 160, MAP_MAX_H = 1200;

export const blockId = () => Array.from(crypto.getRandomValues(new Uint8Array(4)), x => x.toString(16).padStart(2, '0')).join('');

export function blocks(json) {
  return array(json, 'blocks').filter(b => b && typeof b === 'object' && !Array.isArray(b) && typeof b.kind === 'string')
    .map(b => ({ ...b, id: typeof b.id === 'string' && b.id ? b.id : blockId() }));
}

export const blocksJson = list => JSON.stringify({ blocks: list });

/** How far a canvas sits in (0–4): nested under the list item or words above it. */
export const indentOf = b => Math.max(0, Math.min(4, Math.round(Number(b.indent) || 0)));

/** A short summary of what's inside an idea, for its badge: "☑ 1/3 · 🎙 2". */
export function insideSummary(list) {
  const parts = [];
  let items = 0, done = 0, recs = 0, maps = 0, words = 0, days = 0;
  for (const b of list || []) {
    if (b.kind === 'list') todos(JSON.stringify(b)).forEach(i => { if (i.text.trim()) { items++; if (i.done) done++; } });
    else if (b.kind === 'audio') recs += recordings(JSON.stringify(b)).length;
    else if (b.kind === 'mindmap') maps++;
    else if (b.kind === 'text' && (b.md || '').trim()) words++;
    else if (b.kind === 'day') days++;
  }
  if (items) parts.push(`☑ ${done}/${items}`);
  if (recs) parts.push(`🎙 ${recs}`);
  if (maps) parts.push(maps > 1 ? `◈ ${maps}` : '◈');
  if (days) parts.push('📅');
  if (words) parts.push('¶');
  return parts.join(' · ');
}

export const mapHeight = b => Math.max(MAP_MIN_H, Math.min(MAP_MAX_H, Math.round(Number(b.h) || MAP_H)));

const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
/** "Thursday, 26 November 2026": the same words on every device, whatever its language settings. */
export function dayWords(ms) {
  const d = new Date(ms);
  return `${DAYS[d.getDay()]}, ${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
}

/** The note's words as Markdown, for search, cards, links and older versions (which open it as a text note). */
export function blocksContent(list) {
  const out = [];
  for (const b of list) {
    const json = JSON.stringify(b);
    if (b.kind === 'text') { if (typeof b.md === 'string' && b.md.trim()) out.push(b.md.trim()); }
    else if (b.kind === 'list') todos(json).forEach(i => { if (i.text.trim()) out.push((i.done ? '- [x] ' : '- [ ] ') + i.text.trim()); });
    else if (b.kind === 'mindmap') mindMap(json).nodes.forEach(n => {
      for (const t of [n.text, n.sub]) if (t.trim()) out.push('- ' + t.trim());
      if (n.blocks) { const inside = blocksContent(blocks(JSON.stringify({ blocks: n.blocks }))); if (inside) out.push(inside); }
    });
    else if (b.kind === 'audio') recordings(json).forEach(r => out.push('🎙 ' + (r.label.trim() || 'Recording')));
    else if (b.kind === 'day' && Number(b.day)) out.push('### ' + dayWords(Number(b.day)) + (b.mood ? ' ' + b.mood : ''));
  }
  return out.join('\n');
}

// ---------------------------------------------------------- pictures in text

/** A picture between the words of a text note or journal: a line of its own, `![[name]]`, maybe with a
 *  size and place after it (`![[name|60%|center]]`, picline.js). Group 1 is the name. */
export const PICTURE_LINE = /^!\[\[([^\]|\/\\]+)(?:\|[^\]]*)?\]\]$/;

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

// Made once: a formatter is slow to make, and a grid of cards asks for hundreds of dates.
const FORMATS = {};
const format = (key, options) => (FORMATS[key] ||= new Intl.DateTimeFormat([], options));

export function relative(t) {
  const now = Date.now(), d = new Date(t), today = dayStart(now);
  if (t >= today) return format('time', { hour: '2-digit', minute: '2-digit' }).format(d);
  if (t >= today - 86400000) return 'Yesterday';
  if (now - t < 6 * 86400000) return format('weekday', { weekday: 'long' }).format(d);
  return d.getFullYear() === new Date(now).getFullYear()
    ? format('day', { day: 'numeric', month: 'short' }).format(d)
    : format('dayYear', { day: 'numeric', month: 'short', year: 'numeric' }).format(d);
}
