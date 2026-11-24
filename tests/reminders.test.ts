import { describe, it, expect } from 'vitest';
import { nextFire, activeReminders, toLocalInput, defaultTime } from '../src/reminderTime';
import type { Reminder } from '../src/types';

const at = (s: string) => new Date(s).getTime();
const r = (when: string, repeat: Reminder['repeat']): Reminder => ({ id: 1, at: at(when), page: 0, note: '', repeat });

describe('reminder times', () => {
  const now = at('2026-11-18T12:00:00'); // a Wednesday

  it('one-off: rings once, then is gone', () => {
    expect(nextFire(r('2026-11-19T08:00:00', 'none'), now)).toBe(at('2026-11-19T08:00:00'));
    expect(nextFire(r('2026-11-17T08:00:00', 'none'), now)).toBeNull();
  });

  it('daily: the next day at the same time once today has passed', () => {
    expect(nextFire(r('2026-11-10T08:30:00', 'daily'), now)).toBe(at('2026-11-19T08:30:00'));
    expect(nextFire(r('2026-11-10T18:00:00', 'daily'), now)).toBe(at('2026-11-18T18:00:00'));
  });

  it('weekly: the same weekday', () => {
    // 2026-11-16 is a Monday → next Monday
    expect(nextFire(r('2026-11-16T09:00:00', 'weekly'), now)).toBe(at('2026-11-23T09:00:00'));
    // a Wednesday earlier today → next week
    expect(nextFire(r('2026-11-11T09:00:00', 'weekly'), now)).toBe(at('2026-11-25T09:00:00'));
  });

  it('lists only reminders that will still ring, soonest first', () => {
    const list = [r('2026-11-17T08:00:00', 'none'), { ...r('2026-11-25T08:00:00', 'none'), id: 2 }, { ...r('2026-11-01T20:00:00', 'daily'), id: 3 }];
    expect(activeReminders(list, now).map((x) => x.id)).toEqual([3, 2]);
  });

  it('formats for the date-time field and defaults to tomorrow 08:00', () => {
    expect(toLocalInput(at('2026-03-05T07:09:00'))).toBe('2026-03-05T07:09');
    expect(toLocalInput(defaultTime(now))).toBe('2026-11-19T08:00');
  });
});
