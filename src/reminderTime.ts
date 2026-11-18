import type { Reminder } from './types';

/** When a reminder rings next (at or after `now`); null for a one-off that has passed. */
export function nextFire(r: Reminder, now = Date.now()): number | null {
  if (r.repeat === 'none') return r.at >= now ? r.at : null;
  const first = new Date(r.at);
  if (r.at >= now) return r.at;
  const d = new Date(now);
  d.setHours(first.getHours(), first.getMinutes(), 0, 0);
  if (r.repeat === 'daily') {
    if (d.getTime() < now) d.setDate(d.getDate() + 1);
    return d.getTime();
  }
  // weekly: same weekday as the first time
  let add = (first.getDay() - d.getDay() + 7) % 7;
  if (add === 0 && d.getTime() < now) add = 7;
  d.setDate(d.getDate() + add);
  return d.getTime();
}

/** Reminders that will still ring, soonest first. */
export function activeReminders(list: Reminder[] | undefined, now = Date.now()): Reminder[] {
  return (list ?? []).filter((r) => nextFire(r, now) != null).sort((a, b) => nextFire(a, now)! - nextFire(b, now)!);
}

export function newReminderId(): number {
  return 1 + Math.floor(Math.random() * 0x7ffffffe);
}

/** Default time for a new reminder: tomorrow at 08:00 (a study reminder for the morning). */
export function defaultTime(now = Date.now()): number {
  const d = new Date(now);
  d.setDate(d.getDate() + 1);
  d.setHours(8, 0, 0, 0);
  return d.getTime();
}

/** Value for <input type="datetime-local"> in local time. */
export function toLocalInput(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}
