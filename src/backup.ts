import { Capacitor } from '@capacitor/core';
import { dumpAll, restoreAll } from './db';
import { encodeBackup, decodeBackup, chunks, BackupError } from './backupFormat';
import { toBase64 } from './share';
import type { DocMeta, PageData, Clip, Folder, CustomTemplate } from './types';
import { t } from './i18n';
import { toast } from './ui';

const LAST = 'alnai-last-backup';

export function lastBackup(): number | null {
  try {
    const v = Number(localStorage.getItem(LAST));
    return v > 0 ? v : null;
  } catch {
    return null;
  }
}

function stamp() {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}`;
}

/** Writes every notebook, PDF, note, clip and voice memo into one file and offers it to save or share. */
export async function createBackup() {
  toast(t('preparing'));
  const data = await dumpAll();
  const parts = encodeBackup(data);
  const name = `Notiva-Backup-${stamp()}.notiva`;
  if (Capacitor.isNativePlatform()) {
    const { Filesystem, Directory } = await import('@capacitor/filesystem');
    const { Share } = await import('@capacitor/share');
    // written in pieces: base64 of a whole library at once would not fit in memory
    let first = true;
    for (const piece of chunks(parts, 3 * 1024 * 1024)) {
      const args = { path: name, data: toBase64(piece), directory: Directory.Cache };
      if (first) await Filesystem.writeFile(args);
      else await Filesystem.appendFile(args);
      first = false;
    }
    const { uri } = await Filesystem.getUri({ path: name, directory: Directory.Cache });
    try {
      await Share.share({ title: t('backup'), files: [uri], dialogTitle: t('backupSave') });
    } catch (e) {
      if (!/cancel/i.test(String(e))) throw e;
    }
  } else {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob(parts as BlobPart[], { type: 'application/octet-stream' }));
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 30_000);
  }
  try { localStorage.setItem(LAST, String(Date.now())); } catch { /* private mode */ }
  toast(`${t('backupDone')} · ${data.docs.length} ${t('documents')}`);
}

/** Reads a backup file and adds its contents to the library. Returns false if the user cancelled. */
export async function restoreBackup(file: File): Promise<boolean> {
  let b;
  try {
    b = decodeBackup(await file.arrayBuffer());
  } catch (e) {
    console.error(e);
    toast(e instanceof BackupError ? t('restoreError') : String(e));
    return false;
  }
  const when = new Date(b.createdAt).toLocaleString(document.documentElement.lang);
  if (!confirm(`${t('restoreConfirm')}\n\n${when}\n${b.docs.length} ${t('documents')} · ${b.clips.length} ${t('clipsCount')}`)) return false;
  toast(t('loading'));
  await restoreAll({
    docs: b.docs as DocMeta[], pages: b.pages as PageData[], clips: b.clips as Clip[], folders: b.folders as Folder[],
    templates: (b.templates ?? []) as CustomTemplate[], files: b.files,
  });
  toast(t('restoreDone'));
  return true;
}
