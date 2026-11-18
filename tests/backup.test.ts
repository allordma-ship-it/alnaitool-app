import { describe, it, expect } from 'vitest';
import { encodeBackup, decodeBackup, chunks, BackupError } from '../src/backupFormat';

const join = (parts: Uint8Array[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.byteLength, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.byteLength; }
  return out.buffer;
};

describe('backup format', () => {
  const content = {
    docs: [{ id: 'd1', title: 'الربو – Asthma' }],
    pages: [{ id: 'p1', strokes: [{ pts: [1, 2, 0.5] }] }],
    clips: [{ id: 'c1', note: 'Frage?' }],
    folders: [{ id: 'f1', name: 'Pneumologie' }],
    files: [{ id: 'd1', bytes: new Uint8Array([37, 80, 68, 70]) }, { id: 'a1', bytes: new Uint8Array(70_000).fill(7) }],
  };

  it('round-trips everything, including binary files', () => {
    const b = decodeBackup(join(encodeBackup(content, 123)));
    expect(b.createdAt).toBe(123);
    expect(b.docs).toEqual(content.docs);
    expect(b.pages).toEqual(content.pages);
    expect(b.clips).toEqual(content.clips);
    expect(b.folders).toEqual(content.folders);
    expect(b.files.map((f) => f.id)).toEqual(['d1', 'a1']);
    expect([...b.files[0].bytes]).toEqual([37, 80, 68, 70]);
    expect(b.files[1].bytes.byteLength).toBe(70_000);
  });

  it('rejects other files and cut-off backups', () => {
    expect(() => decodeBackup(new TextEncoder().encode('%PDF-1.7 nope').buffer)).toThrow(BackupError);
    const whole = new Uint8Array(join(encodeBackup(content)));
    expect(() => decodeBackup(whole.slice(0, whole.byteLength - 10).buffer)).toThrow(BackupError);
  });

  it('chunks write the same bytes in pieces', () => {
    const parts = encodeBackup(content);
    const pieces = [...chunks(parts, 4096)];
    expect(pieces.every((p) => p.byteLength <= 4096)).toBe(true);
    expect(new Uint8Array(join(pieces))).toEqual(new Uint8Array(join(parts)));
  });
});
