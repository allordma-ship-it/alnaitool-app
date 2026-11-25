/**
 * Encryption of locked documents. Pure WebCrypto, no app state except the in-memory key cache at the bottom.
 *
 * - A data key (AES-GCM, 256 bit) encrypts the pages, files, clips and text index of locked documents.
 * - On the device it is stored wrapped with a key derived from the PIN (PBKDF2-SHA256).
 * - For backups it is stored wrapped with a key derived from the recovery code only: a backup file can leave the
 *   device, and a 4–8 digit PIN could be guessed offline; the recovery code (160 random bits) cannot.
 * - Every encrypted record carries the id of the key and is bound to its own record id (AES-GCM additional data),
 *   so encrypted records cannot be swapped between documents.
 */

const te = new TextEncoder();
const td = new TextDecoder();

export class VaultError extends Error {}

/** A data key wrapped with a key derived from a secret (PIN or recovery code). */
export interface Wrap {
  salt: string;
  iter: number;
  iv: string;
  ct: string;
}

/** One data key as stored on the device: wrapped with the PIN, and with the recovery code (for backups). */
export interface KeyEntry {
  id: string;
  pin: Wrap;
  rec: Wrap;
}

/** An encrypted JSON record. */
export interface Sealed {
  k: string;
  iv: string;
  ct: string;
}

export const PIN_ITER = 310_000;
export const REC_ITER = 100_000;

// ---------------------------------------------------------------- encoding

export function b64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
export function unb64(s: string): Uint8Array<ArrayBuffer> {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
const random = (n: number) => crypto.getRandomValues(new Uint8Array(n));

// ---------------------------------------------------------------- keys

export function newKeyId(): string {
  return [...random(8)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** A fresh data key, raw (only to wrap it; use importDataKey for encrypting). */
export function newRawKey(): Uint8Array<ArrayBuffer> {
  return random(32);
}

export function importDataKey(raw: Uint8Array<ArrayBuffer>): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

async function kek(secret: string, salt: Uint8Array<ArrayBuffer>, iter: number): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey('raw', te.encode(secret), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

export async function wrapKey(raw: Uint8Array<ArrayBuffer>, secret: string, iter: number): Promise<Wrap> {
  const salt = random(16), iv = random(12);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await kek(secret, salt, iter), raw));
  return { salt: b64(salt), iter, iv: b64(iv), ct: b64(ct) };
}

/** The raw data key, or VaultError when the secret is wrong. */
export async function unwrapKey(w: Wrap, secret: string): Promise<Uint8Array<ArrayBuffer>> {
  try {
    const k = await kek(secret, unb64(w.salt), w.iter);
    return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(w.iv) }, k, unb64(w.ct)));
  } catch {
    throw new VaultError('wrong secret');
  }
}

export function isWrap(x: unknown): x is Wrap {
  const w = x as Wrap;
  return !!w && typeof w.salt === 'string' && typeof w.iv === 'string' && typeof w.ct === 'string' && Number.isSafeInteger(w.iter) && w.iter > 0;
}

// ---------------------------------------------------------------- recovery code

/** Crockford base32: no I, L, O, U, so the code is easy to read back and type. */
const B32 = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** 160 random bits as 32 characters in groups of four: XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX. */
export function newRecoveryCode(): string {
  const bytes = random(20);
  let bits = 0, value = 0, out = '';
  for (const b of bytes) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5; }
  }
  return out.match(/.{4}/g)!.join('-');
}

/** Accepts the code typed in any case, with or without dashes or spaces, O for 0 and I/L for 1; null if it cannot be one. */
export function normalizeRecoveryCode(input: string): string | null {
  const s = input.toUpperCase().replace(/[\s\-–_]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
  if (s.length !== 32 || [...s].some((c) => !B32.includes(c))) return null;
  return s.match(/.{4}/g)!.join('-');
}

// ---------------------------------------------------------------- records

export async function sealJson(key: CryptoKey, keyId: string, value: unknown, aad: string): Promise<Sealed> {
  const iv = random(12);
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: te.encode(aad) }, key, te.encode(JSON.stringify(value)));
  return { k: keyId, iv: b64(iv), ct: b64(new Uint8Array(ct)) };
}

