import { getFile } from './db';

/** Longest side of a stored picture: sharp on a 12.7" tablet, small enough for hundreds of them. */
const MAX_SIDE = 2000;

export interface PreparedImage {
  bytes: Uint8Array;
  mime: string;
  /** pixel size after scaling down */
  w: number;
  h: number;
}

/** Scales a photo down and stores it as JPEG (PNG stays PNG when it has transparency, e.g. a cut-out clip). */
export async function prepareImage(src: Blob): Promise<PreparedImage> {
  const bmp = await createImageBitmap(src);
  const k = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
  const w = Math.max(1, Math.round(bmp.width * k)), h = Math.max(1, Math.round(bmp.height * k));
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const ctx = c.getContext('2d')!;
  const png = src.type === 'image/png';
  if (!png) {
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, w, h);
  }
  ctx.drawImage(bmp, 0, 0, w, h);
  bmp.close();
  const mime = png ? 'image/png' : 'image/jpeg';
  const blob = await new Promise<Blob>((res, rej) => c.toBlob((b) => (b ? res(b) : rej(new Error('encode failed'))), mime, 0.88));
  c.width = 0;
  return { bytes: new Uint8Array(await blob.arrayBuffer()), mime, w, h };
}

/** A data: URL (from a clip) as a Blob. */
export async function dataUrlToBlob(url: string): Promise<Blob> {
  return (await fetch(url)).blob();
}

const urls = new Map<string, Promise<string>>();

/** Object URL for showing a stored picture in an <img>; kept for the session. */
export function imageUrl(fileId: string, mime: string): Promise<string> {
  let p = urls.get(fileId);
  if (!p) {
    p = getFile(fileId).then((buf) => {
      if (!buf) throw new Error('missing image');
      return URL.createObjectURL(new Blob([buf], { type: mime }));
    });
    urls.set(fileId, p);
    p.catch(() => urls.delete(fileId));
  }
  return p;
}

/** Where a picture of `w`×`h` pixels goes on a page: at most 60 % of the page width, centred on (cx, cy), inside the page. */
export function placeImage(w: number, h: number, page: { w: number; h: number }, cx: number, cy: number) {
  const iw = Math.min(page.w * 0.6, w / 2, page.w - 40);
  const ih = (iw * h) / w;
  const fitH = Math.min(ih, page.h - 40);
  const sw = (fitH / ih) * iw;
  const x = Math.max(20, Math.min(page.w - sw - 20, cx - sw / 2));
  const y = Math.max(20, Math.min(page.h - fitH - 20, cy - fitH / 2));
  return { x, y, w: sw, h: fitH };
}
