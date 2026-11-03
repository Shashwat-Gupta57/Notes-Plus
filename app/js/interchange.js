// Notes in and out: Google Keep (Takeout), Apple Notes (its .html / .txt export), Markdown folders
// (Obsidian, Bear, Joplin, plain files), and Notes+'s own export, a zip of Markdown that reads back
// here. Pure: files in, notes out (and back). The screens pick the files and store the notes.
//
// The phone's and desktop's Interchange.java read and write the same way, kept in step by the same
// test cases.

import { TEXT, LIST, JOURNAL, MINDMAP, SKETCH, AUDIO, todos, todosJson, journal, journalJson, mindMap, mindMapJson, recordings } from './model.js';
import * as due from './due.js';

export const MAX_TEXT = 2000000;
const COLOR_NAMES = ['None', 'Yellow', 'Green', 'Blue', 'Purple', 'Pink', 'Grey', 'Orange', 'Red', 'Teal', 'Indigo', 'Brown', 'Lime'];
const EMBED = /^!\[\[([^\]]+?)(\|[^\]]*)?\]\]$/;
const MD_IMAGE = /^!\[[^\]]*\]\(<?([^)>]+?)>?(\s+"[^"]*")?\)$/;
const TASK = /^\s*[-*+]\s+\[([ xX])\]\s?(.*)$/;
const BULLET = /^(\s*)[-*+]\s+(.*)$/;
const PICTURE_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'heic']);
const utf8 = new TextDecoder(), enc = new TextEncoder();

/** A note, coming in or going out. */
export function emptyNote() {
  return { type: TEXT, title: '', content: '', data: null, folder: null, color: 0, pinned: false, archived: false, template: false,
    locked: false, edited: 0, created: 0, files: new Map(), key: null };
}

const norm = p => p.replace(/\\/g, '/').replace(/^\.\//, '').replace(/^\/+/, '');
export const base = p => p.slice(p.lastIndexOf('/') + 1);
const parent = p => (p.lastIndexOf('/') < 0 ? '' : p.slice(0, p.lastIndexOf('/')));
export const ext = p => { const b = base(p), d = b.lastIndexOf('.'); return d < 0 ? '' : b.slice(d + 1).toLowerCase(); };
const stripExt = n => { const d = n.lastIndexOf('.'); return d <= 0 ? n : n.slice(0, d); };
const clip = (s, max) => (s.length > max ? s.slice(0, max) : s);
const capital = s => (s ? s[0].toUpperCase() + s.slice(1) : s);

// ====================================================================== in

/** Every note in [{path, bytes}]: { notes, sources, folders, pictures, trashed, ignored, summary() }. */
export function read(entries, namer, now) {
  const found = { notes: [], sources: new Set(), folders: new Set(), pictures: 0, trashed: 0, ignored: 0 };
  const byPath = new Map(), byName = new Map(), notes = [];
  for (const raw of entries) {
    const e = { path: norm(raw.path), bytes: raw.bytes };
    if (hidden(e.path)) continue;
    byPath.set(e.path.toLowerCase(), e);
    if (!byName.has(base(e.path).toLowerCase())) byName.set(base(e.path).toLowerCase(), e);
    const x = ext(e.path);
    if (inAttachments(e.path) || base(e.path).toLowerCase() === 'not exported.md') continue;
    if (['json', 'md', 'markdown', 'txt', 'html', 'htm'].includes(x)) notes.push(e);
  }
  const root = commonRoot(notes);
  const pics = picsOf(byPath, byName, namer, found);
  for (const e of notes) {
    if (e.bytes.length > MAX_TEXT) { found.ignored++; continue; }
    let text = utf8.decode(e.bytes);
    if (text.startsWith('﻿')) text = text.slice(1);
    const rel = e.path.slice(Math.min(root.length, e.path.length));
    const x = ext(e.path);
    let n;
    if (x === 'json') {
      n = keep(text, e.path, pics, found);
      if (n) found.sources.add('Google Keep');
    } else if (x === 'html' || x === 'htm') {
      n = html(text, e.path, pics);
      found.sources.add('Apple Notes');
    } else {
      n = markdown(text, e.path, pics);
      found.sources.add(x === 'txt' ? 'text files' : 'Markdown');
    }
    if (!n) { found.ignored++; continue; }
    if (x !== 'json') fileFolder(n, rel);
    if (n.edited <= 0) n.edited = now;
    if (n.folder) found.folders.add(n.folder);
    found.notes.push(n);
  }
  if (found.sources.has('Markdown')) found.sources.delete('text files');
  found.summary = () => summary(found);
  return found;
}

function summary(found) {
  const lists = found.notes.filter(n => n.type === LIST).length, all = found.notes.length;
  let s = `Found ${all} ${all === 1 ? 'note' : 'notes'}`;
  if (found.sources.size) s += ' from ' + [...found.sources].join(', ');
  s += '.';
  const parts = [];
  if (all - lists > 0) parts.push(`${all - lists} text`);
  if (lists > 0) parts.push(`${lists} ${lists === 1 ? 'list' : 'lists'}`);
  if (found.pictures > 0) parts.push(`${found.pictures} ${found.pictures === 1 ? 'picture' : 'pictures'}`);
  if (parts.length) s += ' ' + capital(parts.join(', ')) + '.';
  if (found.folders.size) {
    const f = [...found.folders];
    s += ' Folders: ' + f.slice(0, 6).join(', ') + (f.length > 6 ? ` and ${f.length - 6} more` : '') + '.';
  }
  if (found.trashed > 0) s += ` ${found.trashed} in Keep's trash left out.`;
  return s;
}

/** The key that says two notes are the same (importing twice skips what's already here). */
// Without its picture lines: a picture gets a new name each time it's brought in.
export const sameKey = (title, content) => (title || '').trim().toLowerCase() + '\n'
  + (content || '').replace(/!\[\[[^\]]*\]\]/g, '').replace(/\s+/g, ' ').trim().toLowerCase();

