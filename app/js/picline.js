// A picture between the words of a text note or journal: a line of its own,
//   ![[IMG_x.png]]                 as it comes (the column's width at most)
//   ![[IMG_x.png|60%]]             60% of the column (10–100, steps of 5)
//   ![[IMG_x.png|60%|center]]      and placed in the middle (or |right; left is the default)
// The same rules as the apps' PictureLine.java, kept in step by the same cases (tests/picline.test.mjs).

import { PICTURE_LINE } from './model.js';

export const ALIGNS = ['left', 'center', 'right'];

/** A width as kept: a multiple of 5 from 10 to 100, or 0 (as it comes). */
export const snap = pct => (pct > 0 ? Math.max(10, Math.min(100, Math.round(pct / 5) * 5)) : 0);

/** {name, width, align} of a picture line, or null when the line isn't one. */
export function parse(line) {
  const m = PICTURE_LINE.exec((line || '').trim());
  if (!m) return null;
  let width = 0, align = 'left';
  const inner = line.trim().slice(3, -2);
  for (const part of inner.split('|').slice(1)) {
    const p = part.trim().toLowerCase();
    const pct = /^(\d{1,3})%$/.exec(p);
    if (pct && +pct[1] >= 10 && +pct[1] <= 100) width = snap(+pct[1]);
    else if (ALIGNS.includes(p)) align = p;
  }
  return { name: m[1], width, align };
}

/** The line for a picture: only what differs from as-it-comes is written. */
export function format({ name, width = 0, align = 'left' }) {
  const w = snap(width);
  return `![[${name}${w ? `|${w}%` : ''}${align && align !== 'left' && ALIGNS.includes(align) ? `|${align}` : ''}]]`;
}
