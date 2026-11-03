// Links between notes: [[Note title]] (or [[Note title|words shown]]) written in a note's words or
// list items, the way Obsidian writes them. Matched by title, ignoring case and outer spaces; when
// titles repeat, the newest note wins. A picture line (![[IMG_….png]]) is never a link. Mind-map
// ideas link to notes by uuid instead, and count as backlinks too.
//
// The logic is the apps' NoteLinks.java, case for case. Below it, how the web shows links: drawn with
// the CSS Highlight API (nothing is added to the text, so nothing can leak into the saved Markdown),
// opened with Ctrl/Cmd+click (a plain click in a note sent to me, or a tap's bar), and "[[" offers titles.

import { LIST, MINDMAP, todos, todosJson, wraps, mindMap, mindMapJson, kind } from './model.js';
import { h, icon, snackbar } from './ui.js';

export const LINK = /(?<!!)\[\[([^[\]|\n]+)(\|[^[\]\n]*)?\]\]/g;
export const TYPING = /(?<!!)\[\[([^[\]|\n]{0,80})$/;

export const key = title => (title || '').trim().toLowerCase();

/** The titles `text` links to, each once, in order. */
export function titlesIn(text) {
  const seen = new Set(), out = [];
  if (!text || !text.includes('[[')) return out;
  for (const m of text.matchAll(LINK)) {
    const t = m[1].trim();
    if (t && !seen.has(key(t))) { seen.add(key(t)); out.push(t); }
  }
  return out;
}

/** Words for a card: "[[Trip|the trip]]" reads "the trip", "[[Trip]]" reads "Trip". */
export const shown = text => (!text || !text.includes('[[') ? text : text.replace(LINK, (all, title, alias) => (alias && alias.length > 1 ? alias.slice(1) : title.trim())));

export const linkable = n => !n.deleted && !n.template && !!key(n.title);

/** The note `title` opens: the newest linkable one with that title, or null. */
export function resolve(title, notes) {
  const k = key(title);
  if (!k) return null;
  let best = null;
  for (const n of notes) if (linkable(n) && key(n.title) === k && (!best || n.timestamp > best.timestamp)) best = n;
  return best;
}

/** The notes that link to `note` (by its title, or a mind-map idea pointing at its uuid). Newest first. */
export function backlinks(note, notes) {
  const k = key(note.title), target = k ? resolve(note.title, notes) : null;
  const byTitle = !!target && target.uuid === note.uuid;
  const out = [];
  for (const n of notes) {
    if (n.uuid === note.uuid || n.deleted) continue;
    const words = n.content || '';
    let links = byTitle && words.includes('[[') && words.toLowerCase().includes(k) && titlesIn(words).some(t => key(t) === k);
    if (!links && n.type === MINDMAP && n.data && n.data.includes(note.uuid)) {
      links = mindMap(n.data).nodes.some(x => x.linkUuid === note.uuid);
    }
    if (links) out.push(n);
  }
  return out.sort((a, b) => b.timestamp - a.timestamp);
}

/** `text` with its links to `from` pointing at `to` instead (words shown kept). */
export function renameIn(text, from, to) {
  if (!text || !text.includes('[[')) return text;
  const k = key(from);
  return text.replace(LINK, (all, title, alias) => (key(title) === k ? `[[${to.trim()}${alias || ''}]]` : all));
}

/** Note `n` with its links to `from` renamed: { content, data }, or null when it has none. */
export function renamed(n, from, to) {
  const content = n.content || '';
  const next = renameIn(content, from, to);
  if (next === content) return null;
  let data = n.data;
  if (n.type === LIST) {
    const items = todos(data);
    for (const it of items) it.text = renameIn(it.text, from, to);
    data = todosJson(items, wraps(data));
  } else if (n.type === MINDMAP) {
    const map = mindMap(data);
    for (const x of map.nodes) x.text = renameIn(x.text, from, to);
    data = mindMapJson(map);
  }
  return { content: next, data };
}

/** Titles to offer after "[[": those containing what's typed (starting with it first), newest first. */
export function suggestions(typed, notes, max = 8, except = null) {
  const q = key(typed), starts = [], contains = [];
  for (const n of notes) {
    if (!linkable(n) || n.uuid === except) continue;
    const k = key(n.title);
    if (k.startsWith(q)) starts.push(n); else if (k.includes(q)) contains.push(n);
  }
  const newest = (a, b) => b.timestamp - a.timestamp;
  const seen = new Set(), out = [];
  for (const n of [...starts.sort(newest), ...contains.sort(newest)]) {
    if (seen.has(key(n.title))) continue;
    seen.add(key(n.title));
    out.push(n.title.trim());
    if (out.length >= max) break;
  }
  return out;
}

// ======================================================================= on screen

const highlights = typeof CSS !== 'undefined' && CSS.highlights && typeof Highlight !== 'undefined';

/**
 * A formatted block of words: its links drawn, opened on Ctrl/Cmd+click (a plain click in a note
 * sent to me; a bar to open one after a tap), and "[[" offering titles.
 * ctx: { readOnly, linkNotes() → notes, openLink(title), onLinks(f) → off }.
 */
export function decorate(el, ctx) {
  const draw = () => paint(el, ctx);
  el.addEventListener('input', draw);
  const off = ctx.onLinks ? ctx.onLinks(draw) : null;
  requestAnimationFrame(draw);
  el.addEventListener('click', e => {
    const title = linkAt(e.clientX, e.clientY, el);
    if (!title) return;
    if (ctx.readOnly || e.ctrlKey || e.metaKey) {
      e.preventDefault();
      ctx.openLink(title);
    } else {
      const there = resolve(title, ctx.linkNotes());
      snackbar(there ? `“${title.trim()}”` : `No note called “${title.trim()}”`, there ? 'Open' : 'Create', () => ctx.openLink(title));
    }
  });
  if (!ctx.readOnly) suggest(el, ctx);
  return off;
}

// One set of drawn links for the page: only one note is open at a time.
const drawn = new Map();
function paint(el, ctx) {
  if (!highlights) return;
  const notes = ctx.linkNotes(), there = [], missing = [];
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node.nodeValue;
    if (!text.includes('[[')) continue;
    for (const m of text.matchAll(LINK)) {
      const r = new Range();
      r.setStart(node, m.index);
      r.setEnd(node, m.index + m[0].length);
      (resolve(m[1], notes) ? there : missing).push(r);
    }
  }
  drawn.set(el, { there, missing });
  for (const [k] of drawn) if (!k.isConnected) drawn.delete(k);
  const all = [...drawn.values()];
  CSS.highlights.set('note-link', new Highlight(...all.flatMap(d => d.there)));
  CSS.highlights.set('note-link-missing', new Highlight(...all.flatMap(d => d.missing)));
}

/** The link under a point in `el`, or null. */
function linkAt(x, y, el) {
  let node = null, offset = 0;
  if (document.caretPositionFromPoint) {
    const p = document.caretPositionFromPoint(x, y);
    if (p) { node = p.offsetNode; offset = p.offset; }
  } else if (document.caretRangeFromPoint) {
    const r = document.caretRangeFromPoint(x, y);
    if (r) { node = r.startContainer; offset = r.startOffset; }
  }
  if (!node || node.nodeType !== 3 || !el.contains(node)) return null;
  for (const m of node.nodeValue.matchAll(LINK)) if (offset > m.index && offset < m.index + m[0].length) return m[1];
  return null;
}

/** A list item's field: Ctrl/Cmd+click on a link opens it (the click has put the caret inside). */
export function linkClicks(field, ctx) {
  field.addEventListener('click', e => {
    if (!(e.ctrlKey || e.metaKey || ctx.readOnly)) return;
    const at = field.selectionStart;
    for (const m of field.value.matchAll(LINK)) {
      if (at > m.index && at < m.index + m[0].length) { e.preventDefault(); ctx.openLink(m[1]); return; }
    }
  });
}

/** "[[" in a contenteditable block or a text field offers titles under the caret. */
export function suggest(el, ctx) {
  const editable = el.isContentEditable || el.contentEditable === 'true';
  let box = null, shownTitles = [], selected = 0, typedLength = 0;
  const close = () => { if (box) { box.remove(); box = null; } };

  function before() {
    if (!editable) return el.value.slice(0, el.selectionStart).split('\n').pop();
    const sel = getSelection();
    if (!sel.rangeCount || !sel.isCollapsed || !el.contains(sel.anchorNode)) return null;
    const r = document.createRange();
    r.selectNodeContents(el);
    r.setEnd(sel.anchorNode, sel.anchorOffset);
    return r.toString().split('\n').pop();
  }

  function where() {
    if (editable) {
      const sel = getSelection();
      if (!sel.rangeCount) return null;
      const rect = sel.getRangeAt(0).getBoundingClientRect();
      return rect.height ? { x: rect.left, y: rect.bottom } : null;
    }
    const r = el.getBoundingClientRect();
    return { x: r.left + 8, y: r.bottom };
  }

  function choose(i) {
    const title = shownTitles[i];
    close();
    if (!title) return;
    if (editable) {
      const sel = getSelection();
      for (let k = 0; k < typedLength; k++) sel.modify('extend', 'backward', 'character');
      document.execCommand('insertText', false, title + ']]');
    } else {
      const at = el.selectionStart, end = el.value.slice(at, at + 2) === ']]' ? at + 2 : at;
      el.setRangeText(title + ']]', at - typedLength, end, 'end');
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }

  function paintBox() {
    if (!box) return;
    [...box.children].forEach((r, i) => r.classList.toggle('selected', i === selected));
  }

  function changed() {
    const text = before();
    const m = text == null ? null : TYPING.exec(text);
    if (!m) { close(); return; }
    const titles = suggestions(m[1], ctx.linkNotes(), 8, ctx.self ? ctx.self() : null);
    if (!titles.length) { close(); return; }
    shownTitles = titles;
    typedLength = m[1].length;
    selected = 0;
    const at = where();
    if (!at) { close(); return; }
    if (!box) {
      box = h('div.link-pop', { role: 'listbox' });
      box.addEventListener('mousedown', e => e.preventDefault()); // The caret stays where it is.
      document.body.appendChild(box);
    }
    box.replaceChildren(...titles.map((t, i) => h('div.link-pop-row', { role: 'option', onClick: () => choose(i) },
      icon('noteText', 16, 'var(--accent)'), h('span', t))));
    box.style.left = Math.max(8, Math.min(window.innerWidth - box.offsetWidth - 8, at.x - 8)) + 'px';
    box.style.top = Math.min(window.innerHeight - box.offsetHeight - 8, at.y + 4) + 'px';
    paintBox();
  }

  el.addEventListener('input', changed);
  el.addEventListener('blur', () => setTimeout(close, 150));
  el.addEventListener('keydown', e => {
    if (!box) return;
    if (e.key === 'ArrowDown') { e.preventDefault(); selected = Math.min(shownTitles.length - 1, selected + 1); paintBox(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); selected = Math.max(0, selected - 1); paintBox(); }
    else if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); e.stopImmediatePropagation(); choose(selected); }
    else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); }
  }, true);
}

/** "Linked from": a row of the notes linking here (opened with `open(uuid)`), or nothing. */
export function backlinksRow(from, open) {
  if (!from.length) return null;
  return h('div.backlinks', h('span.backlinks-label', 'Linked from'),
    ...from.map(n => {
      const k = kind(n.type);
      const t = (n.title || '').trim() || k.label;
      return h('button.backlink', { type: 'button', onClick: () => open(n.uuid) },
        icon(k.icon, 15, `var(--${k.accent})`), t.length > 40 ? t.slice(0, 40) + '…' : t);
    }));
}