export async function openJson<T>(key: CryptoKey, s: Sealed, aad: string): Promise<T> {
  try {
    const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(s.iv), additionalData: te.encode(aad) }, key, unb64(s.ct));
    return JSON.parse(td.decode(pt)) as T;
  } catch {
    throw new VaultError('cannot decrypt record');
  }
}

export function isSealed(x: unknown): x is Sealed {
  const s = x as Sealed;
  return !!s && typeof s.k === 'string' && typeof s.iv === 'string' && typeof s.ct === 'string';
}

/**
 * Stored files stay ArrayBuffers (so backups copy them unchanged): an encrypted one starts with a marker no PDF,
 * picture or recording starts with.
 *
 *   "NVSEAL1\0" | key id length (1 byte) | key id (ASCII) | iv (12 bytes) | ciphertext
 */
const MARK = te.encode('NVSEAL1\0');

export function sealedFileKeyId(buf: ArrayBuffer): string | null {
  const b = new Uint8Array(buf);
  if (b.byteLength < MARK.length + 1 + 12 || MARK.some((m, i) => b[i] !== m)) return null;
  const n = b[MARK.length];
  return td.decode(b.subarray(MARK.length + 1, MARK.length + 1 + n));
}

export async function sealFile(key: CryptoKey, keyId: string, data: ArrayBuffer, aad: string): Promise<ArrayBuffer> {
  const iv = random(12), kid = te.encode(keyId);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: te.encode(aad) }, key, data));
  const out = new Uint8Array(MARK.length + 1 + kid.length + 12 + ct.length);
  out.set(MARK, 0);
  out[MARK.length] = kid.length;
  out.set(kid, MARK.length + 1);
  out.set(iv, MARK.length + 1 + kid.length);
  out.set(ct, MARK.length + 1 + kid.length + 12);
  return out.buffer;
}

export async function openFile(key: CryptoKey, buf: ArrayBuffer, aad: string): Promise<ArrayBuffer> {
  const b = new Uint8Array(buf);
  const start = MARK.length + 1 + b[MARK.length];
  try {
    return await crypto.subtle.decrypt({ name: 'AES-GCM', iv: b.slice(start, start + 12), additionalData: te.encode(aad) }, key, b.subarray(start + 12));
  } catch {
    throw new VaultError('cannot decrypt file');
  }
}

// ---------------------------------------------------------------- keys in memory

/** Thrown when a locked document is read or written without its key in memory. */
export class LockedError extends Error {
  constructor(public keyId: string) { super('locked'); }
}

const cache = new Map<string, CryptoKey>();

export function cachedKey(id: string): CryptoKey | undefined {
  return cache.get(id);
}
export function rememberKey(id: string, key: CryptoKey) {
  cache.set(id, key);
}
/** The key for `id`, or LockedError. */
export function needKey(id: string): CryptoKey {
  const k = cache.get(id);
  if (!k) throw new LockedError(id);
  return k;
}

/**
 * While a document is open its last strokes must still be saveable after the app is hidden, so no key is forgotten
 * while any document is open; the wipe waits until the last one closes.
 */
let open = 0;
let wipePending = false;
export function documentOpened() {
  open++;
}
export function documentClosed() {
  open = Math.max(0, open - 1);
  if (open === 0 && wipePending) wipeKeys();
}
/** Forgets every key (the app went to the background, or the lock was turned off) – later, if a document is open. */
export function wipeKeys() {
  if (open > 0) { wipePending = true; return; }
  cache.clear();
  wipePending = false;
}
/** The PIN was entered again: keys in memory are current, a pending wipe is cancelled. */
export function keysFresh() {
  wipePending = false;
}
