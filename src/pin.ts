/** PIN storage: only a salted PBKDF2 hash is kept, never the PIN itself. Pure functions, easy to test. */

export interface PinRecord {
  salt: string;
  hash: string;
  iter: number;
}

const hex = (b: ArrayBuffer | Uint8Array) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
const unhex = (s: string) => new Uint8Array((s.match(/../g) ?? []).map((x) => parseInt(x, 16)));

async function derive(pin: string, salt: Uint8Array, iter: number): Promise<string> {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(pin), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations: iter }, key, 256);
  return hex(bits);
}

export async function makePin(pin: string, iter = 150_000): Promise<PinRecord> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return { salt: hex(salt), hash: await derive(pin, salt, iter), iter };
}

export async function checkPin(pin: string, rec: PinRecord): Promise<boolean> {
  const h = await derive(pin, unhex(rec.salt), rec.iter);
  // compare without stopping at the first different character
  let diff = h.length ^ rec.hash.length;
  for (let i = 0; i < Math.min(h.length, rec.hash.length); i++) diff |= h.charCodeAt(i) ^ rec.hash.charCodeAt(i);
  return diff === 0;
}

/** 4 to 8 digits (Arabic-Indic digits typed on an Arabic keyboard count too). */
export function normalizePin(input: string): string | null {
  const ascii = input.replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x660)).replace(/[۰-۹]/g, (d) => String(d.charCodeAt(0) - 0x6f0)).trim();
  return /^\d{4,8}$/.test(ascii) ? ascii : null;
}

/** Seconds to wait after `fails` wrong PINs in a row: none for the first 4, then 30 s, doubling, at most 10 min. */
export function lockoutSeconds(fails: number): number {
  if (fails < 5) return 0;
  return Math.min(600, 30 * 2 ** (fails - 5));
}
