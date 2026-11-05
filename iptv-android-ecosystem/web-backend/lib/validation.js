// Input normalisation & sanitisation helpers shared by all routes.

const CONTROL_CHARS = /[\u0000-\u001F\u007F-\u009F\u200B-\u200F\u2028-\u202E\u2066-\u2069]/g;

/**
 * Accepts AA:BB:CC:DD:EE:FF, aa-bb-cc-dd-ee-ff, aabb.ccdd.eeff or aabbccddeeff.
 * Returns the canonical upper-case colon form or null when invalid.
 */
export function normalizeMac(input) {
  if (typeof input !== 'string') return null;
  const hex = input.trim().replace(/[:\-.\s]/g, '').toUpperCase();
  if (!/^[0-9A-F]{12}$/.test(hex)) return null;
  if (hex === '000000000000' || hex === 'FFFFFFFFFFFF') return null;
  return hex.match(/.{2}/g).join(':');
}

export function isDeviceKey(value) {
  return typeof value === 'string' && /^\d{6}$/.test(value);
}

/** Strips control/bidi-override characters and markup-sensitive brackets. */
export function sanitizeText(value, maxLength = 80) {
  if (typeof value !== 'string') return '';
  return value.replace(CONTROL_CHARS, '').replace(/[<>]/g, '').trim().slice(0, maxLength);
}

/** Credentials are opaque, but must not contain control chars and are length-bounded. */
export function sanitizeCredential(value, maxLength = 128) {
  if (typeof value !== 'string') return null;
  const v = value.replace(CONTROL_CHARS, '').trim();
  if (!v || v.length > maxLength) return null;
  return v;
}

/** Only absolute http(s) URLs are accepted. Returns the normalised href or null. */
export function normalizeHttpUrl(value, { maxLength = 2048 } = {}) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > maxLength || CONTROL_CHARS.test(trimmed)) {
    CONTROL_CHARS.lastIndex = 0;
    return null;
  }
  CONTROL_CHARS.lastIndex = 0;
  let url;
  try {
    url = new URL(trimmed);
  } catch {
    return null;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
  if (!url.hostname) return null;
  return url.href;
}

/**
 * Normalises an Xtream Codes "DNS"/host: adds a scheme when missing and strips
 * well-known endpoint suffixes (player_api.php, get.php, ...) and trailing slashes.
 */
export function normalizeXtreamHost(value) {
  if (typeof value !== 'string') return null;
  let v = value.trim();
  if (!v) return null;
  if (!/^https?:\/\//i.test(v)) v = `http://${v}`;
  const href = normalizeHttpUrl(v, { maxLength: 512 });
  if (!href) return null;
  const url = new URL(href);
  url.search = '';
  url.hash = '';
  url.pathname = url.pathname.replace(/\/(player_api|get|xmltv|panel_api)\.php$/i, '').replace(/\/+$/, '');
  return url.href.replace(/\/+$/, '');
}
