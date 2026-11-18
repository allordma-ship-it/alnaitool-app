import { getTextIndex, putTextIndex, getPages, listClips } from './db';
import { pdfForDoc, pdfTextLines, joinLines, closePdf } from './pdf';
import { normQuery, findSnippets, type Snippet } from './searchText';
import type { DocMeta, TextIndex } from './types';

export interface Hit {
  doc: DocMeta;
  /** page index in the document */
  page: number;
  kind: 'text' | 'clip';
  count: number;
  snippet: Snippet;
}

const building = new Map<string, Promise<TextIndex | null>>();

async function pageMap(doc: DocMeta): Promise<number[]> {
  const pages = await getPages(doc.pageIds);
  return pages.map((p) => (p?.bg.kind === 'pdf' ? p.bg.pdfPage : 0));
}

/** Extracts the text of every page of a PDF once and keeps it. Pictures and notebooks have none. */
export function indexDoc(doc: DocMeta): Promise<TextIndex | null> {
  if (doc.kind !== 'pdf') return Promise.resolve(null);
  let p = building.get(doc.id);
  if (!p) {
    p = (async () => {
      const key = doc.pageIds.join(',');
      const old = await getTextIndex(doc.id);
      if (old) {
        if (old.key === key) return old;
        const fresh = { ...old, key, pageMap: await pageMap(doc) };
        await putTextIndex(fresh);
        return fresh;
      }
      const pdf = await pdfForDoc(doc.id);
      const byPdfPage: string[] = [];
      try {
        for (let i = 1; i <= pdf.numPages; i++) {
          try {
            byPdfPage.push(joinLines(await pdfTextLines(doc.id, i)));
          } catch {
            byPdfPage.push('');
          }
        }
      } finally {
        closePdf(doc.id);
      }
      const ix: TextIndex = { docId: doc.id, byPdfPage, pageMap: await pageMap(doc), key };
      await putTextIndex(ix);
      return ix;
    })().finally(() => building.delete(doc.id));
    building.set(doc.id, p);
  }
  return p;
}

/** Documents whose text has not been extracted yet. */
export async function unindexed(docs: DocMeta[]): Promise<DocMeta[]> {
  const out: DocMeta[] = [];
  for (const d of docs) if (d.kind === 'pdf' && !(await getTextIndex(d.id))) out.push(d);
  return out;
}

/** Searches the text inside PDFs and the clips (text + own notes). One hit per page, best pages first. */
export async function searchContent(query: string, docs: DocMeta[], limit = 60): Promise<Hit[]> {
  const q = normQuery(query);
  if (q.length < 2) return [];
  const byId = new Map(docs.map((d) => [d.id, d]));
  const hits: Hit[] = [];
  for (const d of docs) {
    if (d.kind !== 'pdf') continue;
    let ix = await getTextIndex(d.id);
    if (!ix) continue;
    if (ix.key !== d.pageIds.join(',')) ix = (await indexDoc(d)) ?? ix;
    ix.pageMap.forEach((pdfPage, page) => {
      if (!pdfPage) return;
      const { count, snippets } = findSnippets(ix!.byPdfPage[pdfPage - 1] ?? '', q, 1);
      if (count) hits.push({ doc: d, page, kind: 'text', count, snippet: snippets[0] });
    });
  }
  for (const c of await listClips()) {
    const d = byId.get(c.docId);
    if (!d) continue;
    const { count, snippets } = findSnippets([c.note, c.text].filter(Boolean).join(' — '), q, 1);
    if (count) hits.push({ doc: d, page: c.page, kind: 'clip', count, snippet: snippets[0] });
  }
  // clips first (the user's own words), then pages with the most hits
  hits.sort((a, b) => (a.kind === b.kind ? b.count - a.count : a.kind === 'clip' ? -1 : 1));
  return hits.slice(0, limit);
}
