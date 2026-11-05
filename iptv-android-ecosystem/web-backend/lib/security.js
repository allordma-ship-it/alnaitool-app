import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  randomBytes,
  randomInt,
  timingSafeEqual,
} from 'node:crypto';

/** 6-digit numeric pairing key, uniformly distributed (000000-999999). */
export function generateDeviceKey() {
  return String(randomInt(0, 1_000_000)).padStart(6, '0');
}

/** High-entropy secret handed to the TV once; only its SHA-256 is stored. */
export function generateDeviceSecret() {
  return randomBytes(32).toString('base64url');
}

export function sha256Hex(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

export function safeEqual(a, b) {
  const ba = Buffer.from(String(a ?? ''), 'utf8');
  const bb = Buffer.from(String(b ?? ''), 'utf8');
  if (ba.length !== bb.length) {
    // Still burn comparable time to avoid leaking length information.
    timingSafeEqual(ba, ba);
    return false;
  }
  return timingSafeEqual(ba, bb);
}

export function verifyDeviceSecret(secret, storedHash) {
  if (!secret || typeof secret !== 'string' || secret.length > 128) return false;
  return safeEqual(sha256Hex(secret), storedHash);
}

// ---------------------------------------------------------------------------
// AES-256-GCM envelope for provider credentials stored at rest.
// Format: v1.<iv b64url>.<tag b64url>.<ciphertext b64url>
// ---------------------------------------------------------------------------

export function encryptJson(key, value) {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  const ct = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return ['v1', iv.toString('base64url'), tag.toString('base64url'), ct.toString('base64url')].join('.');
}

export function decryptJson(key, envelope) {
  const [version, iv, tag, ct] = String(envelope).split('.');
  if (version !== 'v1' || !iv || !tag || !ct) throw new Error('Unsupported credential envelope');
  const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(iv, 'base64url'));
  decipher.setAuthTag(Buffer.from(tag, 'base64url'));
  const pt = Buffer.concat([decipher.update(Buffer.from(ct, 'base64url')), decipher.final()]);
  return JSON.parse(pt.toString('utf8'));
}

// ---------------------------------------------------------------------------
// Stateless portal session tokens.
// token = base64url(payload) "." base64url(HMAC(appSecret, payload + "|" + deviceKey))
// Binding the signature to the current device key means rotating the key
// (e.g. a TV re-pair) immediately invalidates every outstanding portal session.
// ---------------------------------------------------------------------------

export function signPortalToken(appSecret, { mac, deviceKey, ttlMs }) {
  const payload = Buffer.from(JSON.stringify({ mac, exp: Date.now() + ttlMs })).toString('base64url');
  const sig = createHmac('sha256', appSecret).update(`${payload}|${deviceKey}`).digest('base64url');
  return { token: `${payload}.${sig}`, expiresAt: new Date(Date.now() + ttlMs).toISOString() };
}

/** Returns the unverified payload (mac, exp) or null if malformed/expired. */
export function decodePortalToken(token) {
  if (typeof token !== 'string' || token.length > 512) return null;
  const [payload, sig] = token.split('.');
  if (!payload || !sig) return null;
  try {
    const data = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    if (typeof data.mac !== 'string' || typeof data.exp !== 'number' || data.exp < Date.now()) return null;
    return { ...data, payload, sig };
  } catch {
    return null;
  }
}

export function verifyPortalSignature(appSecret, decoded, deviceKey) {
  const expected = createHmac('sha256', appSecret).update(`${decoded.payload}|${deviceKey}`).digest('base64url');
  return safeEqual(expected, decoded.sig);
}