// ----------------------------------------------------------------- Google Keep

function keep(text, path, pics, found) {
  let o;
  try { o = JSON.parse(text); } catch { return null; }
  if (!o || typeof o !== 'object') return null;
  if (!('textContent' in o || 'listContent' in o) || !('userEditedTimestampUsec' in o || 'createdTimestampUsec' in o || 'isTrashed' in o)) return null;
  if (o.isTrashed === true) { found.trashed++; return null; }
  const n = emptyNote();
  n.title = String(o.title || '').trim();
  n.pinned = o.isPinned === true;
  n.archived = o.isArchived === true;
  n.color = keepColor(String(o.color || 'DEFAULT'));
  n.edited = Math.floor(Number(o.userEditedTimestampUsec || 0) / 1000);
  n.created = Math.floor(Number(o.createdTimestampUsec || 0) / 1000);
  const label = Array.isArray(o.labels) && o.labels[0] && typeof o.labels[0].name === 'string' ? o.labels[0].name.trim() : '';
  if (label) n.folder = clip(label, 100);
  const names = [];
  for (const a of Array.isArray(o.attachments) ? o.attachments : []) {
    if (!a || !String(a.mimetype || '').startsWith('image/')) continue;
    const name = pics.take(String(a.filePath || ''), parent(path), n);
    if (name) names.push(name);
  }
  if (Array.isArray(o.listContent)) {
    const items = o.listContent.filter(it => it && typeof it === 'object')
      .map(it => ({ text: String(it.text || '').replace(/\n/g, ' '), done: it.isChecked === true }));
    for (const p of names) items.push({ text: `![[${p}]]`, done: false });
    listNote(n, items);
  } else {
    let words = String(o.textContent || '').trim();
    for (const p of names) words += (words ? '\n\n' : '') + `![[${p}]]`;
    n.content = words;
  }
  if (!n.title && !n.content && !n.data) return null;
  return n;
}

