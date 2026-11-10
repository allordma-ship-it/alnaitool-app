import { openDB, type IDBPDatabase } from 'idb';
import type { DocMeta, PageData } from './types';

let dbp: Promise<IDBPDatabase> | null = null;

function db() {
  dbp ??= openDB('alnai-notes', 1, {
    upgrade(d) {
      d.createObjectStore('docs', { keyPath: 'id' });
      d.createObjectStore('pages', { keyPath: 'id' });
      d.createObjectStore('files');
    },
  });
  return dbp;
}

export async function listDocs(): Promise<DocMeta[]> {
  const all = (await (await db()).getAll('docs')) as DocMeta[];
  return all.sort((a, b) => b.updatedAt - a.updatedAt);
}
export async function getDoc(id: string) {
  return (await (await db()).get('docs', id)) as DocMeta | undefined;
}
export async function putDoc(doc: DocMeta) {
  await (await db()).put('docs', doc);
}
export async function getPages(ids: string[]): Promise<PageData[]> {
  const d = await db();
  const tx = d.transaction('pages');
  const res = await Promise.all(ids.map((id) => tx.store.get(id)));
  await tx.done;
  return res as PageData[];
}
export async function putPages(pages: PageData[]) {
  const d = await db();
  const tx = d.transaction('pages', 'readwrite');
  await Promise.all([...pages.map((p) => tx.store.put(p)), tx.done]);
}
export async function deletePages(ids: string[]) {
  const d = await db();
  const tx = d.transaction('pages', 'readwrite');
  await Promise.all([...ids.map((id) => tx.store.delete(id)), tx.done]);
}
export async function putFile(id: string, data: ArrayBuffer) {
  await (await db()).put('files', data, id);
}
export async function getFile(id: string) {
  return (await (await db()).get('files', id)) as ArrayBuffer | undefined;
}
export async function deleteDoc(doc: DocMeta) {
  const d = await db();
  const tx = d.transaction(['docs', 'pages', 'files'], 'readwrite');
  await Promise.all([
    tx.objectStore('docs').delete(doc.id),
    ...doc.pageIds.map((id) => tx.objectStore('pages').delete(id)),
    tx.objectStore('files').delete(doc.id),
    tx.done,
  ]);
}
