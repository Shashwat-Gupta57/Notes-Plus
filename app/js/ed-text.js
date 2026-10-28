// Text notes, journals and lists (the apps' NoteEditorActivity / NoteEditorView).
//
// Text and journal bodies are blocks: words, and pictures between them stored as a line of
// their own, `![[IMG_….png]]`, exactly as the desktop writes them, so a note with pictures
// reads the same everywhere. Pictures come from the button, a paste, or a drop.

import { h, icon, iconButton, toast, choices, dialog } from './ui.js';
import { richEditor } from './rich.js';
import { TEXT, LIST, JOURNAL, PICTURE_LINE, todos, todosJson, wraps, journal, journalJson, MOODS, mediaName, isVideo, dayStart } from './model.js';

function autoGrow(ta) {
  const fit = () => { ta.style.height = 'auto'; ta.style.height = ta.scrollHeight + 'px'; };
  ta.addEventListener('input', fit);
  requestAnimationFrame(fit);
  ta._fit = fit;
  return ta;
}

function titleField(ctx, placeholder, onEnter) {
  const t = autoGrow(h('textarea.title-field', { rows: 1, placeholder, readOnly: ctx.readOnly, maxlength: 500 }));
  t.value = ctx.note.title || '';
  t.addEventListener('input', () => ctx.changed());
  t.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onEnter && onEnter(); }
  });
  return t;
}

const extOf = blob => ((blob.type || '').split('/')[1] || 'png').replace('jpeg', 'jpg').replace(/[^a-z0-9]/g, '') || 'png';

/**
 * Words with pictures between them: blocks of formatted words (rich.js, edited as they look,
 * kept as Markdown), and pictures, each a line of its own `![[IMG_….png]]`.
 */
