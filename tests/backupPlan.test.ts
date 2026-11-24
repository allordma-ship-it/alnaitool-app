import { describe, it, expect } from 'vitest';
import { autoBackupDue, autoBackupName, librarySignature, toPrune } from '../src/backupPlan';

const DAY = 864e5;
const now = Date.UTC(2026, 10, 25, 12);

describe('automatic backup: when', () => {
  it('first time: as soon as there is a document', () => {
    expect(autoBackupDue('weekly', null, 's', 1, now)).toBe(true);
    expect(autoBackupDue('weekly', null, 's', 0, now)).toBe(false);
  });

  it('never when turned off', () => {
    expect(autoBackupDue('off', null, 's', 5, now)).toBe(false);
  });

  it('weekly: not before 7 days, then only if something changed', () => {
    expect(autoBackupDue('weekly', { at: now - 6 * DAY, sig: 'old' }, 'new', 5, now)).toBe(false);
    expect(autoBackupDue('weekly', { at: now - 7 * DAY, sig: 'old' }, 'new', 5, now)).toBe(true);
    expect(autoBackupDue('weekly', { at: now - 8 * DAY, sig: 'same' }, 'same', 5, now)).toBe(false);
  });

  it('daily: after one day', () => {
    expect(autoBackupDue('daily', { at: now - 23 * 36e5, sig: 'a' }, 'b', 5, now)).toBe(false);
    expect(autoBackupDue('daily', { at: now - DAY, sig: 'a' }, 'b', 5, now)).toBe(true);
  });

  it('unchanged library: again after 30 days anyway', () => {
    expect(autoBackupDue('weekly', { at: now - 30 * DAY, sig: 'same' }, 'same', 5, now)).toBe(true);
  });
});

describe('automatic backup: library fingerprint', () => {
  const lib = {
    docs: [{ id: 'd1', title: 'Asthma', updatedAt: 1, pageIds: ['p1'] }],
    clips: [{ id: 'c1', note: 'Frage?', image: 'data:image/jpeg;base64,AAAA' }],
    folders: [{ id: 'f1', name: 'Pneumologie' }],
    templates: [],
  };
  const sig = librarySignature(lib);

  it('stays the same when nothing changed', () => {
    expect(librarySignature(structuredClone(lib))).toBe(sig);
  });

  it('changes on a rename, a folder move, a clip note, a new folder', () => {
    expect(librarySignature({ ...lib, docs: [{ ...lib.docs[0], title: 'COPD' }] })).not.toBe(sig);
    expect(librarySignature({ ...lib, docs: [{ ...lib.docs[0], folderId: 'f1' }] })).not.toBe(sig);
    expect(librarySignature({ ...lib, clips: [{ ...lib.clips[0], note: 'Antwort' }] })).not.toBe(sig);
    expect(librarySignature({ ...lib, folders: [...lib.folders, { id: 'f2', name: 'Kardiologie' }] })).not.toBe(sig);
  });

  it('ignores clip pictures (large, never edited)', () => {
    expect(librarySignature({ ...lib, clips: [{ ...lib.clips[0], image: 'data:other' }] })).toBe(sig);
  });
});

describe('automatic backup: files', () => {
  it('names sort by date', () => {
    const a = autoBackupName(new Date(2026, 0, 9, 8, 5));
    const b = autoBackupName(new Date(2026, 10, 25, 14, 30));
    expect(a).toBe('Notiva-Auto-2026-01-09_0805.notiva');
    expect([b, a].sort()).toEqual([a, b]);
  });

  it('keeps the newest 3, removes older ones and unfinished ones, leaves other files alone', () => {
    const names = [
      'Notiva-Auto-2026-11-01_1000.notiva',
      'Notiva-Auto-2026-11-08_1000.notiva',
      'Notiva-Auto-2026-11-15_1000.notiva',
      'Notiva-Auto-2026-11-22_1000.notiva',
      '.Notiva-Auto-2026-11-23_1000.notiva.part',
      'Notiva-Backup-2026-10-01_0900.notiva', // shared by hand: not ours to delete
      'Lecture.pdf',
    ];
    expect(toPrune(names).sort()).toEqual(['.Notiva-Auto-2026-11-23_1000.notiva.part', 'Notiva-Auto-2026-11-01_1000.notiva']);
  });
});
