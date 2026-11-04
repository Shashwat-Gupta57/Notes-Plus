// Reminders for list items with a day, while Notes+ is open (a tab or the installed app): a
// notification at the item's time (9:00 on its day when it has none); clicking it opens the list.
// The apps' Reminders. A closed web page can't be woken without a push server, which Notes+ doesn't
// have: the phone and the PC remind with their apps closed (the phone) or running (the PC).
//
// Only my own lists on the grid: never a locked, hidden, archived or template one.

import * as db from './db.js';
import * as due from './due.js';
import * as lock from './lock.js';
import { LIST, todos } from './model.js';
import { shown } from './notelinks.js';
import { toast } from './ui.js';

const SHOWN_UNTIL = 'np_reminders_shown_until';
const LATE_OK = 12 * 60 * 60 * 1000;
let timer = null;

const store = {
  get() { try { return Number(localStorage.getItem(SHOWN_UNTIL)) || 0; } catch { return 0; } },
  set(v) { try { localStorage.setItem(SHOWN_UNTIL, String(v)); } catch { /* Private window: none twice this session. */ } },
};
let memoryUntil = 0;

/** When an item reminds, on this device's clock. */
export function at(item) {
  const [y, m, d] = due.civil(due.day(item.due));
  const minute = due.remindMinute(item);
  return new Date(y, m - 1, d, Math.floor(minute / 60), minute % 60).getTime();
}

export function moments(notes) {
  const out = [];
  for (const n of notes) {
    if (n.deleted || n.type !== LIST || n.hidden || n.archived || n.template || lock.isLocked(n.data)) continue;
    for (const item of todos(n.data)) if (due.remindable(item)) out.push({ at: at(item), note: n, item });
  }
  return out;
}

/** Starts checking (once, when the app starts). */
export function start(open) {
  if (timer) return;
  if (!store.get()) store.set(Date.now()); // Nothing from before.
  const check = async () => {
    const now = Date.now();
    const until = Math.max(store.get(), memoryUntil) || now;
    let notes = [];
    try { notes = await db.allNotes(); } catch { return; }
    const dueNow = moments(notes).filter(m => m.at > until && m.at <= now && now - m.at < LATE_OK);
    memoryUntil = Math.max(until, now);
    store.set(memoryUntil);
    for (const m of dueNow) show(m, open);
  };
  timer = setInterval(check, 20000);
  check();
}

async function show(m, open) {
  const list = (m.note.title || '').trim() || 'List';
  const t = due.time(m.item.time);
  const words = shown(m.item.text.trim());
  const body = `${list}  ·  ${t ? 'Due ' + t : 'Due today'}`;
  toast(`⏰ ${words}  ·  ${body}`, 6000); // In the page too, in case notifications are off.
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  const options = { body, tag: 'remind_' + m.note.uuid + '_' + m.item.text, icon: '../res/icon-192.png', data: { uuid: m.note.uuid } };
  try {
    const reg = navigator.serviceWorker && await navigator.serviceWorker.getRegistration();
    if (reg) { await reg.showNotification(words, options); return; } // The installed app's way; sw.js opens the list.
  } catch { /* Fall through to a page notification. */ }
  try {
    const n = new Notification(words, options);
    n.onclick = () => { window.focus(); open(m.note.uuid); n.close(); };
  } catch { /* Not allowed here: the toast has said it. */ }
}

/** Asks for permission to notify, once a day is being given to an item (a click, so browsers allow it). */
export function askIfNeeded() {
  if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission().catch(() => {});
}
