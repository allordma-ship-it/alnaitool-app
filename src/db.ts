import { openDB, type IDBPDatabase } from 'idb';
import type { DocMeta, PageData, Clip } from './types';

let dbp: Promise<IDBPDatabase> | null = null;

function db() {
  dbp ??= openDB('alnai-notes', 2, {
    upgrade(d, oldVersion) {
      if (oldVersion < 1) {
        d.createObjectStore('docs', { keyPath: 'id' });
        d.createObjectStore('pages', { keyPath: 'id' });
        d.createObjectStore('files');
      }
      if (oldVersion < 2) d.createObjectStore('clips', { keyPath: 'id' }).createIndex('docId', 'docId');
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
export async function listClips(docId?: string): Promise<Clip[]> {
  const d = await db();
  const all = (docId ? await d.getAllFromIndex('clips', 'docId', docId) : await d.getAll('clips')) as Clip[];
  return all.sort((a, b) => b.createdAt - a.createdAt);
}
export async function putClip(c: Clip) {
  await (await db()).put('clips', c);
}
export async function deleteClip(c: Clip) {
  const d = await db();
  const tx = d.transaction(['clips', 'files'], 'readwrite');
  await Promise.all([
    tx.objectStore('clips').delete(c.id),
    ...(c.audioId ? [tx.objectStore('files').delete(c.audioId)] : []),
    tx.done,
  ]);
}
export async function deleteDoc(doc: DocMeta) {
  const clips = await listClips(doc.id);
  const d = await db();
  const tx = d.transaction(['docs', 'pages', 'files', 'clips'], 'readwrite');
  await Promise.all([
    tx.objectStore('docs').delete(doc.id),
    ...doc.pageIds.map((id) => tx.objectStore('pages').delete(id)),
    tx.objectStore('files').delete(doc.id),
    ...(doc.fileIds ?? []).map((id) => tx.objectStore('files').delete(id)),
    ...clips.map((c) => tx.objectStore('clips').delete(c.id)),
    ...clips.filter((c) => c.audioId).map((c) => tx.objectStore('files').delete(c.audioId!)),
    tx.done,
  ]);
}
export async function deleteFile(id: string) {
  await (await db()).delete('files', id);
}