function richBody(ctx, content, placeholder) {
  const wrap = h('div.rich');
  const blocks = [];
  let active = null;
  let onCaret = () => {};

  function textBlock(md) {
    const ed = richEditor({
      readOnly: ctx.readOnly, placeholder: '',
      onChange: () => ctx.changed(),
      onFocus: e => { active = e; onCaret(); },
    });
    ed.setMarkdown(md);
    const block = { kind: 'text', node: ed.node, ed, value: () => ed.markdown() };
    ed.node.addEventListener('paste', e => {
      const files = [...(e.clipboardData ? e.clipboardData.files : [])].filter(f => f.type.startsWith('image/'));
      if (!files.length || ctx.readOnly) return;
      e.preventDefault();
      files.forEach(f => insertPicture(f, block));
    });
    ed.node.addEventListener('keydown', e => {
      // Backspace at the very start, right after a picture, removes the picture.
      if (e.key !== 'Backspace' || ctx.readOnly) return;
      const sel = getSelection();
      if (!sel.isCollapsed || !sel.rangeCount) return;
      const r = document.createRange();
      r.selectNodeContents(ed.node);
      r.setEnd(sel.anchorNode, sel.anchorOffset);
      if (r.toString().replace(/​/g, '') !== '') return;
      const i = blocks.indexOf(block);
      const prev = blocks[i - 1];
      if (prev && prev.kind === 'pic') { e.preventDefault(); removeBlock(prev); }
    });
    return block;
  }

  function picBlock(name) {
    const img = h('img', { alt: '' });
    ctx.fileUrl(name).then(u => {
      if (u) img.src = u;
      else fig.classList.add('missing');
    });
    const del = ctx.readOnly ? null : h('button.pic-remove', { type: 'button', title: 'Remove picture', onClick: () => removeBlock(block) }, icon('close', 18, '#fff'));
    const fig = h('figure.pic', img, del, h('figcaption', 'Picture on another device'));
    img.addEventListener('click', () => { if (img.src) window.open(img.src, '_blank'); });
    const block = { kind: 'pic', node: fig, name, value: () => `![[${name}]]` };
    return block;
  }

  function removeBlock(b) {
    const i = blocks.indexOf(b);
    if (i < 0) return;
    blocks.splice(i, 1);
    b.node.remove();
    // Two runs of words now meet: they become one.
    const a = blocks[i - 1], c = blocks[i];
    if (a && c && a.kind === 'text' && c.kind === 'text') {
      const x = a.value(), y = c.value();
      a.ed.setMarkdown(x && y ? x + '\n' + y : x + y);
      blocks.splice(i, 1);
      c.node.remove();
      a.ed.focusEnd();
    }
    ctx.changed();
  }

  async function insertPicture(blob, at) {
    const name = mediaName('IMG', extOf(blob));
    await ctx.putFile(name, blob);
    let i = blocks.indexOf(at);
    if (i < 0) i = blocks.length - 1;
    const target = blocks[i];
    let after = '';
    if (target.kind === 'text') {
      const [before, rest] = target.ed.split();
      after = rest;
      target.ed.setMarkdown(before);
    }
    const pic = picBlock(name), next = textBlock(after);
    blocks.splice(i + 1, 0, pic, next);
    target.node.after(pic.node, next.node);
    next.ed.focusStart();
    ctx.changed();
  }

  // Parse: runs of words, and picture lines.
  let run = [];
  const flush = () => { blocks.push(textBlock(run.join('\n'))); run = []; };
  for (const line of (content || '').split('\n')) {
    const m = PICTURE_LINE.exec(line.trim());
    if (m) { flush(); blocks.push(picBlock(m[1])); } else run.push(line);
  }
  flush();
  blocks[0].node.dataset.placeholder = placeholder;
  wrap.append(...blocks.map(b => b.node));

  const activeBlock = () => (active ? blocks.find(b => b.ed === active) : null) || blocks[blocks.length - 1];
  wrap.addEventListener('dragover', e => { if (!ctx.readOnly && e.dataTransfer.types.includes('Files')) e.preventDefault(); });
  wrap.addEventListener('drop', e => {
    const files = [...e.dataTransfer.files].filter(f => f.type.startsWith('image/'));
    if (!files.length || ctx.readOnly) return;
    e.preventDefault();
    files.forEach(f => insertPicture(f, activeBlock()));
  });
  document.addEventListener('selectionchange', () => {
    const sel = getSelection();
    if (sel.rangeCount && wrap.contains(sel.anchorNode)) onCaret();
  });

  return {
    node: wrap,
    value() {
      const parts = [];
      for (const b of blocks) {
        const v = b.value();
        if (b.kind === 'text' && v === '' && blocks.length > 1) continue;
        parts.push(v);
      }
      return parts.join('\n');
    },
    words() {
      return blocks.filter(b => b.kind === 'text').map(b => b.ed.text()).join(' ').trim().split(/\s+/).filter(Boolean).length;
    },
    focusEnd() { [...blocks].reverse().find(b => b.kind === 'text').ed.focusEnd(); },
    focusStart() { blocks.find(b => b.kind === 'text').ed.focusStart(); },
    addPicture(files) { [...files].forEach(f => insertPicture(f, activeBlock())); },
    editor: () => active || blocks.find(b => b.kind === 'text').ed,
    onCaret(f) { onCaret = f; },
  };
}

function pickFiles(accept, multiple, onFiles) {
  const input = h('input', { type: 'file', accept, multiple });
  input.addEventListener('change', () => { if (input.files.length) onFiles(input.files); });
  input.click();
}

function footer(ctx, getExtraText) {
  const el = h('div.editor-footer');
  const update = () => {
    const extra = typeof getExtraText === 'function' ? getExtraText() : getExtraText;
    const statusText = ctx && ctx.syncStatus ? `  ·  ${ctx.syncStatus}` : '';
    el.textContent = (extra || '') + statusText;
  };
  if (ctx && ctx.onSyncStatus) ctx.onSyncStatus(update);
  update();
  el._update = update;
  return el;
}

const editedLine = note => 'Edited ' + new Date(note.timestamp || Date.now())
  .toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

// ========================================================= formatting

const TEXT_COLORS = [
  ['Automatic', null], ['Yellow', '#E6A700'], ['Orange', '#FF9500'], ['Red', '#FF3B30'], ['Pink', '#FF2D55'],
  ['Purple', '#AF52DE'], ['Blue', '#007AFF'], ['Teal', '#30B0C7'], ['Green', '#34C759'], ['Grey', '#8E8E93'],
];
const BLOCK_NAMES = { NORMAL: 'Normal text', H1: 'Heading 1', H2: 'Heading 2', H3: 'Heading 3', QUOTE: 'Quote' };

