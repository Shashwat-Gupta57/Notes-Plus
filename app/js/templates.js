// Templates: a note of my own with `template` set, kept off the grid except under the Templates
// filter, and offered in the New sheet. It syncs like any of my notes (the flag travels only in
// users/{uid}/notes). Text, list, journal and mind map notes can be templates: their substance is
// words and structure. Pictures stay with the note they were added to.
//
// The phone's and desktop's Templates.java make the same fresh copy.

import { TEXT, LIST, JOURNAL, MINDMAP, PICTURE_LINE, todos, todosJson, wraps, journal, journalJson, mindMap, mindMapJson, newNote, KINDS } from './model.js';
import * as due from './due.js';
import * as lock from './lock.js';

export const kindAllowed = type => type === TEXT || type === LIST || type === JOURNAL || type === MINDMAP;

/** Whether "Save as template" is offered: my own, unlocked, words-and-structure note. */
export const canSave = n => !n.template && !n.sharedFrom && !n.groupId && kindAllowed(n.type) && !lock.isLocked(n.data);

/** Text without its picture lines (![[IMG_….png]]). */
export function withoutPictures(text) {
  return (text || '').split('\n').filter(l => !PICTURE_LINE.test(l.trim())).join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

/**
 * What a note starts as, from a template (and what a template keeps of a note): [content, data].
 * Ticks cleared, one-off days dropped, repeating items on their first day from `today`, a journal on
 * `dayStart` with no mood, and no pictures anywhere.
 */
export function fresh(type, content, data, today, dayStart) {
  content = content || '';
  if (type === LIST) {
    const items = [];
    for (const item of todos(data)) {
      if (PICTURE_LINE.test((item.text || '').trim())) continue;
      item.done = false;
      if (due.repeats(item)) item.due = due.ymd(due.first(item.repeat, today));
      else { delete item.due; delete item.time; delete item.repeat; }
      items.push(item);
    }
    const words = items.map(i => i.text.trim()).filter(Boolean).join('\n');
    return [words, todosJson(items, wraps(data))];
  }
  if (type === JOURNAL) {
    const j = journal(data, dayStart);
    return [withoutPictures(content), journalJson({ ...j, day: dayStart, mood: '', media: [] })];
  }
  if (type === MINDMAP) {
    const map = mindMap(data);
    for (const n of map.nodes) n.image = null;
    return [content, mindMapJson(map)];
  }
  return [withoutPictures(content), data];
}

const startOfToday = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d.getTime(); };

/** A new note (not yet stored) from `from`: a template when `template`. */
export function copyOf(from, title, template, uid) {
  const [content, data] = fresh(from.type, from.content, from.data, due.today(), startOfToday());
  const n = { ...newNote(from.type), title: (title || '').trim(), content, data, color: from.color || 0, account: uid || null };
  if (template) n.template = true;
  return n;
}

/** My templates among `notes`, by name. */
export const of = notes => notes.filter(n => n.template && !n.deleted)
  .sort((a, b) => name(a).localeCompare(name(b), undefined, { sensitivity: 'base' }));

export function name(n) {
  const t = (n.title || '').trim();
  return t || (KINDS.find(k => k.type === n.type) || KINDS[0]).label + ' template';
}