function keepColor(c) {
  return { RED: 8, ORANGE: 7, YELLOW: 1, GREEN: 2, TEAL: 9, BLUE: 3, CERULEAN: 10, PURPLE: 4, PINK: 5, BROWN: 11, GRAY: 6, GREY: 6 }[c.toUpperCase()] || 0;
}

// ------------------------------------------------------------------ Markdown

function markdown(text, path, pics) {
  text = text.replace(/\r\n?/g, '\n');
  const n = emptyNote(), front = {};
  if (text.startsWith('---\n')) {
    const end = text.indexOf('\n---', 4);
    if (end > 0) {
      for (const line of text.slice(4, end).split('\n')) {
        const colon = line.indexOf(':');
        if (colon > 0) front[line.slice(0, colon).trim().toLowerCase()] = unquote(line.slice(colon + 1).trim());
      }
      const after = text.indexOf('\n', end + 4);
      text = after < 0 ? '' : text.slice(after + 1);
    }
  }
  let title = front.title || '';
  const lines = text.split('\n');
  while (lines.length && !lines[0].trim()) lines.shift();
  if (!title && lines.length && lines[0].startsWith('# ')) {
    title = lines.shift().slice(2).trim();
    while (lines.length && !lines[0].trim()) lines.shift();
  }
  if (!title) title = stripExt(base(path));
  n.title = clip(title, 500);
  n.edited = date(front.updated ?? front.modified ?? front.date);
  n.created = date(front.created);
  n.pinned = String(front.pinned || '').toLowerCase() === 'true';
  n.color = colorIndex(front.colour ?? front.color);
  if (front.folder) n.folder = clip(front.folder, 100);
  const dir = parent(path), type = String(front.type || '').toLowerCase();

  if (type === 'mind map' || type === 'mindmap') {
    const map = mindMapFrom(lines, dir, pics, n);
    if (map) return map;
  }
  let tasks = false, other = false;
  for (const l of lines) {
    if (!l.trim()) continue;
    if (TASK.test(l)) tasks = true;
    else if (imageLine(l) === null) other = true;
  }
  if (type === 'list' || (tasks && !other)) {
    const items = [];
    for (const l of lines) {
      if (!l.trim()) continue;
      const t = TASK.exec(l), img = imageLine(l);
      if (t) items.push(task(t[2], t[1].toLowerCase() === 'x'));
      else if (img !== null) {
        const name = pics.take(img, dir, n);
        if (name) items.push({ text: `![[${name}]]`, done: false });
      } else {
        const b = BULLET.exec(l);
        items.push({ text: b ? b[2].trim() : l.trim(), done: false });
      }
    }
    listNote(n, items);
    return n;
  }
  const out = lines.map(l => {
    const img = imageLine(l);
    if (img === null) return l;
    const name = pics.take(img, dir, n);
    return name ? `![[${name}]]` : l;
  });
  n.content = out.join('\n').replace(/\n{3,}/g, '\n\n').trim();
  if (type === 'journal') {
    n.type = JOURNAL;
    const j = journal(null, n.edited > 0 ? n.edited : 0);
    const day = date(front.day);
    if (day > 0) j.day = day;
    j.mood = front.mood || '';
    const kept = [];
    for (const l of n.content.split('\n')) {
      const m = EMBED.exec(l.trim());
      if (m) j.media.push(m[1]); else kept.push(l);
    }
    n.content = kept.join('\n').trim();
    n.data = journalJson(j);
  }
  return n;
}

