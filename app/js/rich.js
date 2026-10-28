// Formatted words, edited as they look (WordPad-style): bold is bold, a heading is big, a list
// has its bullets, and no mark is ever on screen. What is kept is Markdown (md.js), the same
// text the desktop and the phone read.
//
// The editor is a contenteditable block holding p, h1–h3, blockquote, ul/ol li and div.task;
// inside them b, i, u, s, code and font colour. Word's habits: Enter continues a list (and
// ends it on an empty item), Backspace at the start of a heading, quote or task makes it plain,
// and typing "# ", "- ", "1. ", "[] " or "> " at a line's start turns it into one; **bold**,
// *italic*, ~~strike~~ and `code` typed with their marks become formatted.

import { parseLine, inline, write, writeRuns, PLAIN, repair } from './md.js';

const escHtml = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** Runs as inline HTML. */
export function runsHtml(runs) {
  return runs.map(r => {
    let h = escHtml(r.text);
    const s = r.style;
    if (s.code) h = `<code>${h}</code>`;
    if (s.italic) h = `<i>${h}</i>`;
    if (s.bold) h = `<b>${h}</b>`;
    if (s.strike) h = `<s>${h}</s>`;
    if (s.underline) h = `<u>${h}</u>`;
    if (s.color) h = `<font color="${s.color}">${h}</font>`;
    return h;
  }).join('');
}

/** Markdown as the editor's HTML. */
export function mdHtml(text) {
  const paras = (text || '').split('\n').map(parseLine);
  let out = '', list = null;
  const close = () => { if (list) { out += `</${list}>`; list = null; } };
  for (const p of paras) {
    const inner = runsHtml(p.runs) || '<br>';
    const want = p.block === 'BULLET' ? 'ul' : p.block === 'NUMBER' ? 'ol' : null;
    if (want !== list) { close(); if (want) { out += `<${want}>`; list = want; } }
    if (want) { out += `<li>${inner}</li>`; continue; }
    if (p.block === 'H1' || p.block === 'H2' || p.block === 'H3') out += `<${p.block.toLowerCase()}>${inner}</${p.block.toLowerCase()}>`;
    else if (p.block === 'QUOTE') out += `<blockquote>${inner}</blockquote>`;
    else if (p.block === 'TASK' || p.block === 'TASK_DONE') out += `<div class="task" data-done="${p.block === 'TASK_DONE' ? 1 : 0}">${inner}</div>`;
    else out += `<p>${inner}</p>`;
  }
  close();
  return out || '<p><br></p>';
}

// --------------------------------------------------------------- reading back

const BLOCKS = new Set(['P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BLOCKQUOTE', 'UL', 'OL', 'LI', 'PRE', 'SECTION', 'ARTICLE']);

