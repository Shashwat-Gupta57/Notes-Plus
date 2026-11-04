// Due dates and repeats on list items: the item's `due` (a calendar day, "YYYY-MM-DD", the same on
// every device whatever its time zone), optional `time` ("HH:mm") and `repeat`: `daily`, `weekdays`,
// `weekly:1,3,5` (1 = Monday … 7 = Sunday) or `monthly:9` (that day of each month, the month's last
// day when it's shorter).
//
// Ticking a repeating item keeps it ticked and moves `due` to its next day; when that day comes it
// shows unticked again (rollover). Days are counted from 1970-01-01 in plain arithmetic, so this file
// and the phone's and desktop's Due.java agree to the day. Pictures between items never have a date.

import { PICTURE_LINE } from './model.js';

export const NONE = 0, DONE = 1, LATER = 2, TODAY = 3, OVERDUE = 4;
export const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
export const NO_DAY = null;

// ------------------------------------------------------------------- days

/** The day number of y-m-d (1970-01-01 = 0). */
export function dayOf(y, m, d) {
  y -= m <= 2 ? 1 : 0;
  const era = Math.floor(y / 400), yoe = y - era * 400;
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** [y, m, d] of a day number. */
export function civil(z) {
  z += 719468;
  const era = Math.floor(z / 146097), doe = z - era * 146097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1, m = mp < 10 ? mp + 3 : mp - 9;
  return [yoe + era * 400 + (m <= 2 ? 1 : 0), m, d];
}

export const monthLength = (y, m) => (m === 12 ? dayOf(y + 1, 1, 1) : dayOf(y, m + 1, 1)) - dayOf(y, m, 1);

/** The day number of "YYYY-MM-DD", or null. */
export function day(ymd) {
  const r = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ymd || '');
  if (!r) return NO_DAY;
  const y = +r[1], m = +r[2], d = +r[3];
  if (m < 1 || m > 12 || d < 1 || d > monthLength(y, m)) return NO_DAY;
  return dayOf(y, m, d);
}

const pad = n => String(n).padStart(2, '0');
export function ymd(n) {
  const [y, m, d] = civil(n);
  return `${String(y).padStart(4, '0')}-${pad(m)}-${pad(d)}`;
}

/** Today, on this device's calendar. */
export function today() {
  const d = new Date();
  return dayOf(d.getFullYear(), d.getMonth() + 1, d.getDate());
}

/** 1 = Monday … 7 = Sunday. */
export const weekday = n => (((n + 3) % 7) + 7) % 7 + 1;

// ------------------------------------------------------------------- repeats

/** The days of a weekly repeat as booleans [_, Mon … Sun]. */
export function weekDays(repeat) {
  const on = new Array(8).fill(false);
  if (typeof repeat === 'string' && repeat.startsWith('weekly:')) {
    for (const p of repeat.slice(7).split(',')) {
      const d = /^\s*\d+\s*$/.test(p) ? +p : 0;
      if (d >= 1 && d <= 7) on[d] = true;
    }
  }
  return on;
}

export function weekly(on) {
  const days = [];
  for (let d = 1; d <= 7; d++) if (on[d]) days.push(d);
  return days.length ? 'weekly:' + days.join(',') : null;
}

/** The day of the month a monthly repeat falls on (1–31), or 0. */
export function monthDay(repeat) {
  const r = /^monthly:\s*(\d+)\s*$/.exec(typeof repeat === 'string' ? repeat : '');
  const d = r ? +r[1] : 0;
  return d >= 1 && d <= 31 ? d : 0;
}

export const valid = repeat => repeat === 'daily' || repeat === 'weekdays' || weekly(weekDays(repeat)) !== null || monthDay(repeat) > 0;

/** Whether the day is one of the repeat's days. */
export function matches(repeat, n) {
  if (repeat === 'daily') return true;
  if (repeat === 'weekdays') return weekday(n) <= 5;
  if (typeof repeat === 'string' && repeat.startsWith('weekly:')) return weekDays(repeat)[weekday(n)];
  const md = monthDay(repeat);
  if (!md) return false;
  const [y, m, d] = civil(n);
  return d === Math.min(md, monthLength(y, m));
}

/** The repeat's first day on or after `from` (`from` itself for no repeat). */
export function first(repeat, from) {
  if (!valid(repeat)) return from;
  for (let d = from; d < from + 400; d++) if (matches(repeat, d)) return d;
  return from;
}