/** A task's words, with a 📅 day, ⏰ time and 🔁 repeat taken off them. */
function task(words, done) {
  const item = { text: words, done };
  const d = /\s*📅\s*(\d{4}-\d{2}-\d{2})/u.exec(words);
  if (d && due.day(d[1]) !== due.NO_DAY) {
    item.due = d[1];
    words = words.slice(0, d.index) + words.slice(d.index + d[0].length);
    const t = /\s*⏰\s*(\d{1,2}):(\d{2})/u.exec(words);
    if (t) {
      item.time = String(Number(t[1])).padStart(2, '0') + ':' + t[2];
      words = words.slice(0, t.index) + words.slice(t.index + t[0].length);
    }
    const r = /\s*🔁\s*([^📅⏰]+)$/u.exec(words);
    if (r) {
      const repeat = repeatFrom(r[1].trim(), item.due);
      if (repeat) item.repeat = repeat;
      words = words.slice(0, r.index);
    }
  }
  item.text = words.trim();
  return item;
}

/** "every Mon, Wed" (what due.repeatLong writes, and Obsidian Tasks' words) back to a repeat. */
export function repeatFrom(words, dueDay) {
  const w = words.toLowerCase().replace('every', '').trim();
  if (w === 'day' || w === 'daily') return 'daily';
  if (w === 'weekday' || w === 'weekdays') return 'weekdays';
  const month = /^month(?: on the (\d{1,2})(?:st|nd|rd|th)?)?$/.exec(w);
  if (month) {
    const d = month[1] ? Number(month[1]) : due.civil(due.day(dueDay))[2];
    return d >= 1 && d <= 31 ? 'monthly:' + d : null;
  }
  const on = new Array(8).fill(false);
  let any = false;
  const full = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];
  for (const part of w.replace('week on', '').split(/[,\s]+(?:and\s+)?/)) {
    for (let d = 0; d < 7; d++) if (part && part.length >= 3 && full[d].startsWith(part)) { on[d + 1] = true; any = true; }
  }
  if (!any && w === 'week') { on[due.weekday(due.day(dueDay))] = true; any = true; }
  return any ? due.weekly(on) : null;
}

/** A nested bullet outline (what the export writes for a mind map) back into its ideas. */
function mindMapFrom(lines, dir, pics, n) {
  const map = mindMap(null), stack = [];
  let nextId = 1, row = 0;
  for (const l of lines) {
    if (!l.trim()) continue;
    const b = BULLET.exec(l);
    if (b) {
      const indent = b[1].replace(/\t/g, '    ').length;
      while (stack.length && stack[stack.length - 1][0] >= indent) stack.pop();
      const parentId = stack.length ? map.nodes[stack[stack.length - 1][1]].id : -1;
      map.nodes.push({ id: nextId++, parent: parentId, text: b[2].trim(), x: 240 * stack.length, y: 90 * row++, color: 0,
        collapsed: false, link: 0, linkUuid: null, image: null, sub: '' });
      stack.push([indent, map.nodes.length - 1]);
    } else if (map.nodes.length) {
      const last = map.nodes[map.nodes.length - 1], t = l.trim(), img = imageLine(t);
      if (img !== null) last.image = pics.take(img, dir, n);
      else if (t.length > 2 && t.startsWith('_') && t.endsWith('_')) last.sub = t.slice(1, -1);
    }
  }
  if (!map.nodes.length) return null;
  n.type = MINDMAP;
  n.data = mindMapJson(map);
  n.content = map.nodes.map(x => x.text).join('\n').trim();
  return n;
}

// -------------------------------------------------------- Apple Notes (HTML)

function html(text, path, pics) {
  const n = emptyNote();
  const t = /<title>([\s\S]*?)<\/title>/i.exec(text);
  let title = t ? entities(t[1]).trim() : '';
  const lines = htmlToMarkdown(text, parent(path), pics, n).split('\n');
  while (lines.length && !lines[0].trim()) lines.shift();
  if (lines.length && lines[0].startsWith('# ')) {
    const first = lines[0].slice(2).trim();
    if (!title || title.toLowerCase() === first.toLowerCase()) { title = first; lines.shift(); }
  }
  if (!title) title = stripExt(base(path));
  n.title = clip(title, 500);
  n.content = lines.join('\n').replace(/\n{3,}/g, '\n\n').trim();
  return n;
}