function hexOf(color) {
  if (!color) return null;
  color = color.trim();
  if (/^#[0-9a-f]{6}$/i.test(color)) return color.toUpperCase();
  if (/^#[0-9a-f]{3}$/i.test(color)) return ('#' + color.slice(1).split('').map(c => c + c).join('')).toUpperCase();
  const m = /^rgba?\((\d+),\s*(\d+),\s*(\d+)/i.exec(color);
  if (m) return '#' + [m[1], m[2], m[3]].map(n => Number(n).toString(16).padStart(2, '0')).join('').toUpperCase();
  return null; // "currentcolor", "inherit": the automatic colour.
}

/** The style an inline element adds to {@code s}. */
function styled(el, s) {
  const t = el.tagName, st = el.style || {};
  s = { ...s };
  if (t === 'B' || t === 'STRONG' || st.fontWeight === 'bold' || Number(st.fontWeight) >= 600) s.bold = true;
  if (st.fontWeight === 'normal' || st.fontWeight === '400') s.bold = false;
  if (t === 'I' || t === 'EM' || st.fontStyle === 'italic') s.italic = true;
  if (st.fontStyle === 'normal') s.italic = false;
  const deco = (st.textDecoration || '') + ' ' + (st.textDecorationLine || '');
  if (t === 'U' || deco.includes('underline')) s.underline = true;
  if (t === 'S' || t === 'STRIKE' || t === 'DEL' || deco.includes('line-through')) s.strike = true;
  if (t === 'CODE' || t === 'KBD' || t === 'SAMP') s.code = true;
  if (t === 'FONT' && el.getAttribute('color')) s.color = hexOf(el.getAttribute('color'));
  if (st.color) s.color = hexOf(st.color);
  return s;
}

/** The lines (each a list of runs) of a block element; a <br> starts a new line. */
function linesOf(el) {
  const lines = [[]];
  const walk = (node, s) => {
    for (const c of node.childNodes) {
      if (c.nodeType === 3) {
        const text = c.nodeValue.replace(/​/g, '').replace(/\n/g, ' ');
        if (text) lines[lines.length - 1].push({ text, style: s });
      } else if (c.nodeType === 1) {
        if (c.tagName === 'BR') lines.push([]);
        else if (c.tagName === 'IMG' || c.tagName === 'UL' || c.tagName === 'OL') continue;
        else walk(c, styled(c, s));
      }
    }
  };
  walk(el, { ...PLAIN });
  if (lines.length > 1 && !lines[lines.length - 1].length) lines.pop(); // A trailing <br> is no line.
  return lines;
}

/** The editor's (or a fragment's) paragraphs, as {block, runs}. */
export function parasOf(root) {
  const paras = [];
  let loose = null; // Text sitting straight in the root: one plain paragraph.
  const flush = () => { if (loose) { linesOf(loose).forEach(runs => paras.push({ block: 'NORMAL', runs })); loose = null; } };
  const add = (block, el) => linesOf(el).forEach(runs => paras.push({ block, runs }));
  const visit = (node, inQuote) => {
    for (const c of node.childNodes) {
      if (c.nodeType === 1 && BLOCKS.has(c.tagName)) {
        flush();
        const t = c.tagName;
        if (t === 'UL' || t === 'OL') {
          for (const li of c.children) {
            add(t === 'UL' ? 'BULLET' : 'NUMBER', li); // Its own words (nested lists are skipped there)…
            for (const sub of li.children) if (sub.tagName === 'UL' || sub.tagName === 'OL') visit({ childNodes: [sub] }, inQuote); // …then theirs.
          }
        } else if (t === 'LI') add('BULLET', c);
        else if (t === 'BLOCKQUOTE') {
          if ([...c.children].some(x => BLOCKS.has(x.tagName))) visit(c, true); else add('QUOTE', c);
        } else if (/^H[1-6]$/.test(t)) add(t === 'H1' ? 'H1' : t === 'H2' ? 'H2' : 'H3', c);
        else if (t === 'DIV' && c.classList.contains('task')) add(c.dataset.done === '1' ? 'TASK_DONE' : 'TASK', c);
        else if ([...c.children].some(x => BLOCKS.has(x.tagName))) visit(c, inQuote);
        else add(inQuote ? 'QUOTE' : 'NORMAL', c);
      } else if (c.nodeType === 1 || (c.nodeType === 3 && c.nodeValue.replace(/​/g, '').trim())) {
        if (!loose) loose = document.createElement('span');
        loose.appendChild(c.cloneNode(true));
      }
    }
    flush();
  };
  visit(root, false);
  return paras;
}

export const htmlMd = root => write(parasOf(root));

// ------------------------------------------------------------------ editor

/**
 * A formatted block: {node, setMarkdown, markdown, text, focusStart, focusEnd, split, cmd, state}.
 * `onChange` hears every edit; `onCaret` every caret move (the toolbar follows).
 */
export function richEditor({ readOnly = false, placeholder = '', onChange = () => {}, onFocus = () => {} } = {}) {
  const el = document.createElement('div');
  el.className = 'rich-text';
  el.contentEditable = readOnly ? 'false' : 'true';
  el.spellcheck = true;
  el.dataset.placeholder = placeholder;
  const changed = () => { paintEmpty(); onChange(); };
  const paintEmpty = () => el.classList.toggle('empty', !el.textContent.trim() && !el.querySelector('li, .task, h1, h2, h3, blockquote'));

  function currentBlock() {
    const sel = getSelection();
    if (!sel.rangeCount || !el.contains(sel.anchorNode)) return null;
    let n = sel.anchorNode.nodeType === 3 ? sel.anchorNode.parentNode : sel.anchorNode;
    while (n && n !== el && !(n.nodeType === 1 && (BLOCKS.has(n.tagName) && n.tagName !== 'UL' && n.tagName !== 'OL'))) n = n.parentNode;
    return n && n !== el ? n : null;
  }

  /** Text of the current block from its start to the caret. */
  function beforeCaret(block) {
    const sel = getSelection();
    const r = document.createRange();
    r.setStart(block, 0);
    r.setEnd(sel.anchorNode, sel.anchorOffset);
    return r.toString();
  }

  function caretAtStart(block) {
    const sel = getSelection();
    return sel.isCollapsed && beforeCaret(block).replace(/​/g, '') === '';
  }

  function placeCaret(node, atEnd) {
    const r = document.createRange();
    r.selectNodeContents(node);
    r.collapse(!atEnd);
    const sel = getSelection();
    sel.removeAllRanges();
    sel.addRange(r);
  }

  /** Turns the block into another element (tasks and plain paragraphs), keeping its words. */
  function retag(block, tag, cls, done) {
    const fresh = document.createElement(tag);
    if (cls) { fresh.className = cls; fresh.dataset.done = done ? '1' : '0'; }
    while (block.firstChild) fresh.appendChild(block.firstChild);
    if (!fresh.firstChild) fresh.appendChild(document.createElement('br'));
    if (block.tagName === 'LI') {
      // Out of the list: the item becomes its own paragraph after the list part above it.
      const list = block.parentNode;
      const after = document.createElement(list.tagName);
      while (block.nextSibling) after.appendChild(block.nextSibling);
      list.after(fresh);
      if (after.children.length) fresh.after(after);
      block.remove();
      if (!list.children.length) list.remove();
    } else {
      block.replaceWith(fresh);
    }
    return fresh;
  }

  /** The current paragraph (or selected ones) as `block`; plain again if it already is. */
  function setBlock(block) {
    el.focus();
    const cur = currentBlock();
    const is = cur ? kindOf(cur) : 'NORMAL';
    const target = is === block ? 'NORMAL' : block;
    if (target === 'BULLET' || target === 'NUMBER') {
      if (is === 'TASK' || is === 'TASK_DONE') retag(cur, 'p');
      document.execCommand(target === 'BULLET' ? 'insertUnorderedList' : 'insertOrderedList');
    } else if (is === 'BULLET' || is === 'NUMBER') {
      document.execCommand(is === 'BULLET' ? 'insertUnorderedList' : 'insertOrderedList'); // Off the list first.
      if (target !== 'NORMAL') setBlock(target);
    } else if (target === 'TASK' || target === 'TASK_DONE') {
      if (cur) placeCaret(retag(cur, 'div', 'task', target === 'TASK_DONE'), true);
    } else {
      const tag = { NORMAL: 'p', H1: 'h1', H2: 'h2', H3: 'h3', QUOTE: 'blockquote' }[target];
      if (cur && cur.classList && cur.classList.contains('task')) placeCaret(retag(cur, 'p'), true);
      document.execCommand('formatBlock', false, tag);
    }
    changed();
  }

  function kindOf(b) {
    if (!b) return 'NORMAL';
    if (b.tagName === 'LI') return b.parentNode.tagName === 'OL' ? 'NUMBER' : 'BULLET';
    if (b.classList && b.classList.contains('task')) return b.dataset.done === '1' ? 'TASK_DONE' : 'TASK';
    if (/^H[1-3]$/.test(b.tagName)) return b.tagName;
    if (b.tagName === 'BLOCKQUOTE' || (b.parentNode && b.parentNode.tagName === 'BLOCKQUOTE')) return 'QUOTE';
    return 'NORMAL';
  }

  /** Wraps the selection in <code> (or takes it off). */
  function toggleCode() {
    const sel = getSelection();
    if (!sel.rangeCount || sel.isCollapsed) return;
    const r = sel.getRangeAt(0);
    let n = r.commonAncestorContainer;
    n = n.nodeType === 3 ? n.parentNode : n;
    const code = n.closest && n.closest('code');
    if (code && el.contains(code)) {
      code.replaceWith(...code.childNodes);
    } else {
      const c = document.createElement('code');
      c.appendChild(r.extractContents());
      r.insertNode(c);
      sel.removeAllRanges();
      const rr = document.createRange();
      rr.selectNodeContents(c);
      sel.addRange(rr);
    }
    changed();
  }

  const AUTO_BLOCK = { '#': 'H1', '##': 'H2', '###': 'H3', '-': 'BULLET', '*': 'BULLET', '•': 'BULLET', '>': 'QUOTE', '[]': 'TASK', '[ ]': 'TASK', '[x]': 'TASK_DONE' };
  const AUTO_INLINE = /(\*\*([^*]+)\*\*|(?:^|[^*\w])\*([^*\s][^*]*)\*|~~([^~]+)~~|`([^`]+)`)$/;

  el.addEventListener('beforeinput', e => {
    if (readOnly) return;
    const block = currentBlock();
    // "# ", "- ", "1. ", "[] ", "> " at the start of a plain line.
    if (e.inputType === 'insertText' && e.data === ' ' && block && kindOf(block) === 'NORMAL') {
      const typed = beforeCaret(block).replace(/​/g, '');
      const kind = AUTO_BLOCK[typed] || (/^\d+[.)]$/.test(typed) ? 'NUMBER' : null);
      if (kind) {
        e.preventDefault();
        const sel = getSelection();
        const r = document.createRange();
        r.setStart(block, 0);
        r.setEnd(sel.anchorNode, sel.anchorOffset);
        r.deleteContents();
        if (!block.textContent) block.innerHTML = '<br>';
        placeCaret(block, false);
        setBlock(kind);
        return;
      }
    }
    // Enter in a task: the next one; on an empty task, back to plain.
    if (e.inputType === 'insertParagraph' && block && block.classList && block.classList.contains('task')) {
      e.preventDefault();
      if (!block.textContent.trim()) { placeCaret(retag(block, 'p'), false); changed(); return; }
      const sel = getSelection();
      const r = sel.getRangeAt(0);
      r.deleteContents();
      const tail = document.createRange();
      tail.setStart(r.endContainer, r.endOffset);
      tail.setEnd(block, block.childNodes.length);
      const next = document.createElement('div');
      next.className = 'task';
      next.dataset.done = '0';
      next.appendChild(tail.extractContents());
      if (!next.textContent) next.innerHTML = '<br>';
      if (!block.textContent) block.innerHTML = '<br>';
      block.after(next);
      placeCaret(next, false);
      changed();
      return;
    }
    // Backspace at the start of a heading, quote or task: plain first, as Word does.
    if (e.inputType === 'deleteContentBackward' && block && caretAtStart(block)) {
      const kind = kindOf(block);
      if (kind === 'H1' || kind === 'H2' || kind === 'H3' || kind === 'QUOTE' || kind === 'TASK' || kind === 'TASK_DONE') {
        e.preventDefault();
        if (kind === 'QUOTE' && block.tagName !== 'BLOCKQUOTE') { document.execCommand('formatBlock', false, 'p'); changed(); return; }
        placeCaret(retag(block, 'p'), false);
        changed();
      } else if (kind === 'BULLET' || kind === 'NUMBER') {
        e.preventDefault();
        placeCaret(retag(block, 'p'), false);
        changed();
      }
    }
  });

  el.addEventListener('input', e => {
    // **bold**, *italic*, ~~strike~~, `code` typed with their marks.
    if (e.inputType === 'insertText' && /[*~`]/.test(e.data || '')) {
      const sel = getSelection();
      const node = sel.anchorNode;
      if (node && node.nodeType === 3) {
        const before = node.nodeValue.slice(0, sel.anchorOffset);
        const m = AUTO_INLINE.exec(before);
        if (m) {
          const inner = m[2] || m[3] || m[4] || m[5];
          const whole = m[2] ? m[1] : m[3] ? m[1].replace(/^[^*]/, '') : m[1];
          const start = sel.anchorOffset - whole.length;
          const tag = m[2] ? 'b' : m[3] ? 'i' : m[4] ? 's' : 'code';
          const after = node.splitText(sel.anchorOffset);
          node.nodeValue = node.nodeValue.slice(0, start);
          const f = document.createElement(tag);
          f.textContent = inner;
          after.before(f);
          // A zero-width space after it, so what follows is typed plain.
          const rest = document.createTextNode('​');
          f.after(rest);
          const r = document.createRange();
          r.setStart(rest, 1);
          r.collapse(true);
          sel.removeAllRanges();
          sel.addRange(r);
        }
      }
    }
    changed();
  });

  // Clicking a task's box ticks it.
  el.addEventListener('mousedown', e => {
    const t = e.target.closest && e.target.closest('.task');
    if (!t || !el.contains(t)) return;
    const box = t.getBoundingClientRect();
    if (e.clientX - box.left > 26) return;
    e.preventDefault();
    if (readOnly) return;
    t.dataset.done = t.dataset.done === '1' ? '0' : '1';
    changed();
  });

  el.addEventListener('keydown', e => {
    if (readOnly || !(e.ctrlKey || e.metaKey)) return;
    const k = e.key.toLowerCase();
    if (k === 'b' && !e.altKey) { e.preventDefault(); cmd('bold'); }
    else if (k === 'i' && !e.altKey) { e.preventDefault(); cmd('italic'); }
    else if (k === 'u' && !e.altKey) { e.preventDefault(); cmd('underline'); }
    else if (k === 'x' && e.shiftKey) { e.preventDefault(); cmd('strikeThrough'); }
    else if (k === 'e' && !e.altKey) { e.preventDefault(); toggleCode(); }
    else if (e.altKey && ['1', '2', '3', '0'].includes(e.key)) { e.preventDefault(); setBlock(e.key === '0' ? 'NORMAL' : 'H' + e.key); }
    else if (e.shiftKey && k === 'l') { e.preventDefault(); setBlock('BULLET'); }
  });

  // Pasted text is read as Markdown, so formatting copied from a note (or a .md file) survives.
  el.addEventListener('paste', e => {
    if (readOnly) return;
    const cd = e.clipboardData;
    if (!cd || [...(cd.files || [])].some(f => f.type.startsWith('image/'))) return; // Pictures: the body handles them.
    const text = cd.getData('text/plain');
    if (!text) return;
    e.preventDefault();
    const clean = text.replace(/\r\n?/g, '\n');
    document.execCommand('insertHTML', false, clean.includes('\n') ? mdHtml(clean) : runsHtml(inline(clean)));
    changed();
  });
  // Copied as Markdown (pasting it into a note brings the formatting back) and as HTML.
  const copy = (e, cut) => {
    const sel = getSelection();
    if (!sel.rangeCount || sel.isCollapsed) return;
    const holder = document.createElement('div');
    holder.appendChild(sel.getRangeAt(0).cloneContents());
    e.preventDefault();
    e.clipboardData.setData('text/plain', htmlMd(holder));
    e.clipboardData.setData('text/html', holder.innerHTML);
    if (cut && !readOnly) { sel.deleteFromDocument(); changed(); }
  };
  el.addEventListener('copy', e => copy(e, false));
  el.addEventListener('cut', e => copy(e, true));
  el.addEventListener('focus', () => onFocus(api));

  function cmd(name, value) {
    if (readOnly) return;
    el.focus();
    document.execCommand('styleWithCSS', false, false);
    document.execCommand(name, false, value);
    changed();
  }

  const api = {
    node: el,
    setMarkdown(text) { el.innerHTML = mdHtml(repair(text)); paintEmpty(); },
    markdown: () => htmlMd(el),
    text: () => el.innerText || '',
    focusStart() { el.focus(); placeCaret(el.firstElementChild || el, false); },
    focusEnd() { el.focus(); placeCaret(el.lastElementChild || el, true); },
    /** The Markdown before and after the caret (where a picture goes in). */
    split() {
      const sel = getSelection();
      const head = document.createRange(), tail = document.createRange();
      head.selectNodeContents(el);
      tail.selectNodeContents(el);
      if (sel.rangeCount && el.contains(sel.anchorNode)) {
        head.setEnd(sel.anchorNode, sel.anchorOffset);
        tail.setStart(sel.anchorNode, sel.anchorOffset);
      } else {
        head.collapse(false);
        tail.collapse(false);
      }
      const a = document.createElement('div'), b = document.createElement('div');
      a.appendChild(head.cloneContents());
      b.appendChild(tail.cloneContents());
      return [htmlMd(a), htmlMd(b)];
    },
    cmd, setBlock, toggleCode,
    color(hex) { cmd('foreColor', hex || 'currentcolor'); },
    /** What the caret is on: {bold, italic, underline, strike, block}. */
    state() {
      const q = c => { try { return document.queryCommandState(c); } catch { return false; } };
      return { bold: q('bold'), italic: q('italic'), underline: q('underline'), strike: q('strikeThrough'), block: kindOf(currentBlock()) };
    },
    owns: node => el.contains(node),
  };
  return api;
}

export { writeRuns };
