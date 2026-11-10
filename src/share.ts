import { Capacitor } from '@capacitor/core';
import type { DocMeta, PageData } from './types';
import { getFile } from './db';
import { exportDocToPdf } from './exportPdf';
import { renderPageToCanvas, canvasToBytes } from './render';
import { icons } from './icons';
import { t, type Key } from './i18n';
import { h, modal, toast } from './ui';

export interface OutFile { name: string; bytes: Uint8Array; mime: string }

function toBase64(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

/** Hands files to the Android share sheet (save to Files, Drive, WhatsApp, mail…) or downloads them on the web. */
export async function shareFiles(files: OutFile[], title: string) {
  if (Capacitor.isNativePlatform()) {
    const { Filesystem, Directory } = await import('@capacitor/filesystem');
    const { Share } = await import('@capacitor/share');
    const uris: string[] = [];
    for (const f of files) {
      const { uri } = await Filesystem.writeFile({ path: f.name, data: toBase64(f.bytes), directory: Directory.Cache });
      uris.push(uri);
    }
    try {
      await Share.share({ title, files: uris, dialogTitle: title });
    } catch (e) {
      if (!/cancel/i.test(String(e))) throw e;
    }
    return;
  }
  const asFiles = files.map((f) => new File([f.bytes as BlobPart], f.name, { type: f.mime }));
  const nav = navigator as Navigator & { canShare?: (d: ShareData) => boolean };
  if (nav.canShare?.({ files: asFiles })) {
    try {
      await nav.share({ files: asFiles, title });
      return;
    } catch (e) {
      if ((e as DOMException).name === 'AbortError') return;
    }
  }
  for (const f of asFiles) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(f);
    a.download = f.name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
    await new Promise((r) => setTimeout(r, 150));
  }
}

const safeName = (s: string) => s.replace(/[\\/:*?"<>|]+/g, '_').trim() || 'notes';

type Format = 'pdf' | 'images' | 'image' | 'text' | 'original';

export async function convertDoc(meta: DocMeta, pages: PageData[], format: Format, current = 0): Promise<OutFile[]> {
  const base = safeName(meta.title);
  if (format === 'pdf') return [{ name: `${base}.pdf`, bytes: await exportDocToPdf(meta, pages), mime: 'application/pdf' }];
  if (format === 'original') {
    const buf = await getFile(meta.id);
    if (!buf) throw new Error(t('fileError'));
    return [{ name: `${base}.pdf`, bytes: new Uint8Array(buf), mime: 'application/pdf' }];
  }
  if (format === 'text') {
    const text = pages
      .map((p) => [...(p.texts ?? [])].sort((a, b) => a.y - b.y).map((tb) => tb.text).join('\n\n'))
      .filter(Boolean)
      .join('\n\n');
    if (!text) throw new Error(t('noText'));
    return [{ name: `${base}.txt`, bytes: new TextEncoder().encode(text), mime: 'text/plain' }];
  }
  const which = format === 'image' ? [current] : pages.map((_, i) => i);
  const out: OutFile[] = [];
  for (const i of which) {
    if (which.length > 1) toast(`${t('preparing')} ${out.length + 1} / ${which.length}`);
    const canvas = await renderPageToCanvas(meta, pages[i], 2);
    out.push({ name: `${base}-${String(i + 1).padStart(2, '0')}.png`, bytes: await canvasToBytes(canvas), mime: 'image/png' });
    canvas.width = 0; // free memory early on large documents
  }
  return out;
}

export function openShareDialog(meta: DocMeta, pages: PageData[], current: number) {
  const item = (icon: string, label: Key, value: Format) => {
    const b = h('button', { value, class: 'menu-item' });
    b.innerHTML = icon;
    b.append(h('span', {}, t(label)));
    return b;
  };
  const form = h('form', { method: 'dialog', class: 'menu' },
    h('h2', {}, t('shareTitle')),
    item(icons.file, 'asPdf', 'pdf'),
    item(icons.image, 'asImage', 'image'),
    item(icons.pages, 'asImages', 'images'),
    item(icons.text, 'asText', 'text'),
  );
  if (meta.kind === 'pdf') form.append(item(icons.file, 'original', 'original'));
  form.append(h('button', { value: 'cancel' }, t('cancel')));
  void modal(form).closed.then(async (v) => {
    if (!v || v === 'cancel') return;
    toast(t('preparing'));
    try {
      await shareFiles(await convertDoc(meta, pages, v as Format, current), meta.title);
    } catch (err) {
      console.error(err);
      toast(err instanceof Error ? err.message : String(err));
    }
  });
}