/**
 * Word's formatting row: paragraph style, bold, italic, underline, strike, code, colour, lists,
 * tasks. Every button acts on the words themselves; the buttons light up for what the caret is on.
 */
function formatBar(ctx, body) {
  if (ctx.readOnly) return null;
  const btn = (title, ic, onClick, label) =>
    h('button.fmt-btn', { type: 'button', title, onmousedown: e => { e.preventDefault(); onClick(); } }, ic ? icon(ic, 18) : null, label || null);
  const ed = () => body.editor();
  const para = h('button.fmt-dropdown-btn', { type: 'button', title: 'Paragraph style (Ctrl+Alt+1, 2, 3; Ctrl+Alt+0 normal)', onmousedown: e => {
    e.preventDefault();
    choices('Paragraph style', Object.entries(BLOCK_NAMES).map(([k, v]) => ({ text: v, onClick: () => ed().setBlock(k) })));
  } }, 'Normal text');
  const bold = btn('Bold (Ctrl+B)', 'bold', () => ed().cmd('bold'));
  const italic = btn('Italic (Ctrl+I)', 'italic', () => ed().cmd('italic'));
  const underline = btn('Underline (Ctrl+U)', 'underline', () => ed().cmd('underline'));
  const strike = btn('Strikethrough (Ctrl+Shift+X)', 'strikethrough', () => ed().cmd('strikeThrough'));
  const code = btn('Code (Ctrl+E)', 'formatCode', () => ed().toggleCode());
  const color = btn('Text colour', 'palette', () => choices('Text colour', TEXT_COLORS.map(([n, hex]) => ({ text: n, onClick: () => ed().color(hex) }))));
  const bullets = btn('Bulleted list (Ctrl+Shift+L, or type "- ")', 'formatListBulleted', () => ed().setBlock('BULLET'));
  const numbers = btn('Numbered list (type "1. ")', 'formatListNumbered', () => ed().setBlock('NUMBER'));
  const tasks = btn('Tick boxes (type "[] ")', 'check', () => ed().setBlock('TASK'));
  const bar = h('div.format-bar', para, h('div.format-sep'), h('div.format-group', bold, italic, underline, strike, code, color),
    h('div.format-sep'), h('div.format-group', bullets, numbers, tasks));
  body.onCaret(() => {
    const s = ed().state();
    for (const [b, on] of [[bold, s.bold], [italic, s.italic], [underline, s.underline], [strike, s.strike],
      [bullets, s.block === 'BULLET'], [numbers, s.block === 'NUMBER'], [tasks, s.block === 'TASK' || s.block === 'TASK_DONE']]) b.classList.toggle('on', !!on);
    para.textContent = BLOCK_NAMES[s.block] || 'Normal text';
  });
  return bar;
}

// ================================================================== text

export function textEditor(ctx) {
  const n = ctx.note;
  let body;
  const title = titleField(ctx, 'Title', () => body.focusStart());
  body = richBody(ctx, n.content, 'Note');
  const toolbar = formatBar(ctx, body);
  const foot = footer(ctx, () => editedLine(ctx.note) + '  ·  ' + body.words() + (body.words() === 1 ? ' word' : ' words'));
  body.node.addEventListener('input', () => foot._update());
  const addPic = ctx.readOnly ? null : h('button.chip-btn', { type: 'button', onClick: () => pickFiles('image/*', true, f => body.addPicture(f)) }, icon('photo', 18), 'Add picture');
  const page = h('div.page', title, toolbar, body.node, h('div.page-actions', addPic), foot);
  page.addEventListener('click', e => { if (e.target === page && !ctx.readOnly) body.focusEnd(); });
  return {
    node: page,
    collect(note) {
      note.title = title.value.trim();
      note.content = body.value().trim();
      return !!(note.content || note.title);
    },
    focus(isNew) { if (isNew) title.focus(); },
  };
}