// ------------------------------------------------------------------- items

const isPicture = item => PICTURE_LINE.test((item.text || '').trim());
export const dated = item => !!item.due && !isPicture(item) && day(item.due) !== NO_DAY; // Cheapest test first.
export const repeats = item => dated(item) && valid(item.repeat);

/**
 * Ticks or unticks an item. A repeating item stays ticked and moves on to its next day after
 * today (or after its day, when ticked ahead of it); unticked again, it gets that day back.
 */
export function tick(item, done, now) {
  if (done && !item.done && repeats(item)) {
    item.undoDue = item.due;
    item.due = ymd(first(item.repeat, Math.max(now, day(item.due)) + 1));
  } else if (!done && item.done && item.undoDue) {
    item.due = item.undoDue;
  }
  if (!done || !repeats(item)) delete item.undoDue;
  item.done = done;
}

/** Unticks every repeating item whose next day has come. Whether any changed. */
export function rollover(items, now) {
  let changed = false;
  for (const item of items) {
    if (item.done && repeats(item) && day(item.due) <= now) {
      item.done = false;
      delete item.undoDue;
      changed = true;
    }
  }
  return changed;
}

export function status(item, now) {
  if (!dated(item)) return NONE;
  const d = day(item.due);
  if (item.done) return repeats(item) ? LATER : DONE;
  return d < now ? OVERDUE : d === now ? TODAY : LATER;
}

/** How many unticked items are past their day. */
export const overdue = (items, now) => items.filter(i => status(i, now) === OVERDUE).length;

/** The item a card's tag speaks for: the soonest still to do (overdue first), or null. */
export function urgent(items, now) {
  let best = null;
  for (const item of items) {
    if (status(item, now) < LATER) continue;
    if (!best || moment(item) < moment(best)) best = item;
  }
  return best;
}

/** Minutes from day 0 to an item's moment: its day, then its time (no time: the day's end). */
function moment(item) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(item.time || '');
  return day(item.due) * (24 * 60 + 1) + (m ? Number(m[1]) * 60 + Number(m[2]) : 24 * 60);
}

export const anyDated = items => items.some(dated);

/** When a reminder comes on its day with no time set: 9:00. */
export const REMIND_DEFAULT_MINUTE = 9 * 60;

/** Whether an item reminds: a day, words, still to do (a ticked repeating item: on its next day). */
export const remindable = item => dated(item) && !!(item.text || '').trim() && (!item.done || repeats(item));

/** Minutes into its day that an item reminds: its time, or 9:00. */
export function remindMinute(item) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(item.time || '');
  const v = m ? Number(m[1]) * 60 + Number(m[2]) : -1;
  return v >= 0 && v < 24 * 60 ? v : REMIND_DEFAULT_MINUTE;
}

// ------------------------------------------------------------------- edits from the picker

/** Gives the item this day; a weekly repeat gains its weekday, a monthly one moves to its date. */
export function setDay(item, n) {
  item.due = ymd(n);
  delete item.undoDue;
  if (typeof item.repeat === 'string' && item.repeat.startsWith('weekly:')) {
    const on = weekDays(item.repeat);
    on[weekday(n)] = true;
    item.repeat = weekly(on);
  } else if (monthDay(item.repeat) > 0) {
    item.repeat = 'monthly:' + civil(n)[2];
  }
}

/**
 * Sets how the item repeats ('daily', 'weekdays', 'weekly', 'monthly' or null) around its day
 * (today when it has none), then moves the day onto the repeat if it isn't one of its days.
 */
export function setRepeat(item, kind, now) {
  const d = dated(item) ? day(item.due) : now;
  if (!kind) item.repeat = null;
  else if (kind === 'weekly') { const on = new Array(8).fill(false); on[weekday(d)] = true; item.repeat = weekly(on); }
  else if (kind === 'monthly') item.repeat = 'monthly:' + civil(d)[2];
  else item.repeat = kind;
  delete item.undoDue;
  realign(item, d, now);
}

/** Turns one weekday of a weekly repeat on or off (never the last one). */
export function toggleWeekday(item, wd, now) {
  const on = weekDays(item.repeat);
  on[wd] = !on[wd];
  const next = weekly(on);
  if (!next) return;
  item.repeat = next;
  delete item.undoDue;
  realign(item, dated(item) ? day(item.due) : now, now);
}

