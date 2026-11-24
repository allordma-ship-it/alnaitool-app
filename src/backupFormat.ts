/**
 * The backup file: one self-contained file the user can keep in Drive, on a USB stick, anywhere.
 *
 *   "ALNAIBK1" (8 bytes) | header length (uint32, little endian) | header (UTF-8 JSON) | file bytes, in header order
 *
 * The header holds the documents, pages, clips and folders as JSON, and the id and length of every stored
 * file (PDFs, pictures, voice memos). The file bytes follow raw, so a backup of 500 MB of PDFs stays 500 MB.
 */
const MAGIC = 'ALNAIBK1';

export interface BackupContent<D = unknown, P = unknown, C = unknown, F = unknown> {
  docs: D[];
  pages: P[];
  clips: C[];
  folders: F[];
  /** custom page templates (older backups have none) */
  templates?: unknown[];
  files: { id: string; bytes: Uint8Array }[];
}

export interface Backup<D = unknown, P = unknown, C = unknown, F = unknown> extends BackupContent<D, P, C, F> {
  version: 1;
  createdAt: number;
}

/** The backup as a list of parts, so a large library is never copied into one big buffer. */
export function encodeBackup(b: BackupContent, createdAt = Date.now()): Uint8Array[] {
  const header = {
    version: 1, createdAt,
    docs: b.docs, pages: b.pages, clips: b.clips, folders: b.folders, templates: b.templates ?? [],
    files: b.files.map((f) => ({ id: f.id, len: f.bytes.byteLength })),
  };
  const json = new TextEncoder().encode(JSON.stringify(header));
  const head = new Uint8Array(12);
  head.set(new TextEncoder().encode(MAGIC), 0);
  new DataView(head.buffer).setUint32(8, json.byteLength, true);
  return [head, json, ...b.files.map((f) => f.bytes)];
}

export class BackupError extends Error {}

export function decodeBackup(buf: ArrayBuffer): Backup {
  const bytes = new Uint8Array(buf);
  if (bytes.byteLength < 12 || new TextDecoder().decode(bytes.subarray(0, 8)) !== MAGIC) throw new BackupError('not a backup');
  const len = new DataView(buf).getUint32(8, true);
  if (12 + len > bytes.byteLength) throw new BackupError('truncated');
  const header = JSON.parse(new TextDecoder().decode(bytes.subarray(12, 12 + len)));
  if (header.version !== 1) throw new BackupError(`unknown version ${header.version}`);
  let off = 12 + len;
  const files = (header.files as { id: string; len: number }[]).map((f) => {
    if (off + f.len > bytes.byteLength) throw new BackupError('truncated');
    const out = { id: f.id, bytes: bytes.slice(off, off + f.len) };
    off += f.len;
    return out;
  });
  return {
    version: 1, createdAt: header.createdAt,
    docs: header.docs ?? [], pages: header.pages ?? [], clips: header.clips ?? [], folders: header.folders ?? [],
    templates: header.templates ?? [], files,
  };
}

/** Splits parts into pieces of at most `size` bytes (for writing a large file in steps). */
export function* chunks(parts: Uint8Array[], size: number): Generator<Uint8Array> {
  for (const p of parts) for (let i = 0; i < p.byteLength; i += size) yield p.subarray(i, i + size);
}