const newLine = out => { if (out.length && !out[out.length - 1].endsWith('\n')) out.push('\n'); };

/** HTML as Notes+ Markdown: headings, bold, italic, underline, strike, lists, checklists, links, pictures. */
export function htmlToMarkdown(html, dir, pics, n) {
  html = html.replace(/<(head|style|script)\b[\s\S]*?<\/\1>/gi, '');
  const out = [], lists = [];
  const text = () => out.join('');
  let href = null, linkStart = 0, at = 0, m;
  const TAG = /<(\/?)([a-zA-Z0-9]+)([^>]*)>|<!--[\s\S]*?-->/g;
  while ((m = TAG.exec(html))) {
    const between = entities(html.slice(at, m.index).replace(/\s+/g, ' '));
    if (between) out.push(between);
    at = TAG.lastIndex;
    if (m[2] === undefined) continue;
    const close = m[1] !== '', tag = m[2].toLowerCase(), attrs = m[3], a = attrs.toLowerCase();
    switch (tag) {
      case 'br': out.push('\n'); break;
      case 'p': case 'div': case 'tr': case 'blockquote': newLine(out); break;
      case 'h1': newLine(out); if (!close) out.push('# '); break;
      case 'h2': newLine(out); if (!close) out.push('## '); break;
      case 'h3': case 'h4': case 'h5': case 'h6': newLine(out); if (!close) out.push('### '); break;
      case 'b': case 'strong': out.push('**'); break;
      case 'i': case 'em': out.push('*'); break;
      case 'u': out.push(close ? '</u>' : '<u>'); break;
      case 's': case 'strike': case 'del': out.push('~~'); break;
      case 'ul': case 'ol':
        if (close) lists.pop(); else lists.push(tag + (a.includes('checklist') ? ':check' : ''));
        newLine(out);
        break;
      case 'li': {
        newLine(out);
        if (close) break;
        const kind = lists.length ? lists[lists.length - 1] : 'ul', indent = '  '.repeat(Math.max(0, lists.length - 1));
        if (kind.endsWith(':check') || a.includes('checked') || a.includes('unchecked')) {
          const done = (a.includes('checked') && !a.includes('unchecked')) || a.includes('done');
          out.push(indent + (done ? '- [x] ' : '- [ ] '));
        } else out.push(indent + (kind.startsWith('ol') ? '1. ' : '- '));
        break;
      }
      case 'input':
        if (a.includes('checkbox')) {
          const all = text(), line = all.slice(all.lastIndexOf('\n') + 1), done = /\bchecked\b/.test(a);
          if (line.trim() === '-' || !line.trim()) {
            const keep = all.slice(0, all.length - line.length) + (line.trim() === '-' ? line.slice(0, line.indexOf('-')) : line);
            out.length = 0;
            out.push(keep, done ? '- [x] ' : '- [ ] ');
          }
        }
        break;
      case 'a':
        if (!close) {
          const hr = /href\s*=\s*["']([^"']+)["']/i.exec(attrs);
          href = hr ? hr[1] : null;
          linkStart = text().length;
        } else if (href) {
          const words = text().slice(linkStart).trim();
          if (words !== href && /^https?:\/\//i.test(href)) out.push(` (${href})`);
          href = null;
        }
        break;
      case 'img': {
        const src = /src\s*=\s*["']([^"']+)["']/i.exec(attrs);
        if (src) {
          const name = pics.take(entities(src[1]), dir, n);
          if (name) out.push(`\n![[${name}]]\n`);
        }
        break;
      }
      default: break;
    }
  }
  out.push(entities(html.slice(at).replace(/\s+/g, ' ')));
  return text().split('\n').map(l => l.replace(/[ \t ]+$/, '').replace(/^ (?=\S)/, '').split('****').join('').split('~~~~').join(''))
    .join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

function entities(s) {
  return s.replace(/&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos|nbsp|#39);/g, (all, e) => {
    switch (e) {
      case 'amp': return '&';
      case 'lt': return '<';
      case 'gt': return '>';
      case 'quot': return '"';
      case 'apos': case '#39': return "'";
      case 'nbsp': return ' ';
      default:
        try { return String.fromCodePoint(e.startsWith('#x') ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10)); } catch { return all; }
    }
  });
}