function realign(item, d, now) {
  if (!item.repeat) { item.due = ymd(d); return; }
  // A day that is one of the repeat's stays (even overdue); any other moves to its next one.
  item.due = ymd(matches(item.repeat, d) ? d : first(item.repeat, Math.max(d, now)));
}

export function clear(item) {
  delete item.due; delete item.time; delete item.repeat; delete item.undoDue;
}

// ------------------------------------------------------------------- words

/** "Today", "Tomorrow", "Yesterday", "Fri" (this week), "9 Nov", "9 Nov 2027". */
export function when(n, now) {
  const diff = n - now;
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  if (diff === -1) return 'Yesterday';
  if (diff > 1 && diff < 7) return WEEKDAYS[weekday(n) - 1];
  const [y, m, d] = civil(n), [ty] = civil(now);
  return `${d} ${MONTHS[m - 1]}` + (y === ty ? '' : ` ${y}`);
}

/** "7:30" from "07:30". */
export function time(hhmm) {
  if (!/^\d{2}:\d{2}$/.test(hhmm || '')) return null;
  return hhmm[0] === '0' ? hhmm.slice(1) : hhmm;
}

/** "Daily", "Weekdays", "Mon Wed Fri", "Monthly". */
export function repeatShort(repeat) {
  if (repeat === 'daily') return 'Daily';
  if (repeat === 'weekdays') return 'Weekdays';
  if (monthDay(repeat) > 0) return 'Monthly';
  const on = weekDays(repeat), days = [];
  for (let d = 1; d <= 7; d++) if (on[d]) days.push(WEEKDAYS[d - 1]);
  return days.length === 7 ? 'Daily' : days.join(' ');
}

/** "Every day", "Every weekday", "Every Mon, Wed, Fri", "Every month on the 9th". */
export function repeatLong(repeat) {
  if (repeat === 'daily') return 'Every day';
  if (repeat === 'weekdays') return 'Every weekday';
  const md = monthDay(repeat);
  if (md) return `Every month on the ${md}${ordinal(md)}`;
  const days = repeatShort(repeat);
  return days === 'Daily' ? 'Every day' : 'Every ' + days.replace(/ /g, ', ');
}

function ordinal(n) {
  if (n % 100 >= 11 && n % 100 <= 13) return 'th';
  return n % 10 === 1 ? 'st' : n % 10 === 2 ? 'nd' : n % 10 === 3 ? 'rd' : 'th';
}

/** The pill under an item: "Today 7:30 · ↻ Mon Wed Fri", "Next Wed", "Overdue · 2 Nov". */
export function pill(item, now) {
  const s = status(item, now);
  if (s === NONE) return '';
  const w = when(day(item.due), now), t = time(item.time);
  let head = s === OVERDUE ? 'Overdue · ' + w : item.done && repeats(item) ? 'Next ' + w : w;
  if (t) head += ' ' + t;
  return repeats(item) ? `${head}  ·  ↻ ${repeatShort(item.repeat)}` : head;
}

/** The picker's headline: "Every Mon, Wed, Fri · next Mon 9 Nov, 7:30" or "Due tomorrow". */
export function summary(item, now) {
  if (!dated(item)) return 'No date yet';
  const d = day(item.due), diff = d - now;
  let w = when(d, now);
  if (diff >= -1 && diff <= 1) w = w.toLowerCase();
  else if (w.length === 3) { const [, m, dd] = civil(d); w += ` ${dd} ${MONTHS[m - 1]}`; }
  const t = time(item.time);
  const at = w + (t ? ', ' + t : '');
  if (repeats(item)) return `${repeatLong(item.repeat)}  ·  next ${at}`;
  return (!item.done && d < now ? 'Was due ' : 'Due ') + at;
}

/** The card's tag: "Overdue", "Today 7:30", "Fri"; "" when every dated item is done. */
export function tag(items, now) {
  const u = urgent(items, now);
  if (!u) return '';
  const s = status(u, now), t = time(u.time);
  const w = s === OVERDUE ? 'Overdue' : when(day(u.due), now) + (s === TODAY && t ? ' ' + t : '');
  return repeats(u) ? w + ' ↻' : w;
}
