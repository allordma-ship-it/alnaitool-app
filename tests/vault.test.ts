import { describe, it, expect } from 'vitest';
import {
  newRawKey, importDataKey, wrapKey, unwrapKey, newRecoveryCode, normalizeRecoveryCode, sealJson, openJson,
  sealFile, openFile, sealedFileKeyId, isSealed, VaultError, LockedError, needKey, rememberKey, wipeKeys, documentOpened,
  documentClosed, cachedKey, newKeyId,
} from '../src/vault';

const ITER = 1000; // fast in tests; the app uses PIN_ITER / REC_ITER

describe('key wrapping', () => {
  it('the PIN unwraps the key, a wrong PIN does not', async () => {
    const raw = newRawKey();
    const w = await wrapKey(raw, '482916', ITER);
    expect([...(await unwrapKey(w, '482916'))]).toEqual([...raw]);
    await expect(unwrapKey(w, '482917')).rejects.toThrow(VaultError);
  });

  it('a changed byte in the wrapped key is detected', async () => {
    const w = await wrapKey(newRawKey(), 'secret', ITER);
    const ct = atob(w.ct);
    const bad = { ...w, ct: btoa(String.fromCharCode(ct.charCodeAt(0) ^ 1) + ct.slice(1)) };
    await expect(unwrapKey(bad, 'secret')).rejects.toThrow(VaultError);
  });
});

describe('recovery code', () => {
  it('is 32 characters in groups of four, different every time', () => {
    const a = newRecoveryCode(), b = newRecoveryCode();
    expect(a).toMatch(/^[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){7}$/);
    expect(a).not.toBe(b);
  });

  it('is accepted however it is typed back', () => {
    const code = newRecoveryCode();
    expect(normalizeRecoveryCode(code)).toBe(code);
    expect(normalizeRecoveryCode(code.toLowerCase().replace(/-/g, ' '))).toBe(code);
    expect(normalizeRecoveryCode('0000-1111-2222-3333-4444-5555-6666-7777'.replace(/0/g, 'O').replace(/1/g, 'l'))).toBe('0000-1111-2222-3333-4444-5555-6666-7777');
    expect(normalizeRecoveryCode('too short')).toBeNull();
    expect(normalizeRecoveryCode('UUUU-1111-2222-3333-4444-5555-6666-7777')).toBeNull(); // U is not in the alphabet
  });

  it('unwraps the same key as the PIN', async () => {
    const raw = newRawKey(), code = newRecoveryCode();
    const w = await wrapKey(raw, code, ITER);
    expect([...(await unwrapKey(w, normalizeRecoveryCode(code.toLowerCase())!))]).toEqual([...raw]);
  });
});

describe('records', () => {
  it('a page round-trips, and is bound to its own id', async () => {
    const key = await importDataKey(newRawKey());
    const page = { strokes: [{ pts: [1, 2, 0.5] }], texts: [{ text: 'Patient: Asthma, FEV1 62 %' }] };
    const s = await sealJson(key, 'k1', page, 'page:p1');
    expect(isSealed(s)).toBe(true);
    expect(s.k).toBe('k1');
    expect(JSON.stringify(s)).not.toContain('Asthma');
    expect(await openJson(key, s, 'page:p1')).toEqual(page);
    await expect(openJson(key, s, 'page:p2')).rejects.toThrow(VaultError); // moved to another record
    await expect(openJson(await importDataKey(newRawKey()), s, 'page:p1')).rejects.toThrow(VaultError); // other key
  });

  it('a file round-trips, keeps its key id, and is recognised as encrypted', async () => {
    const key = await importDataKey(newRawKey());
    const pdf = new TextEncoder().encode('%PDF-1.7 Lungenfunktion').buffer;
    expect(sealedFileKeyId(pdf)).toBeNull();
    const id = newKeyId();
    const sealed = await sealFile(key, id, pdf, 'file:d1');
    expect(sealedFileKeyId(sealed)).toBe(id);
    expect(new TextDecoder().decode(sealed)).not.toContain('Lungenfunktion');
    expect(new Uint8Array(await openFile(key, sealed, 'file:d1'))).toEqual(new Uint8Array(pdf));
    await expect(openFile(key, sealed, 'file:d2')).rejects.toThrow(VaultError);
  });
});

describe('keys in memory', () => {
  it('needKey throws LockedError for a key not unlocked', () => {
    expect(() => needKey('nope')).toThrow(LockedError);
  });

  it('no key is forgotten while a document is open; the wipe waits until it closes', async () => {
    const a = await importDataKey(newRawKey());
    rememberKey('a', a);
    documentOpened();
    wipeKeys();
    expect(cachedKey('a')).toBe(a); // the open document can still save
    documentClosed();
    expect(cachedKey('a')).toBeUndefined();
    rememberKey('a', a);
    wipeKeys();
    expect(cachedKey('a')).toBeUndefined(); // nothing open: forgotten at once
  });
});
