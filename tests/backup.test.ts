import { describe, it, expect } from 'vitest';
import { encodeBackup, encodeHeader, decodeBackup, readBackupHeader, chunks, BackupError } from '../src/backupFormat';

const join = (parts: Uint8Array[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.byteLength, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.byteLength; }
  return out.buffer;
};

describe('backup format', () => {
  const content = {
    docs: [{ id: 'd1', title: 'الربو – Asthma', pageIds: ['p1'] }],
    pages: [{ id: 'p1', strokes: [{ pts: [1, 2, 0.5] }] }],
    clips: [{ id: 'c1', note: 'Frage?' }],
    folders: [{ id: 'f1', name: 'Pneumologie' }],
    templates: [{ id: 'tpl_1', name: 'Planner', fileId: 'tpl_1' }],
    files: [{ id: 'd1', bytes: new Uint8Array([37, 80, 68, 70]) }, { id: 'a1', bytes: new Uint8Array(70_000).fill(7) }],
  };

  it('round-trips everything, including binary files', () => {
    const b = decodeBackup(join(encodeBackup(content, 123)));
    expect(b.createdAt).toBe(123);
    expect(b.docs).toEqual(content.docs);
    expect(b.pages).toEqual(content.pages);
    expect(b.clips).toEqual(content.clips);
    expect(b.folders).toEqual(content.folders);
    expect(b.templates).toEqual(content.templates);
    expect(b.files.map((f) => f.id)).toEqual(['d1', 'a1']);
    expect([...b.files[0].bytes]).toEqual([37, 80, 68, 70]);
    expect(b.files[1].bytes.byteLength).toBe(70_000);
  });

  it('reads older backups without templates', () => {
    const old = decodeBackup(join(encodeBackup({ ...content, templates: undefined })));
    expect(old.templates).toEqual([]);
  });

  it('rejects other files and cut-off backups', () => {
    expect(() => decodeBackup(new TextEncoder().encode('%PDF-1.7 nope').buffer)).toThrow(BackupError);
    const whole = new Uint8Array(join(encodeBackup(content)));
    expect(() => decodeBackup(whole.slice(0, whole.byteLength - 10).buffer)).toThrow(BackupError);
  });

  it('reads the header from a Blob and each file from its own slice, as the restore does', async () => {
    const blob = new Blob(encodeBackup(content, 456) as BlobPart[]);
    const { header, dataStart } = await readBackupHeader(blob);
    expect(header.createdAt).toBe(456);
    expect(header.docs).toEqual(content.docs.map((d) => ({ ...d })));
    expect(header.files).toEqual([{ id: 'd1', len: 4 }, { id: 'a1', len: 70_000 }]);
    let off = dataStart;
    const got: Uint8Array[] = [];
    for (const f of header.files) {
      got.push(new Uint8Array(await blob.slice(off, off + f.len).arrayBuffer()));
      off += f.len;
    }
    expect(off).toBe(blob.size);
    expect([...got[0]]).toEqual([37, 80, 68, 70]);
    expect(got[1].every((b) => b === 7)).toBe(true);
  });

  it('writing the header and then each file separately gives the same file as encodeBackup', () => {
    const entries = content.files.map((f) => ({ id: f.id, len: f.bytes.byteLength }));
    const streamed = join([encodeHeader(content, entries, 9), ...content.files.map((f) => f.bytes)]);
    expect(new Uint8Array(streamed)).toEqual(new Uint8Array(join(encodeBackup(content, 9))));
  });

  it('rejects headers that would put broken entries into the library', async () => {
    const withHeader = (h: object) => {
      const json = new TextEncoder().encode(JSON.stringify(h));
      const prefix = new Uint8Array(12);
      prefix.set(new TextEncoder().encode('ALNAIBK1'));
      new DataView(prefix.buffer).setUint32(8, json.byteLength, true);
      return new Blob([prefix, json]);
    };
    const ok = { version: 1, createdAt: 1, docs: [{ id: 'd', pageIds: [] }], pages: [], clips: [], folders: [], files: [] };
    await expect(readBackupHeader(withHeader(ok))).resolves.toBeTruthy();
    const bad: object[] = [
      { ...ok, version: 2 },
      { ...ok, docs: 'x' },
      { ...ok, docs: [{ title: 'no id', pageIds: [] }] },
      { ...ok, docs: [{ id: 'd' }] }, // no page list
      { ...ok, docs: [{ id: 'd', pageIds: [3] }] },
      { ...ok, pages: [{ id: '' }] },
      { ...ok, clips: [null] },
      { ...ok, files: [{ id: 'f', len: -1 }] },
      { ...ok, files: [{ id: 'f', len: 1.5 }] },
      { ...ok, files: [{ id: 'f', len: 10 }] }, // bytes missing after the header
    ];
    for (const h of bad) await expect(readBackupHeader(withHeader(h)), JSON.stringify(h)).rejects.toThrow(BackupError);
    const notJson = new Uint8Array(await withHeader(ok).arrayBuffer());
    notJson[12] = 0x7b + 1; // '{' → '|'
    await expect(readBackupHeader(new Blob([notJson]))).rejects.toThrow(BackupError);
  });

  it('chunks write the same bytes in pieces', () => {
    const parts = encodeBackup(content);
    const pieces = [...chunks(parts, 4096)];
    expect(pieces.every((p) => p.byteLength <= 4096)).toBe(true);
    expect(new Uint8Array(join(pieces))).toEqual(new Uint8Array(join(parts)));
  });
});
