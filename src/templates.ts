import { putFile, putTemplate } from './db';
import { prepareImage } from './pageImages';
import { loadPdf, renderPdfPage, destroyPdf } from './pdf';
import { renderPageToCanvas } from './render';
import { A4, uid, type CustomTemplate, type DocMeta, type PageData, type PageTemplateRef } from './types';

/** Template files are never deleted with pages or documents: other notebooks may use them. */
export const isTemplateFile = (fileId: string) => fileId.startsWith('tpl_');

export const templateRef = (tp: CustomTemplate): PageTemplateRef => ({ fileId: tp.fileId, mime: tp.mime, w: tp.w, h: tp.h, name: tp.name });

/** A new page with the template as its paper. */
export function pageFromTemplate(ref: PageTemplateRef): PageData {
  return { id: uid(), w: ref.w, h: ref.h, bg: { kind: 'image', fileId: ref.fileId, mime: ref.mime }, strokes: [] };
}

async function save(name: string, blob: Blob): Promise<CustomTemplate> {
  const prep = await prepareImage(blob);
  const id = `tpl_${uid()}`;
  await putFile(id, prep.bytes.slice().buffer);
  // A4 width, height from the picture's shape: pages from a template line up with other A4 pages
  const tp: CustomTemplate = { id, name, fileId: id, mime: prep.mime, w: A4.w, h: (A4.w * prep.h) / prep.w, createdAt: Date.now() };
  await putTemplate(tp);
  return tp;
}

const canvasBlob = (c: HTMLCanvasElement) =>
  new Promise<Blob>((res, rej) => c.toBlob((b) => (b ? res(b) : rej(new Error('encode failed'))), 'image/jpeg', 0.9));

/** From a picture, or the first page of a PDF (a planner, a lab sheet…). */
export async function templateFromFile(file: File): Promise<CustomTemplate> {
  const name = file.name.replace(/\.[^.]+$/, '');
  if (file.type === 'application/pdf' || /\.pdf$/i.test(file.name)) {
    const pdf = await loadPdf(await file.arrayBuffer());
    try {
      const c = document.createElement('canvas');
      const vp = (await pdf.getPage(1)).getViewport({ scale: 1 });
      await renderPdfPage(pdf, 1, c, 1600 / vp.width);
      const blob = await canvasBlob(c);
      c.width = 0;
      return save(name, blob);
    } finally {
      destroyPdf(pdf);
    }
  }
  return save(name, file);
}

/** A page as it looks now (background, pictures, ink and text) becomes a template. */
export async function templateFromPage(meta: DocMeta, page: PageData, name: string): Promise<CustomTemplate> {
  const c = await renderPageToCanvas(meta, page, 1600 / page.w);
  const blob = await canvasBlob(c);
  c.width = 0;
  return save(name, blob);
}
