/**
 * The backup file: one self-contained file the user can keep in Drive, on a USB stick, anywhere.
 *
 *   "ALNAIBK1" (8 bytes) | header length (uint32, little endian) | header (UTF-8 JSON) | file bytes, in header order
 *
 * The header holds the documents, pages, clips and folders as JSON, and the id and length of every stored
 * file (PDFs, pictures, voice memos). The file bytes follow raw, so a backup of 500 MB of PDFs stays 500 MB.
 * Because every file's length is in the header, a backup can be written and read one file at a time:
 * a large library never has to fit in memory at once.
 */
const MAGIC = 'ALNAIBK1';
/** bytes before the header JSON: magic + header length */
export const PREFIX = 12;
/** A header this big is not a Notiva backup (real ones are a few MB even for large libraries). */
const MAX_HEADER = 512 * 1024 * 1024;

export interface FileEntry {
  id: string;
  len: number;
}

export interface BackupMeta<D = unknown, P = unknown, C = unknown, F = unknown> {
  docs: D[];
  pages: P[];
  clips: C[];
  folders: F[];
  /** custom page templates (older backups have none) */
  templates?: unknown[];
}

export interface BackupContent<D = unknown, P = unknown, C = unknown, F = unknown> extends BackupMeta<D, P, C, F> {
  files: { id: string; bytes: Uint8Array }[];
}

export interface BackupHeader extends BackupMeta {
  version: 1;
  createdAt: number;
  templates: unknown[];
  files: FileEntry[];
}

export interface Backup<D = unknown, P = unknown, C = unknown, F = unknown> extends BackupContent<D, P, C, F> {
  version: 1;
  createdAt: number;
}

export class BackupError extends Error {}

/** Magic, length and header JSON: everything that comes before the file bytes. */
export function encodeHeader(b: BackupMeta, files: FileEntry[], createdAt = Date.now()): Uint8Array {
  const header = {
    version: 1, createdAt,
    docs: b.docs, pages: b.pages, clips: b.clips, folders: b.folders, templates: b.templates ?? [],
    files: files.map((f) => ({ id: f.id, len: f.len })),
  };
  const json = new TextEncoder().encode(JSON.stringify(header));
  const out = new Uint8Array(PREFIX + json.byteLength);
  out.set(new TextEncoder().encode(MAGIC), 0);
  new DataView(out.buffer).setUint32(8, json.byteLength, true);
  out.set(json, PREFIX);
  return out;
}

/** The backup as a list of parts, so a large library is never copied into one big buffer. */
export function encodeBackup(b: BackupContent, createdAt = Date.now()): Uint8Array[] {
  const entries = b.files.map((f) => ({ id: f.id, len: f.bytes.byteLength }));
  return [encodeHeader(b, entries, createdAt), ...b.files.map((f) => f.bytes)];
}

/** Reads the first PREFIX bytes: checks the magic and returns the header length. */
export function headerLength(prefix: Uint8Array): number {
  if (prefix.byteLength < PREFIX || new TextDecoder().decode(prefix.subarray(0, 8)) !== MAGIC) throw new BackupError('not a backup');
  const len = new DataView(prefix.buffer, prefix.byteOffset, PREFIX).getUint32(8, true);
  if (len > MAX_HEADER) throw new BackupError('header too large');
  return len;
}

const isObj = (x: unknown): x is Record<string, unknown> => typeof x === 'object' && x !== null && !Array.isArray(x);
const hasId = (x: unknown) => isObj(x) && typeof x.id === 'string' && x.id.length > 0 && x.id.length <= 200;

/**
 * Parses and checks the header, so nothing malformed is written into the library.
 * `totalSize` is the size of the whole backup file; the files listed must fit inside it.
 */
export function parseHeader(json: Uint8Array, totalSize: number): BackupHeader {
  let h: unknown;
  try {
    h = JSON.parse(new TextDecoder().decode(json));
  } catch {
    throw new BackupError('unreadable header');
  }
  if (!isObj(h)) throw new BackupError('unreadable header');
  if (h.version !== 1) throw new BackupError(`unknown version ${String(h.version)}`);
  const list = (k: string, optional = false): unknown[] => {
    const v = h[k];
    if (v === undefined && optional) return [];
    if (!Array.isArray(v)) throw new BackupError(`${k} missing`);
    if (k !== 'files' && !v.every(hasId)) throw new BackupError(`${k}: entry without id`);
    return v;
  };
  const docs = list('docs'), pages = list('pages'), clips = list('clips'), folders = list('folders');
  const templates = list('templates', true);
  if (!docs.every((d) => Array.isArray((d as { pageIds?: unknown }).pageIds) && ((d as { pageIds: unknown[] }).pageIds).every((p) => typeof p === 'string'))) {
    throw new BackupError('docs: page list missing');
  }
  const files = list('files').map((f) => {
    if (!hasId(f) || !Number.isSafeInteger((f as { len?: unknown }).len) || (f as { len: number }).len < 0) throw new BackupError('files: bad entry');
    return { id: (f as FileEntry).id, len: (f as FileEntry).len };
  });
  const end = PREFIX + json.byteLength + files.reduce((n, f) => n + f.len, 0);
  if (end > totalSize) throw new BackupError('truncated');
  return {
    version: 1, createdAt: typeof h.createdAt === 'number' ? h.createdAt : 0,
    docs, pages, clips, folders, templates, files,
  };
}

/** Reads and checks only the header of a backup file (a File or Blob); the file bytes stay on disk. */
export async function readBackupHeader(blob: Blob): Promise<{ header: BackupHeader; dataStart: number }> {
  const len = headerLength(new Uint8Array(await blob.slice(0, PREFIX).arrayBuffer()));
  if (PREFIX + len > blob.size) throw new BackupError('truncated');
  const json = new Uint8Array(await blob.slice(PREFIX, PREFIX + len).arrayBuffer());
  return { header: parseHeader(json, blob.size), dataStart: PREFIX + len };
}

/** Decodes a whole backup held in memory (tests and small files). */
export function decodeBackup(buf: ArrayBuffer): Backup {
  const bytes = new Uint8Array(buf);
  const len = headerLength(bytes);
  if (PREFIX + len > bytes.byteLength) throw new BackupError('truncated');
  const header = parseHeader(bytes.subarray(PREFIX, PREFIX + len), bytes.byteLength);
  let off = PREFIX + len;
  const files = header.files.map((f) => {
    const out = { id: f.id, bytes: bytes.slice(off, off + f.len) };
    off += f.len;
    return out;
  });
  const { docs, pages, clips, folders, templates, createdAt } = header;
  return { version: 1, createdAt, docs, pages, clips, folders, templates, files };
}

/** Splits parts into pieces of at most `size` bytes (for writing a large file in steps). */
export function* chunks(parts: Uint8Array[], size: number): Generator<Uint8Array> {
  for (const p of parts) for (let i = 0; i < p.byteLength; i += size) yield p.subarray(i, i + size);
}