// =============================================================== journal

export function journalEditor(ctx) {
  const n = ctx.note;
  const j = journal(n.data, dayStart(n.timestamp || Date.now()));
  let body;
  const title = titleField(ctx, 'Give the day a title', () => body.focusStart());
  body = richBody(ctx, n.content, 'What happened today?');

  const day = h('div.j-day'), weekday = h('div.j-weekday'), month = h('div.j-month');
  const dateInput = h('input.j-date-input', { type: 'date', tabindex: -1 });
  const showDay = () => {
    const d = new Date(j.day);
    day.textContent = d.getDate();
    weekday.textContent = d.toLocaleDateString([], { weekday: 'long' });
    month.textContent = d.toLocaleDateString([], { month: 'long', year: 'numeric' });
    dateInput.value = new Date(j.day - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  };
  dateInput.addEventListener('change', () => {
    if (!dateInput.value) return;
    const [y, m, dd] = dateInput.value.split('-').map(Number);
    j.day = new Date(y, m - 1, dd).getTime();
    showDay();
    ctx.changed();
  });
  const header = h('div.j-header', { title: ctx.readOnly ? null : 'Change the day', onClick: () => { if (!ctx.readOnly) (dateInput.showPicker ? dateInput.showPicker() : dateInput.click()); } },
    day, h('div', weekday, month), dateInput);
  showDay();

  const moods = h('div.moods');
  const paintMoods = () => moods.querySelectorAll('.mood').forEach(b => b.classList.toggle('on', b.textContent === j.mood));
  for (const m of MOODS) {
    moods.appendChild(h('button.mood', { type: 'button', disabled: ctx.readOnly, onClick: () => { j.mood = j.mood === m ? '' : m; paintMoods(); ctx.changed(); } }, m));
  }
  paintMoods();

  const strip = h('div.j-media');
  function showMedia() {
    strip.replaceChildren();
    for (const name of j.media) {
      const video = isVideo(name);
      const el = video ? h('video', { muted: true, playsinline: true, preload: 'metadata' }) : h('img', { alt: '' });
      ctx.fileUrl(name).then(u => { if (u) el.src = u; else tile.classList.add('missing'); });
      const tile = h('div.j-tile', el, video ? h('span.j-play', icon('play', 22, '#fff')) : null,
        ctx.readOnly ? null : h('button.pic-remove', { type: 'button', title: 'Remove', onClick: e => { e.stopPropagation(); j.media = j.media.filter(x => x !== name); showMedia(); ctx.changed(); } }, icon('close', 16, '#fff')));
      tile.addEventListener('click', () => { if (el.src) window.open(el.src, '_blank'); });
      strip.appendChild(tile);
    }
    if (!ctx.readOnly) {
      strip.appendChild(h('button.j-add', { type: 'button', title: 'Add photos or videos', onClick: () => pickFiles('image/*,video/*', true, async files => {
        for (const f of files) {
          const name = mediaName(f.type.startsWith('video/') ? 'VID' : 'IMG', extOf(f));
          await ctx.putFile(name, f);
          j.media.push(name);
        }
        showMedia();
        ctx.changed();
      }) }, icon('add', 26)));
    }
  }
  showMedia();

  const foot = footer(ctx, () => editedLine(ctx.note) + '  ·  ' + body.words() + (body.words() === 1 ? ' word' : ' words'));
  body.node.addEventListener('input', () => foot._update());
  const addPic = ctx.readOnly ? null : h('button.chip-btn', { type: 'button', onClick: () => pickFiles('image/*', true, f => body.addPicture(f)) }, icon('photo', 18), 'Picture in the text');
  const toolbar = formatBar(ctx, body);
  return {
    node: h('div.page', header, moods, strip, title, toolbar, body.node, h('div.page-actions', addPic), foot),
    collect(note) {
      note.title = title.value.trim();
      note.content = body.value().trim();
      note.data = journalJson(j);
      return !!(note.content || note.title || j.media.length || j.mood);
    },
    focus(isNew) { if (isNew) body.focusStart(); },
  };
}

// ================================================================== list

export function listEditor(ctx) {
  const n = ctx.note;
  const items = todos(n.data);
  let wrapItems = wraps(n.data);
  if (!items.length) items.push({ text: '', done: false });
  const title = titleField(ctx, 'List name', () => focusItem(0));
  const rows = h('div.list-rows');
  const bar = h('div.progress-fill');
  const progress = h('div.list-progress');

  // A picture between the items is an item of its own, `![[name]]` (the text notes' embed line).
  const pictureOf = item => { const m = PICTURE_LINE.exec((item.text || '').trim()); return m ? m[1] : null; };

  function updateProgress() {
    const real = items.filter(i => i.text.trim() && !pictureOf(i));
    const done = real.filter(i => i.done).length;
    progress.textContent = !real.length ? 'Nothing yet' : done === real.length ? 'All done 🎉' : `${done} of ${real.length} done`;
    bar.style.width = (real.length ? (done * 100) / real.length : 0) + '%';
  }

  /** Pasted or dropped pictures go in as items at `i` (in place of an empty item there). */
  async function addPictures(files, i) {
    for (const f of files) {
      const name = mediaName('IMG', extOf(f));
      await ctx.putFile(name, f);
      const at = Math.max(0, Math.min(i, items.length));
      if (items[at] && !items[at].text.trim() && !pictureOf(items[at]) && items.length > 1) removeAt(at);
      const item = { text: `![[${name}]]`, done: false };
      items.splice(at, 0, item);
      const el = row(item);
      if (at >= rows.children.length) rows.appendChild(el); else rows.children[at].before(el);
      i = at + 1;
    }
    updateProgress();
    ctx.changed();
  }

  function pictureRow(item, name) {
    const img = h('img', { alt: '' });
    const fig = h('figure.pic.item-pic', img, h('figcaption', 'Picture on another device'));
    ctx.fileUrl(name).then(u => { if (u) img.src = u; else fig.classList.add('missing'); });
    img.addEventListener('click', () => { if (img.src) window.open(img.src, '_blank'); });
    const handle = h('span.handle', { title: 'Drag to reorder' }, icon('noteList', 18));
    const del = ctx.readOnly ? null : h('button.item-remove', { type: 'button', title: 'Remove picture' }, icon('close', 18));
    const el = h('div.item.picture-item', handle, fig, del);
    if (del) del.addEventListener('click', () => { removeAt(items.indexOf(item)); if (!items.length) addAt(0); });
    dragByHandle(item, el, handle);
    el._field = null;
    return el;
  }

  function row(item) {
    const picture = pictureOf(item);
    if (picture) return pictureRow(item, picture);
    const field = wrapItems
      ? autoGrow(h('textarea.item-text', { rows: 1, placeholder: 'Item', readOnly: ctx.readOnly }))
      : h('input.item-text', { type: 'text', placeholder: 'Item', readOnly: ctx.readOnly });
    field.value = item.text;
    const check = h('button.check' + (item.done ? '.on' : ''), { type: 'button', role: 'checkbox', 'aria-checked': String(item.done), disabled: ctx.readOnly }, icon('check', 16, '#fff'));
    const handle = h('span.handle', { title: 'Drag to reorder' }, icon('noteList', 18));
    const del = ctx.readOnly ? null : h('button.item-remove', { type: 'button', title: 'Delete item' }, icon('close', 18));
    const el = h('div.item' + (item.done ? '.done' : ''), handle, check, field, del);
    const setDone = d => {
      item.done = d;
      el.classList.toggle('done', d);
      check.classList.toggle('on', d);
      check.setAttribute('aria-checked', String(d));
      updateProgress();
      ctx.changed();
    };
    check.addEventListener('click', () => setDone(!item.done));
    field.addEventListener('input', () => { item.text = field.value.replace(/\n/g, ' '); updateProgress(); ctx.changed(); });
    field.addEventListener('paste', e => {
      const files = [...(e.clipboardData ? e.clipboardData.files : [])].filter(f => f.type.startsWith('image/'));
      if (!files.length || ctx.readOnly) return;
      e.preventDefault();
      const i = items.indexOf(item);
      addPictures(files, item.text.trim() ? i + 1 : i);
    });
    field.addEventListener('keydown', e => {
      const i = items.indexOf(item);
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); setDone(!item.done); }
      else if (e.key === 'Enter') { e.preventDefault(); addAt(i + 1); }
      else if (e.key === 'Backspace' && !field.value && items.length > 1) { e.preventDefault(); removeAt(i); focusItem(Math.max(0, i - 1), true); }
      else if (e.key === 'ArrowUp' && !wrapItems) { e.preventDefault(); focusItem(i - 1, true); }
      else if (e.key === 'ArrowDown' && !wrapItems) { e.preventDefault(); focusItem(i + 1, true); }
    });
    if (del) del.addEventListener('click', () => { removeAt(items.indexOf(item)); if (!items.length) addAt(0); });
    dragByHandle(item, el, handle);
    el._field = field;
    return el;
  }

  /** Drag by the handle: the others make room as it passes their middle. */
  function dragByHandle(item, el, handle) {
    handle.addEventListener('pointerdown', e => {
      if (ctx.readOnly) return;
      e.preventDefault();
      handle.setPointerCapture(e.pointerId);
      el.classList.add('dragging');
      const move = ev => {
        const from = items.indexOf(item);
        const kids = [...rows.children];
        let to = from;
        kids.forEach((k, idx) => {
          const r = k.getBoundingClientRect(), mid = r.top + r.height / 2;
          if (idx < from && ev.clientY < mid && to === from) to = idx;
          if (idx > from && ev.clientY > mid) to = idx;
        });
        if (to !== from) {
          items.splice(from, 1);
          items.splice(to, 0, item);
          if (to > from) kids[to].after(el); else kids[to].before(el);
          ctx.changed();
        }
      };
      const up = () => {
        el.classList.remove('dragging');
        handle.removeEventListener('pointermove', move);
        handle.removeEventListener('pointerup', up);
      };
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', up);
    });
  }

  function render() {
    rows.replaceChildren(...items.map(row));
    updateProgress();
  }

  function addAt(i) {
    const item = { text: '', done: false };
    items.splice(i, 0, item);
    const el = row(item);
    if (i >= rows.children.length) rows.appendChild(el); else rows.children[i].before(el);
    updateProgress();
    el._field.focus();
  }

  function removeAt(i) {
    if (i < 0 || i >= items.length) return;
    items.splice(i, 1);
    rows.children[i].remove();
    updateProgress();
    ctx.changed();
  }

  function focusItem(i, atEnd) {
    const el = rows.children[i];
    if (!el) return;
    if (!el._field) { if (i + (atEnd ? -1 : 1) !== i) focusItem(i + (atEnd ? -1 : 1), atEnd); return; }
    el._field.focus();
    if (atEnd) { const l = el._field.value.length; el._field.setSelectionRange(l, l); }
  }

  render();
  rows.addEventListener('dragover', e => { if (!ctx.readOnly && e.dataTransfer.types.includes('Files')) e.preventDefault(); });
  rows.addEventListener('drop', e => {
    const files = [...e.dataTransfer.files].filter(f => f.type.startsWith('image/'));
    if (!files.length || ctx.readOnly) return;
    e.preventDefault();
    addPictures(files, items.length);
  });
  const add = ctx.readOnly ? null : h('button.add-row', { type: 'button', onClick: () => addAt(items.length) }, icon('add', 24, 'var(--tile-green)'), 'Add item');
  const foot = footer(ctx, () => editedLine(ctx.note));
  return {
    node: h('div.page', title, h('div.progress-track', bar), progress, rows, add, foot),
    collect(note) {
      note.title = title.value.trim();
      const kept = items.filter(i => i.text.trim());
      note.data = todosJson(kept, wrapItems);
      note.content = kept.map(i => i.text.trim()).join('\n');
      return !!(kept.length || note.title);
    },
    wrap: () => wrapItems,
    setWrap(w) { wrapItems = w; render(); },
    focus(isNew) { if (isNew) title.focus(); },
  };
}

export const forType = { [TEXT]: textEditor, [LIST]: listEditor, [JOURNAL]: journalEditor };
