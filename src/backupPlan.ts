/** Decisions for the automatic backup. Pure functions, easy to test. */

export type AutoBackupEvery = 'off' | 'daily' | 'weekly';

const DAY = 864e5;
/** Even an unchanged library is backed up again after this long, in case a change slipped past the fingerprint. */
const REFRESH = 30 * DAY;
/** automatic backups kept in Documents/Notiva */
export const AUTO_KEEP = 3;
export const AUTO_DIR = 'Notiva';

export interface AutoState {
  /** when the last automatic backup was written */
  at: number;
  /** library fingerprint at that moment */
  sig: string;
}

/** Is a new automatic backup due? */
export function autoBackupDue(every: AutoBackupEvery, last: AutoState | null, sig: string, docCount: number, now = Date.now()): boolean {
  if (every === 'off' || docCount === 0) return false;
  if (!last) return true;
  const age = now - last.at;
  if (age < (every === 'daily' ? DAY : 7 * DAY)) return false;
  return last.sig !== sig || age >= REFRESH;
}

/** FNV-1a, 32 bit: a short fingerprint of a long string. */
function fnv(s: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}

/**
 * Changes whenever a document (title, pages, folder, tags, edits…), clip, folder or template changes.
 * Clip pictures are left out: they never change after the clip is made, and they are large.
 */
export function librarySignature(m: { docs: unknown[]; clips: object[]; folders: unknown[]; templates: unknown[] }): string {
  const clips = m.clips.map((c) => ({ ...c, image: undefined }));
  const s = JSON.stringify([m.docs, clips, m.folders, m.templates]);
  return `${s.length.toString(36)}-${fnv(s)}`;
}

const AUTO_NAME = /^Notiva-Auto-.+\.notiva$/;
const PART_NAME = /^\.Notiva-Auto-.+\.notiva\.part$/;

/** File name of an automatic backup made at `d`; names sort by date. */
export function autoBackupName(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `Notiva-Auto-${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}.notiva`;
}

/** Which files in Documents/Notiva to delete: older automatic backups beyond `keep`, and unfinished ones. */
export function toPrune(names: string[], keep = AUTO_KEEP): string[] {
  const autos = names.filter((n) => AUTO_NAME.test(n)).sort().reverse();
  return [...autos.slice(keep), ...names.filter((n) => PART_NAME.test(n))];
}
