import { openDB, type IDBPDatabase } from 'idb';
import type { DocMeta, PageData, Clip, Folder, TextIndex, CustomTemplate } from './types';

let dbp: Promise<IDBPDatabase> | null = null;

function db() {
  dbp ??= openDB('alnai-notes', 4, {
    upgrade(d, oldVersion) {
      if (oldVersion < 1) {
        d.createObjectStore('docs', { keyPath: 'id' });
        d.createObjectStore('pages', { keyPath: 'id' });
        d.createObjectStore('files');
      }
      if (oldVersion < 2) d.createObjectStore('clips', { keyPath: 'id' }).createIndex('docId', 'docId');
      if (oldVersion < 3) {
        d.createObjectStore('folders', { keyPath: 'id' });
        d.createObjectStore('text', { keyPath: 'docId' });
      }
      if (oldVersion < 4) d.createObjectStore('templates', { keyPath: 'id' });
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
  const tx = d.transaction(['docs', 'pages', 'files', 'clips', 'text'], 'readwrite');
  await Promise.all([
    tx.objectStore('docs').delete(doc.id),
    tx.objectStore('text').delete(doc.id),
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

export async function listFolders(): Promise<Folder[]> {
  const all = (await (await db()).getAll('folders')) as Folder[];
  return all.sort((a, b) => a.name.localeCompare(b.name));
}
export async function putFolder(f: Folder) {
  await (await db()).put('folders', f);
}
/** Deletes a folder; its documents move to the top level, nothing else is deleted. */
export async function deleteFolder(id: string) {
  const d = await db();
  const tx = d.transaction(['folders', 'docs'], 'readwrite');
  const docs = (await tx.objectStore('docs').getAll()) as DocMeta[];
  await Promise.all([
    tx.objectStore('folders').delete(id),
    ...docs.filter((x) => x.folderId === id).map((x) => { delete x.folderId; return tx.objectStore('docs').put(x); }),
    tx.done,
  ]);
}

export async function getTextIndex(docId: string) {
  return (await (await db()).get('text', docId)) as TextIndex | undefined;
}
export async function putTextIndex(ix: TextIndex) {
  await (await db()).put('text', ix);
}

/**
 * Everything the user made except the bytes of stored files (PDFs, pictures, voice memos), for a backup.
 * Those can be hundreds of MB, so the backup reads them one at a time with getFile(fileIds[i]).
 * The search index is left out: it is rebuilt from the PDFs.
 */
export async function dumpMeta() {
  const d = await db();
  const tx = d.transaction(['docs', 'pages', 'files', 'clips', 'folders', 'templates']);
  const [docs, pages, clips, folders, templates, fileKeys] = await Promise.all([
    tx.objectStore('docs').getAll(), tx.objectStore('pages').getAll(), tx.objectStore('clips').getAll(),
    tx.objectStore('folders').getAll(), tx.objectStore('templates').getAll(), tx.objectStore('files').getAllKeys(),
  ]);
  await tx.done;
  return {
    docs: docs as DocMeta[], pages: pages as PageData[], clips: clips as Clip[], folders: folders as Folder[],
    templates: templates as CustomTemplate[], fileIds: fileKeys.map(String),
  };
}

/** Documents, clips, folders and templates only: enough to tell whether the library changed. */
export async function libraryOutline() {
  const d = await db();
  const tx = d.transaction(['docs', 'clips', 'folders', 'templates']);
  const [docs, clips, folders, templates] = await Promise.all([
    tx.objectStore('docs').getAll(), tx.objectStore('clips').getAll(), tx.objectStore('folders').getAll(), tx.objectStore('templates').getAll(),
  ]);
  await tx.done;
  return { docs: docs as DocMeta[], clips: clips as Clip[], folders: folders as Folder[], templates: templates as CustomTemplate[] };
}

/**
 * Adds the non-file part of a backup to the library (its files were stored first with putFile, so no document
 * ever points at a missing file). Items with the same id are replaced; nothing else is touched.
 */
export async function restoreMeta(b: { docs: DocMeta[]; pages: PageData[]; clips: Clip[]; folders: Folder[]; templates?: CustomTemplate[] }) {
  const d = await db();
  const tx = d.transaction(['docs', 'pages', 'clips', 'folders', 'templates'], 'readwrite');
  await Promise.all([
    ...b.folders.map((x) => tx.objectStore('folders').put(x)),
    ...(b.templates ?? []).map((x) => tx.objectStore('templates').put(x)),
    ...b.pages.map((x) => tx.objectStore('pages').put(x)),
    ...b.clips.map((x) => tx.objectStore('clips').put(x)),
    ...b.docs.map((x) => tx.objectStore('docs').put(x)),
    tx.done,
  ]);
}

export async function listTemplates(): Promise<CustomTemplate[]> {
  const all = (await (await db()).getAll('templates')) as CustomTemplate[];
  return all.sort((a, b) => b.createdAt - a.createdAt);
}
export async function putTemplate(tp: CustomTemplate) {
  await (await db()).put('templates', tp);
}
/** Forgets a template. Its picture stays: pages made from it still show it. */
export async function deleteTemplate(id: string) {
  await (await db()).delete('templates', id);
}
