// The text notes' format: Markdown, line by line, as the desktop (Markdown.java) and the phone
// read and write it, with the few HTML tags Markdown lacks.
//
//   Paragraphs  # H1   ## H2   ### H3   > quote   - item (also • * –)   1. item   - [ ] task   - [x] done
//   Inline      **bold**  *italic*  ~~strike~~  `code`  <u>underline</u>  <s>strike</s>  <font color="#RRGGBB">…</font>
//
// The editor shows the result and never the marks; this turns a line into {block, runs} and back.
// Writing nests the marks properly, so a note reads back unchanged.

export const PLAIN = Object.freeze({ bold: false, italic: false, underline: false, strike: false, code: false, color: null });
export const sameStyle = (a, b) => a.bold === b.bold && a.italic === b.italic && a.underline === b.underline
  && a.strike === b.strike && a.code === b.code && (a.color || null) === (b.color || null);

const HEADING = /^(#{1,3}) (.*)$/;
const TASK = /^\s*[-*•–]\s*\[([ xX])\]\s?(.*)$/;
const BULLET = /^\s*[-*•–] (.*)$/;
const NUMBER = /^\s*\d+[.)] (.*)$/;
const COLOR = /^<font color=["']?(#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{3})["']?>/i;

/** One line as {block, runs}. Blocks: NORMAL H1 H2 H3 QUOTE BULLET NUMBER TASK TASK_DONE. */
export function parseLine(line) {
  let m;
  if ((m = HEADING.exec(line))) return { block: ['H1', 'H2', 'H3'][m[1].length - 1], runs: inline(m[2]) };
  if (line.startsWith('> ')) return { block: 'QUOTE', runs: inline(line.slice(2)) };
  if ((m = TASK.exec(line))) return { block: m[1] === ' ' ? 'TASK' : 'TASK_DONE', runs: inline(m[2]) };
  if ((m = BULLET.exec(line))) return { block: 'BULLET', runs: inline(m[1]) };
  if ((m = NUMBER.exec(line))) return { block: 'NUMBER', runs: inline(m[1]) };
  return { block: 'NORMAL', runs: inline(line) };
}

function nextSingleStar(s, from) {
  for (let k = from; k < s.length; k++) {
    if (s[k] !== '*') continue;
    if (s[k + 1] === '*') { k++; continue; }
    return k;
  }
  return -1;
}

/** The runs [{text, style}] of one line. A mark opens only where it's closed later on the line. */
export function inline(s) {
  const out = [];
  let text = '';
  let bold = false, italic = false, strike = false, code = false, underline = 0, tagStrike = 0;
  const colors = [];
  const lower = s.toLowerCase();
  const now = () => ({ bold, italic, underline: underline > 0, strike: strike || tagStrike > 0, code, color: colors.length ? colors[colors.length - 1] : null });
  let i = 0;
  while (i < s.length) {
    const rest = s.slice(i);
    let took = null;
    const style = now();
    if (code) {
      if (rest.startsWith('`')) { took = '`'; code = false; }
    } else if (rest.startsWith('`') && s.indexOf('`', i + 1) > 0) { took = '`'; code = true; }
    else if (rest.startsWith('**') && (bold || s.indexOf('**', i + 2) > 0)) { took = '**'; bold = !bold; }
    else if (rest.startsWith('**') && !italic) { text += '**'; i += 2; continue; }
    else if (rest.startsWith('~~') && (strike || s.indexOf('~~', i + 2) > 0)) { took = '~~'; strike = !strike; }
    else if (rest.startsWith('*') && (italic || nextSingleStar(s, i + 1) > 0)) { took = '*'; italic = !italic; }
    else if (lower.startsWith('<u>', i) && lower.indexOf('</u>', i) > 0) { took = '<u>'; underline++; }
    else if (lower.startsWith('</u>', i) && underline > 0) { took = '</u>'; underline--; }
    else if (lower.startsWith('<s>', i) && lower.indexOf('</s>', i) > 0) { took = '<s>'; tagStrike++; }
    else if (lower.startsWith('</s>', i) && tagStrike > 0) { took = '</s>'; tagStrike--; }
    else if (lower.startsWith('</font>', i) && colors.length) { took = '</font>'; colors.pop(); }
    else {
      const c = COLOR.exec(rest);
      if (c && lower.indexOf('</font>', i) > 0) { took = c[0]; colors.push(c[1].toUpperCase()); }
    }
    if (took === null) { text += s[i]; i++; continue; }
    if (text) { out.push({ text, style }); text = ''; }
    i += took.length;
  }
  if (text) out.push({ text, style: now() });
  return merge(out);
}

function merge(runs) {
  const out = [];
  for (const r of runs) {
    if (!r.text) continue;
    const last = out[out.length - 1];
    if (last && sameStyle(last.style, r.style)) last.text += r.text;
    else out.push({ text: r.text, style: { ...r.style } });
  }
  return out;
}

function marks(s) {
  const m = [];
  if (s.color) m.push(`<font color="${s.color}">`);
  if (s.underline) m.push('<u>');
  if (s.strike) m.push('~~');
  if (s.bold) m.push('**');
  if (s.italic) m.push('*');
  if (s.code) m.push('`');
  return m;
}
const closing = mark => (mark.startsWith('<font') ? '</font>' : mark === '<u>' ? '</u>' : mark);

/** Runs as Markdown: colour outside, then u, strike, bold, italic, code. */
export function writeRuns(runs) {
  let out = '';
  const open = [];
  for (const r of merge(runs)) {
    const want = marks(r.style);
    let keep = 0;
    while (keep < open.length && keep < want.length && open[keep] === want[keep]) keep++;
    for (let k = open.length - 1; k >= keep; k--) out += closing(open[k]);
    open.length = keep;
    for (let k = keep; k < want.length; k++) { out += want[k]; open.push(want[k]); }
    out += r.text;
  }
  for (let k = open.length - 1; k >= 0; k--) out += closing(open[k]);
  return out;
}

const PREFIX = { NORMAL: '', H1: '# ', H2: '## ', H3: '### ', QUOTE: '> ', BULLET: '- ', TASK: '- [ ] ', TASK_DONE: '- [x] ' };

/** One paragraph as a line; `number` is its place in a numbered list. */
export function writeLine(p, number = 1) {
  return (p.block === 'NUMBER' ? number + '. ' : PREFIX[p.block] || '') + writeRuns(p.runs);
}

/** Paragraphs as a note, numbering each numbered list from 1. */
export function write(paras) {
  let n = 0;
  return paras.map(p => { n = p.block === 'NUMBER' ? n + 1 : 0; return writeLine(p, n); }).join('\n');
}

/** A line without its marks (cards, counts). */
export function plainLine(line) {
  const p = parseLine(line);
  const text = p.runs.map(r => r.text).join('');
  if (p.block === 'BULLET') return '• ' + text;
  if (p.block === 'TASK') return '☐ ' + text;
  if (p.block === 'TASK_DONE') return '☑ ' + text;
  if (p.block === 'NUMBER') return (/^\s*(\d+[.)])/.exec(line) || ['', '1.'])[1] + ' ' + text;
  return text;
}

export const plain = text => (text || '').split('\n').map(plainLine).join('\n');

/**
 * Undoes what Notes+ 16.1 for Android wrote by mistake: characters as HTML entities (&#128512;,
 * &amp;) and colours as <span style="color:…">. Text without them comes back as it was.
 */
export function repair(text) {
  if (!text) return '';
  if (!text.includes('&') && !text.includes('<span')) return text;
  const named = { amp: '&', lt: '<', gt: '>', quot: '"', nbsp: ' ' };
  return text
    .replace(/<span style="color:\s*(#[0-9A-Fa-f]{6});?">(.*?)<\/span>/g, (_, c, t) => `<font color="${c.toUpperCase()}">${t}</font>`)
    .replace(/&(#\d{2,7}|amp|lt|gt|quot|nbsp);/g, (_, g) => g[0] === '#' ? String.fromCodePoint(+g.slice(1)) : named[g]);
}
