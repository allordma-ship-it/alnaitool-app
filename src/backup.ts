import { Capacitor } from '@capacitor/core';
import { dumpMeta, getFileRaw, putFileRaw, restoreMeta, libraryOutline } from './db';
import { encodeHeader, readBackupHeader, chunks, BackupError, type FileEntry } from './backupFormat';
import { autoBackupDue, autoBackupName, librarySignature, toPrune, AUTO_DIR, type AutoState } from './backupPlan';
import { settings } from './settings';
import { toBase64 } from './share';
import type { DocMeta, PageData, Clip, Folder, CustomTemplate } from './types';
import { t } from './i18n';
import { toast } from './ui';
import { backupKeys, addBackupKeys, type BackupKey } from './lock';

const LAST = 'alnai-last-backup';
const AUTO = 'notiva-auto-backup';
const AUTO_ERROR = 'notiva-auto-backup-error';
/** base64 of a piece must fit in memory next to everything else: 3 MB → 4 MB of text */
const PIECE = 3 * 1024 * 1024;

const read = (k: string) => { try { return localStorage.getItem(k); } catch { return null; } };
const write = (k: string, v: string | null) => {
  try { if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch { /* private mode */ }
};

/** Time of the last backup of any kind (shared, or automatic). */
export function lastBackup(): number | null {
  const v = Number(read(LAST));
  return v > 0 ? v : null;
}

export function lastAutoBackup(): AutoState | null {
  try {
    const v = JSON.parse(read(AUTO) ?? 'null') as AutoState | null;
    return v && typeof v.at === 'number' && typeof v.sig === 'string' ? v : null;
  } catch {
    return null;
  }
}

/** Why the last automatic backup failed, if it did (cleared by the next one that works). */
export function autoBackupError(): string | null {
  return read(AUTO_ERROR);
}

function stamp() {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}`;
}

type Sink = (bytes: Uint8Array) => Promise<void>;

/**
 * Writes the whole library through `sink`. Stored files are read one at a time (once to learn their sizes for the
 * header, once to write them), so even a library of several hundred MB never sits in memory at once.
 */
async function writeLibrary(sink: Sink): Promise<{ docs: number }> {
  const meta = await dumpMeta();
  const files: FileEntry[] = [];
  // raw: files of locked documents stay encrypted in the backup, and no key is needed to write it
  for (const id of meta.fileIds) {
    const b = await getFileRaw(id);
    if (b) files.push({ id, len: b.byteLength });
  }
  // the keys of locked documents travel wrapped with their recovery code only (a PIN could be guessed offline)
  await sink(encodeHeader({ ...meta, keys: backupKeys() }, files));
  for (const f of files) {
    const b = await getFileRaw(f.id);
    // edited while the backup ran: the header would not match, so start again later
    if (!b || b.byteLength !== f.len) throw new BackupError('library changed during the backup');
    await sink(new Uint8Array(b));
  }
  return { docs: meta.docs.length };
}

/** A sink that writes a file through Capacitor in pieces (base64 of a whole PDF at once would not fit in memory). */
async function fileSink(path: string, directory: import('@capacitor/filesystem').Directory): Promise<Sink> {
  const { Filesystem } = await import('@capacitor/filesystem');
  let first = true;
  return async (bytes) => {
    for (const piece of chunks([bytes], PIECE)) {
      const data = toBase64(piece);
      if (first) await Filesystem.writeFile({ path, data, directory, recursive: true });
      else await Filesystem.appendFile({ path, data, directory });
      first = false;
    }
  };
}

const isCancel = (e: unknown) => /cancel/i.test(String(e));

/**
 * Writes every notebook, PDF, note, clip and voice memo into one file and offers it to save or share.
 * Returns false when the share sheet was closed without saving (then it does not count as a backup).
 */
export async function createBackup(): Promise<boolean> {
  toast(t('preparing'));
  const name = `Notiva-Backup-${stamp()}.notiva`;
  let docs: number;
  if (Capacitor.isNativePlatform()) {
    const { Filesystem, Directory } = await import('@capacitor/filesystem');
    const { Share } = await import('@capacitor/share');
    // earlier shared backups are copies of the whole library in the cache: remove them first
    try {
      const { files } = await Filesystem.readdir({ path: '', directory: Directory.Cache });
      for (const f of files) if (/^Notiva-Backup-.+\.notiva$/.test(f.name)) await Filesystem.deleteFile({ path: f.name, directory: Directory.Cache });
    } catch { /* nothing to clean */ }
    ({ docs } = await writeLibrary(await fileSink(name, Directory.Cache)));
    const { uri } = await Filesystem.getUri({ path: name, directory: Directory.Cache });
    try {
      await Share.share({ title: t('backup'), files: [uri], dialogTitle: t('backupSave') });
    } catch (e) {
      if (isCancel(e)) return false;
      throw e;
    }
  } else {
    const parts: Uint8Array[] = [];
    ({ docs } = await writeLibrary(async (b) => void parts.push(b)));
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob(parts as BlobPart[], { type: 'application/octet-stream' }));
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 30_000);
  }
  write(LAST, String(Date.now()));
  toast(`${t('backupDone')} · ${docs} ${t('documents')}`);
  return true;
}

// ---------------------------------------------------------------- automatic backup (Android app)

let running = false;

/**
 * Writes a backup into Documents/Notiva when one is due (see autoBackupDue) and keeps the newest few.
 * Files there stay when the app is uninstalled, so notes can be restored after a reinstall or on a new tablet.
 * Quiet: shows one short note when it worked, and remembers the reason when it did not. True when one was written.
 */
export async function maybeAutoBackup(): Promise<boolean> {
  if (running || !Capacitor.isNativePlatform() || settings.autoBackup === 'off') return false;
  running = true;
  const { Filesystem, Directory } = await import('@capacitor/filesystem');
  const name = autoBackupName();
  const part = `${AUTO_DIR}/.${name}.part`;
  try {
    const outline = await libraryOutline();
    const sig = librarySignature(outline);
    if (!autoBackupDue(settings.autoBackup, lastAutoBackup(), sig, outline.docs.length)) return false;
    // written under a hidden temporary name and renamed at the end: a backup cut off half way never looks complete
    await writeLibrary(await fileSink(part, Directory.Documents));
    await Filesystem.rename({ from: part, to: `${AUTO_DIR}/${name}`, directory: Directory.Documents, toDirectory: Directory.Documents });
    const now = Date.now();
    write(AUTO, JSON.stringify({ at: now, sig } satisfies AutoState));
    write(LAST, String(now));
    write(AUTO_ERROR, null);
    try {
      const { files } = await Filesystem.readdir({ path: AUTO_DIR, directory: Directory.Documents });
      for (const old of toPrune(files.map((f) => f.name))) await Filesystem.deleteFile({ path: `${AUTO_DIR}/${old}`, directory: Directory.Documents });
    } catch { /* after a reinstall older files are not ours to list: they simply stay */ }
    toast(t('autoBackupDone'));
    return true;
  } catch (e) {
    console.error(e);
    write(AUTO_ERROR, `${new Date().toLocaleString(document.documentElement.lang)}: ${e instanceof Error ? e.message : String(e)}`);
    try { await Filesystem.deleteFile({ path: part, directory: Directory.Documents }); } catch { /* never written */ }
    return false;
  } finally {
    running = false;
  }
}

// ---------------------------------------------------------------- restore

/**
 * Reads a backup file and adds its contents to the library. Returns false if it was cancelled or failed.
 * The file is read piece by piece (header first, then one stored file at a time), so a large backup does not
 * have to fit in memory, and it is checked before anything is written.
 */
export async function restoreBackup(file: File): Promise<boolean> {
  let head;
  try {
    head = await readBackupHeader(file);
  } catch (e) {
    console.error(e);
    toast(e instanceof BackupError ? t('restoreError') : String(e));
    return false;
  }
  const b = head.header;
  const when = new Date(b.createdAt).toLocaleString(document.documentElement.lang);
  if (!confirm(`${t('restoreConfirm')}\n\n${when}\n${b.docs.length} ${t('documents')} · ${b.clips.length} ${t('clipsCount')}`)) return false;
  try {
    // files first, documents last: a restore that stops half way never leaves a document without its PDF
    let off = head.dataStart;
    for (let i = 0; i < b.files.length; i++) {
      const f = b.files[i];
      const bytes = await file.slice(off, off + f.len).arrayBuffer();
      if (bytes.byteLength !== f.len) throw new BackupError('truncated');
      await putFileRaw(f.id, bytes);
      off += f.len;
      if (i % 10 === 9) toast(`${t('loading')} ${i + 1}/${b.files.length}`);
    }
    // locked documents arrive encrypted: remember their keys, which that backup's recovery code opens
    addBackupKeys(b.keys as BackupKey[]);
    await restoreMeta({
      docs: b.docs as DocMeta[], pages: b.pages as PageData[], clips: b.clips as Clip[], folders: b.folders as Folder[],
      templates: b.templates as CustomTemplate[],
    });
  } catch (e) {
    console.error(e);
    toast(`${t('restoreFailed')}: ${e instanceof Error ? e.message : String(e)}`);
    return false;
  }
  toast(t('restoreDone'));
  return true;
}
