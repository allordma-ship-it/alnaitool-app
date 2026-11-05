// Lightweight M3U inspection used to validate uploads before they are stored.
// Full parsing happens on the Android client (streaming parser); here we only
// make sure the payload is a plausible playlist and gather a few statistics.

export function stripBom(text) {
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
}

/**
 * @returns {{ valid: boolean, entries: number, groups: number, epgUrl: string|null, reason?: string }}
 */
export function inspectM3u(text) {
  const content = stripBom(String(text ?? ''));
  const firstLine = content.slice(0, content.indexOf('\n') === -1 ? content.length : content.indexOf('\n')).trim();
  if (!firstLine.toUpperCase().startsWith('#EXTM3U')) {
    return { valid: false, entries: 0, groups: 0, epgUrl: null, reason: 'Missing #EXTM3U header' };
  }

  const epgMatch = /(?:url-tvg|x-tvg-url)="([^"]+)"/i.exec(firstLine);
  const epgUrl = epgMatch ? epgMatch[1].split(',')[0].trim() : null;

  let entries = 0;
  const groups = new Set();
  let pendingInfo = false;

  // Manual line scan keeps memory flat for multi-megabyte uploads.
  let start = 0;
  while (start < content.length) {
    let end = content.indexOf('\n', start);
    if (end === -1) end = content.length;
    const line = content.slice(start, end).trim();
    start = end + 1;
    if (!line) continue;
    if (line.startsWith('#EXTINF')) {
      pendingInfo = true;
      const g = /group-title="([^"]*)"/i.exec(line);
      if (g && groups.size < 100_000) groups.add(g[1]);
    } else if (line.startsWith('#EXTGRP:')) {
      groups.add(line.slice(8).trim());
    } else if (!line.startsWith('#') && pendingInfo) {
      entries += 1;
      pendingInfo = false;
    }
  }

  if (entries === 0) {
    return { valid: false, entries, groups: groups.size, epgUrl, reason: 'Playlist contains no playable entries' };
  }
  return { valid: true, entries, groups: groups.size, epgUrl };
}
