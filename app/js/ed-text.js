// Text notes, journals and lists (the apps' NoteEditorActivity / NoteEditorView).
//
// Text and journal bodies are blocks: words, and pictures between them stored as a line of
// their own, `![[IMG_….png]]`, exactly as the desktop writes them, so a note with pictures
// reads the same everywhere. Pictures come from the button, a paste, or a drop.

import { h, icon, iconButton, toast, choices, dialog } from './ui.js';
import { object } from './model.js';
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

/** Words with pictures between them. */
function richBody(ctx, content, placeholder) {
  const wrap = h('div.rich');
  const blocks = [];

  function textBlock(value) {
    const ta = autoGrow(h('textarea.para', { rows: 1, readOnly: ctx.readOnly }));
    ta.value = value;
    ta.addEventListener('input', () => ctx.changed());
    ta.addEventListener('focus', () => { activeTa = ta; });
    ta.addEventListener('keydown', e => {
      if ((e.ctrlKey || e.metaKey) && !e.altKey) {
        const k = e.key.toLowerCase();
        if (k === 'b') { e.preventDefault(); wrapSelection('**', '**'); return; }
        if (k === 'i') { e.preventDefault(); wrapSelection('*', '*'); return; }
        if (k === 'u') { e.preventDefault(); wrapSelection('<u>', '</u>'); return; }
        if (k === 'x' && e.shiftKey) { e.preventDefault(); wrapSelection('~~', '~~'); return; }
        if (k === 'e') { e.preventDefault(); wrapSelection('`', '`'); return; }
      }
    });
    ta.addEventListener('paste', e => {
      const files = [...(e.clipboardData ? e.clipboardData.files : [])].filter(f => f.type.startsWith('image/'));
      if (!files.length || ctx.readOnly) return;
      e.preventDefault();
      files.forEach(f => insertPicture(f, ta));
    });
    ta.addEventListener('keydown', e => {
      // Backspace at the start of words right after a picture removes the picture.
      if (e.key === 'Backspace' && ta.selectionStart === 0 && ta.selectionEnd === 0) {
        const i = blocks.findIndex(b => b.node === ta);
        const prev = blocks[i - 1];
        if (prev && prev.kind === 'pic') {
          e.preventDefault();
          removeBlock(prev);
          return;
        }
      }
      if (ctx.readOnly) return;
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      if (start !== end) return;
      const val = ta.value;
      const lineStart = val.lastIndexOf('\n', start - 1) + 1;
      const curLine = val.slice(lineStart, start);

      if (e.key === ' ') {
        if (curLine === '*' || curLine === '-') {
          e.preventDefault();
          ta.setRangeText('• ', lineStart, start, 'end');
          ctx.changed();
          return;
        }
        const numMatch = /^(\d+)$/.exec(curLine);
        if (numMatch) {
          e.preventDefault();
          ta.setRangeText(numMatch[1] + '. ', lineStart, start, 'end');
          ctx.changed();
          return;
        }
      }

      if (e.key === 'Enter' && !e.shiftKey) {
        const bulletEmpty = /^(\s*)(?:•|[*–-])\s*$/.exec(curLine);
        if (bulletEmpty) {
          e.preventDefault();
          ta.setRangeText('', lineStart, start, 'end');
          ctx.changed();
          return;
        }
        const bulletWithText = /^(\s*)(?:•|[*–-])\s+(.+)$/.exec(curLine);
        if (bulletWithText) {
          e.preventDefault();
          const indent = bulletWithText[1];
          ta.setRangeText('\n' + indent + '• ', start, start, 'end');
          ctx.changed();
          return;
        }
        const numEmpty = /^(\s*)(\d+)[.)]\s*$/.exec(curLine);
        if (numEmpty) {
          e.preventDefault();
          ta.setRangeText('', lineStart, start, 'end');
          ctx.changed();
          return;
        }
        const numWithText = /^(\s*)(\d+)([.)])\s+(.+)$/.exec(curLine);
        if (numWithText) {
          e.preventDefault();
          const indent = numWithText[1];
          const nextNum = parseInt(numWithText[2], 10) + 1;
          const sep = numWithText[3] + ' ';
          ta.setRangeText('\n' + indent + nextNum + sep, start, start, 'end');
          ctx.changed();
          return;
        }
      }
    });
    return { kind: 'text', node: ta, value: () => ta.value };
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
      const caret = a.node.value.length;
      a.node.value = a.node.value + (a.node.value && c.node.value ? '\n' : '') + c.node.value;
      blocks.splice(i, 1);
      c.node.remove();
      a.node._fit();
      a.node.focus();
      a.node.setSelectionRange(caret, caret);
    }
    ctx.changed();
  }

  async function insertPicture(blob, at) {
    const name = mediaName('IMG', extOf(blob));
    await ctx.putFile(name, blob);
    let i = blocks.findIndex(b => b.node === at);
    if (i < 0) i = blocks.length - 1;
    const target = blocks[i];
    const caret = target.kind === 'text' ? target.node.selectionStart : 0;
    const before = target.kind === 'text' ? target.node.value.slice(0, caret).replace(/\n$/, '') : '';
    const after = target.kind === 'text' ? target.node.value.slice(caret).replace(/^\n/, '') : '';
    const pic = picBlock(name), rest = textBlock(after);
    if (target.kind === 'text') {
      target.node.value = before;
      target.node._fit();
    }
    blocks.splice(i + 1, 0, pic, rest);
    target.node.after(pic.node, rest.node);
    rest.node.focus();
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
  blocks[0].node.placeholder = placeholder;
  wrap.append(...blocks.map(b => b.node));

  wrap.addEventListener('dragover', e => { if (!ctx.readOnly && e.dataTransfer.types.includes('Files')) e.preventDefault(); });
  wrap.addEventListener('drop', e => {
    const files = [...e.dataTransfer.files].filter(f => f.type.startsWith('image/'));
    if (!files.length || ctx.readOnly) return;
    e.preventDefault();
    const target = document.activeElement && blocks.some(b => b.node === document.activeElement) ? document.activeElement : blocks[blocks.length - 1].node;
    files.forEach(f => insertPicture(f, target));
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
      return blocks.filter(b => b.kind === 'text').map(b => b.value()).join(' ').trim().split(/\s+/).filter(Boolean).length;
    },
    focusEnd() {
      const last = [...blocks].reverse().find(b => b.kind === 'text');
      if (last) { last.node.focus(); last.node.setSelectionRange(last.node.value.length, last.node.value.length); }
    },
    focusStart() { blocks[0].node.focus(); },
    addPicture(files) {
      const target = document.activeElement && blocks.some(b => b.node === document.activeElement) ? document.activeElement : blocks[blocks.length - 1].node;
      [...files].forEach(f => insertPicture(f, target));
    },
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


// ========================================================= rich formatting
const TEXT_COLORS = [
  { name: 'Default', value: '' },
  { name: 'Gold / Amber', value: '#F59E0B' },
  { name: 'Electric Blue', value: '#0A84FF' },
  { name: 'Emerald Green', value: '#10B981' },
  { name: 'Vivid Purple', value: '#8B5CF6' },
  { name: 'Crimson Red', value: '#EF4444' },
  { name: 'Sunset Orange', value: '#FF9F0A' },
  { name: 'Slate Gray', value: '#9CA3AF' },
];

const LEADING_OPTIONS = [
  { label: 'Compact (1.3x)', val: '1.3' },
  { label: 'Normal (1.6x)', val: '1.6' },
  { label: 'Relaxed (1.9x)', val: '1.9' },
  { label: 'Spacious (2.3x)', val: '2.3' },
];

const KERNING_OPTIONS = [
  { label: 'Tight (-0.5px)', val: '-0.5px' },
  { label: 'Normal (0px)', val: 'normal' },
  { label: 'Wide (+1px)', val: '1px' },
  { label: 'Expanded (+2px)', val: '2px' },
];

const ALIGN_OPTIONS = [
  { label: 'Left align', val: 'left' },
  { label: 'Center align', val: 'center' },
  { label: 'Right align', val: 'right' },
  { label: 'Justify', val: 'justify' },
];

function formatBar(ctx, body) {
  if (ctx.readOnly) return null;
  const bar = h('div.format-bar');

  const btn = (title, ic, onClick, textLabel) => {
    const b = h('button.fmt-btn', { type: 'button', title, onmousedown: e => { e.preventDefault(); onClick(); } },
      ic ? icon(ic, 18) : null, textLabel || null);
    return b;
  };

  // Bold, Italic, Underline, Strikethrough
  const group1 = h('div.format-group',
    btn('Bold (Ctrl+B)', 'bold', () => body.wrapSelection('**', '**')),
    btn('Italic (Ctrl+I)', 'italic', () => body.wrapSelection('*', '*')),
    btn('Underline (Ctrl+U)', 'underline', () => body.wrapSelection('<u>', '</u>')),
    btn('Strikethrough (Ctrl+Shift+X)', 'strikethrough', () => body.wrapSelection('~~', '~~'))
  );

  // Font Color picker
  const colorBtn = h('button.fmt-dropdown-btn', {
    type: 'button', title: 'Text Color',
    onmousedown: e => {
      e.preventDefault();
      choices('Font / Text Color', TEXT_COLORS.map(c => ({
        text: (c.value === (body.getTypography().color || '') ? '✓  ' : '') + c.name,
        onClick: () => {
          body.setTypography({ color: c.value });
          ctx.changed();
        }
      })), 'Choose note text color');
    }
  }, icon('formatColor', 18), 'Color');

  // Spacing: Leading (Line-height) & Kerning (Letter-spacing)
  const leadingBtn = h('button.fmt-dropdown-btn', {
    type: 'button', title: 'Line Spacing (Leading)',
    onmousedown: e => {
      e.preventDefault();
      const cur = body.getTypography().leading || '1.6';
      choices('Line Spacing (Leading)', LEADING_OPTIONS.map(opt => ({
        text: (opt.val === cur ? '✓  ' : '') + opt.label,
        onClick: () => {
          body.setTypography({ leading: opt.val });
          ctx.changed();
        }
      })), 'Adjust vertical space between lines');
    }
  }, icon('formatLineSpacing', 18), 'Leading');

  const kerningBtn = h('button.fmt-dropdown-btn', {
    type: 'button', title: 'Letter Spacing (Kerning)',
    onmousedown: e => {
      e.preventDefault();
      const cur = body.getTypography().kerning || 'normal';
      choices('Letter Spacing (Kerning)', KERNING_OPTIONS.map(opt => ({
        text: (opt.val === cur ? '✓  ' : '') + opt.label,
        onClick: () => {
          body.setTypography({ kerning: opt.val });
          ctx.changed();
        }
      })), 'Adjust horizontal character spacing');
    }
  }, icon('formatLetterSpacing', 18), 'Kerning');

  // Headings
  const headingBtn = h('button.fmt-dropdown-btn', {
    type: 'button', title: 'Headings & Titles',
    onmousedown: e => {
      e.preventDefault();
      choices('Text Heading Style', [
        { text: 'Heading 1  (#)', onClick: () => body.prefixLines('# ') },
        { text: 'Heading 2  (##)', onClick: () => body.prefixLines('## ') },
        { text: 'Heading 3  (###)', onClick: () => body.prefixLines('### ') },
        { text: 'Normal Body Text', onClick: () => body.prefixLines('') }
      ]);
    }
  }, icon('formatHeading', 18), 'Style');

  // Lists & Quotes
  const group2 = h('div.format-group',
    btn('Bullet List', 'formatListBulleted', () => body.prefixLines('• ')),
    btn('Numbered List', 'formatListNumbered', () => body.prefixLines('1. ')),
    btn('Checklist', 'check', () => body.prefixLines('- [ ] ')),
    btn('Blockquote', 'formatQuote', () => body.prefixLines('> ')),
    btn('Code Block', 'formatCode', () => body.wrapSelection('`', '`'))
  );

  // Alignment
  const alignBtn = h('button.fmt-dropdown-btn', {
    type: 'button', title: 'Text Alignment',
    onmousedown: e => {
      e.preventDefault();
      const cur = body.getTypography().align || 'left';
      choices('Text Alignment', ALIGN_OPTIONS.map(opt => ({
        text: (opt.val === cur ? '✓  ' : '') + opt.label,
        onClick: () => {
          body.setTypography({ align: opt.val });
          ctx.changed();
        }
      })));
    }
  }, icon('formatAlignLeft', 18));

  bar.append(
    group1,
    h('div.format-sep'),
    colorBtn,
    leadingBtn,
    kerningBtn,
    h('div.format-sep'),
    headingBtn,
    group2,
    h('div.format-sep'),
    alignBtn
  );

  return bar;
}

// ================================================================== text

export function textEditor(ctx) {
  const n = ctx.note;
  let body;
  const title = titleField(ctx, 'Title', () => body.focusStart());
  body = richBody(ctx, n.content, 'Note');
  body.setTypography(object(n.data));
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
      const current = object(note.data);
      const typo = body.getTypography();
      note.data = JSON.stringify({ ...current, ...typo });
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