// ---------------------------------------------------------------- pictures

function picsOf(byPath, byName, namer, found) {
  const find = (ref, dir) => byPath.get(normalize((dir ? dir + '/' : '') + ref).toLowerCase())
    || byPath.get(normalize(ref).toLowerCase()) || byName.get(base(ref).toLowerCase());
  return {
    /** The picture at `ref` (relative to `dir`, by its name anywhere, or a data: URI), or null. */
    take(ref, dir, n) {
      ref = ref.trim();
      let bytes, x;
      if (ref.startsWith('data:image/')) {
        const comma = ref.indexOf(','), slash = ref.indexOf('/'), semi = ref.indexOf(';');
        if (comma < 0 || !ref.slice(0, comma).endsWith(';base64')) return null;
        x = ref.slice(slash + 1, semi > 0 ? semi : comma).toLowerCase().replace('jpeg', 'jpg');
        try { bytes = Uint8Array.from(atob(ref.slice(comma + 1).replace(/\s/g, '')), c => c.charCodeAt(0)); } catch { return null; }
      } else {
        if (/^[a-z]+:\/\//i.test(ref)) return null; // Online: not a file we have.
        let clean = decode(ref.split(/[?#]/)[0]);
        x = ext(clean);
        if (!PICTURE_EXT.has(x)) {
          for (const e of ['jpg', 'png', 'jpeg', 'gif', 'webp']) {
            if (find(clean + '.' + e, dir)) { clean += '.' + e; x = e; break; }
          }
        }
        if (!PICTURE_EXT.has(x)) return null;
        const e = find(clean, dir);
        if (!e) return null;
        bytes = e.bytes;
      }
      if (!PICTURE_EXT.has(x)) return null;
      const name = namer('IMG', x === 'jpeg' ? 'jpg' : x);
      n.files.set(name, bytes);
      found.pictures++;
      return name;
    },
  };
}

function imageLine(line) {
  const t = line.trim();
  let m = EMBED.exec(t);
  if (m) return m[1];
  m = MD_IMAGE.exec(t);
  return m ? m[1] : null;
}

// ===================================================================== out

/**
 * Every note as Markdown in a folder named `root`: folders as folders, Archived and Templates too,
 * pictures and recordings in attachments/. Locked notes can't be read: they're listed in
 * "Not exported.md". `files(note, name)` gives a note's file (bytes, or null when not here).
 */
export async function write(notes, files, root) {
  const out = [], used = new Set(), attachments = new Set(), locked = [];
  const baseDir = root ? root + '/' : '';
  for (const n of notes) {
    const title = (n.title || '').trim();
    if (n.locked) { locked.push(title || 'An untitled ' + kindName(n.type).toLowerCase()); continue; }
    const dir = n.template ? 'Templates/' : n.archived ? 'Archived/' : n.folder && n.folder.trim() ? safe(n.folder) + '/' : '';
    const name = safe(title || 'Untitled ' + kindName(n.type).toLowerCase());
    let path = baseDir + dir + name + '.md';
    for (let k = 2; used.has(path.toLowerCase()); k++) path = baseDir + dir + name + ` (${k}).md`;
    used.add(path.toLowerCase());
    const up = dir ? '../' : '';
    const missing = [];
    const attach = async file => {
      const bytes = await files(n, file);
      if (!bytes) { missing.push(file); return null; }
      let at = 'attachments/' + file;
      for (let k = 2; attachments.has(at.toLowerCase()); k++) at = `attachments/${k}-${file}`;
      attachments.add(at.toLowerCase());
      out.push({ path: baseDir + at, bytes });
      return up + at;
    };

    let md = '---\n' + `title: ${quote(title)}\n` + `type: ${kindName(n.type).toLowerCase()}\n`;
    if (n.created > 0) md += `created: ${iso(n.created)}\n`;
    if (n.edited > 0) md += `updated: ${iso(n.edited)}\n`;
    if (n.pinned) md += 'pinned: true\n';
    if (n.color > 0 && n.color < COLOR_NAMES.length) md += `colour: ${COLOR_NAMES[n.color]}\n`;
    if (n.folder && n.folder.trim()) md += `folder: ${quote(n.folder.trim())}\n`;

    let body = '';
    if (n.type === LIST) {
      for (const item of todos(n.data)) {
        const m = EMBED.exec((item.text || '').trim());
        if (m) {
          const at = await attach(m[1]);
          body += (at ? `![](${at})` : `![[${m[1]}]]`) + '\n';
          continue;
        }
        body += (item.done ? '- [x] ' : '- [ ] ') + item.text.replace(/\n/g, ' ');
        if (due.dated(item)) {
          body += ` 📅 ${item.due}`;
          if (item.time) body += ` ⏰ ${item.time}`;
          if (due.repeats(item)) body += ` 🔁 ${due.repeatLong(item.repeat).toLowerCase()}`;
        }
        body += '\n';
      }
    } else if (n.type === JOURNAL) {
      const j = journal(n.data, n.edited);
      md += `day: ${isoDay(j.day)}\n`;
      if (j.mood) md += `mood: ${j.mood}\n`;
      body += (await pictures(n.content, attach)) + '\n';
      for (const m of j.media) {
        const at = await attach(m);
        body += '\n' + (at ? `![](${at})` : `![[${m}]]`);
      }
    } else if (n.type === MINDMAP) {
      const map = mindMap(n.data), seen = new Set();
      const outline = async (node, depth) => {
        if (seen.has(node.id)) return;
        seen.add(node.id);
        const pad = '  '.repeat(depth);
        body += `${pad}- ${node.text ? node.text.replace(/\n/g, ' ') : '(idea)'}\n`;
        if (node.sub) body += `${pad}  _${node.sub.replace(/\n/g, ' ')}_\n`;
        if (node.image) {
          const at = await attach(node.image);
          if (at) body += `${pad}  ![](${at})\n`;
        }
        for (const c of map.nodes) if (c.parent === node.id) await outline(c, depth + 1);
      };
      for (const r of map.nodes) if (r.parent < 0) await outline(r, 0);
    } else if (n.type === SKETCH) {
      const at = await attach('sketch.png');
      if (at) body += `![](${at})\n`;
    } else if (n.type === AUDIO) {
      for (const r of recordings(n.data)) {
        const at = await attach(r.file);
        const label = r.label || r.file;
        body += '- ' + (at ? `[${label}](${at.replace(/ /g, '%20')})` : label) + '\n';
      }
    } else {
      body += await pictures(n.content, attach);
    }
    md += '---\n\n' + body.trim() + '\n';
    if (missing.length) {
      md += `\n> ${missing.length === 1 ? "1 picture or recording isn't" : missing.length + " pictures or recordings aren't"}`
        + " on this device yet (they're in Google Drive): open the note in Notes+ once to bring them here.\n";
    }
    out.unshift({ path, bytes: enc.encode(md) });
  }
  if (locked.length) {
    out.push({ path: baseDir + 'Not exported.md', bytes: enc.encode('# Not exported\n\nLocked notes can only be read with their lock, so these weren\'t exported. '
      + 'Unlock one in Notes+ (and remove its lock) to export it.\n\n' + locked.map(t => `- ${t}\n`).join('')) });
  }
  return out;
}

async function pictures(content, attach) {
  const out = [];
  for (let line of (content || '').split('\n')) {
    const m = EMBED.exec(line.trim());
    if (m) { const at = await attach(m[1]); if (at) line = `![](${at})`; }
    out.push(line);
  }
  return out.join('\n').trim();
}

// ================================================================= helpers

function listNote(n, items) {
  n.type = LIST;
  n.data = todosJson(items);
  n.content = items.filter(i => !EMBED.test((i.text || '').trim()) && i.text.trim()).map(i => i.text.trim()).join('\n');
}

function fileFolder(n, rel) {
  const parts = rel.split('/');
  if (parts.length < 2) return;
  const first = parts[0];
  if (first.toLowerCase() === 'archived') {
    n.archived = true;
    if (parts.length > 2 && !n.folder) n.folder = clip(parts[1], 100);
  } else if (first.toLowerCase() === 'templates') {
    n.template = true;
  } else if (!n.folder && first.toLowerCase() !== 'notes') { // Apple's default folder is just "Notes".
    n.folder = clip(first, 100);
  }
}

function commonRoot(files) {
  if (!files.length) return '';
  let root = parent(files[0].path);
  for (const e of files) {
    while (root && !(e.path + '/').startsWith(root + '/')) root = parent(root);
    if (!root) break;
  }
  return root ? root + '/' : '';
}

const hidden = path => path.split('/').some(p => p.startsWith('.') || p === '__MACOSX');
const inAttachments = path => path.split('/').some(p => ['attachments', '_resources', 'assets'].includes(p.toLowerCase()));

export function safe(name) {
  let s = name.replace(/[\\/:*?"<>|\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim();
  s = s.replace(/^\.+/, '').replace(/[. ]+$/, '');
  if (s.length > 80) s = s.slice(0, 80).trim();
  return s || 'Untitled';
}

const kindName = type => ({ [LIST]: 'List', [JOURNAL]: 'Journal', [MINDMAP]: 'Mind map', [SKETCH]: 'Sketch', [AUDIO]: 'Recording' }[type] || 'Text');

function colorIndex(name) {
  if (!name) return 0;
  const t = String(name).trim().toLowerCase();
  for (let i = 1; i < COLOR_NAMES.length; i++) if (COLOR_NAMES[i].toLowerCase() === t || (t === 'gray' && i === 6)) return i;
  return 0;
}

const pad2 = x => String(x).padStart(2, '0');
const iso = ms => new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z');
const isoDay = ms => { const d = new Date(ms); return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`; };

/** A date in front matter ("2026-11-03T13:42:00Z", "2026-11-03 13:42", "2026-11-03"), or 0. Without a zone: this device's time. */
export function date(s) {
  if (!s) return 0;
  s = String(s).trim();
  let m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d{3})?)?(Z|[+-]\d{2}:\d{2})?$/.exec(s);
  if (!m) return 0;
  const [, y, mo, d, h = '0', mi = '0', se = '0', zone] = m;
  if (zone) return Date.parse(s.replace(' ', 'T'));
  const t = new Date(+y, +mo - 1, +d, +h, +mi, +se);
  return t.getMonth() === +mo - 1 ? t.getTime() : 0;
}

const quote = s => (/^[\p{L}\p{N} _.,()'&!-]*$/u.test(s) && s && !s.startsWith(' ') ? s : '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"');

function unquote(s) {
  if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'")))) {
    s = s.slice(1, -1);
    if (s.includes('\\')) s = s.replace(/\\"/g, '"').replace(/\\\\/g, '\\');
  }
  return s;
}

function decode(s) {
  try { return decodeURIComponent(s.replace(/\+/g, '%2B')); } catch { return s; }
}

function normalize(path) {
  const out = [];
  for (const p of path.replace(/\\/g, '/').split('/')) {
    if (!p || p === '.') continue;
    if (p === '..') out.pop(); else out.push(p);
  }
  return out.join('/');
}
