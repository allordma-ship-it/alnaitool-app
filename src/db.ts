import { openDB, type IDBPDatabase } from 'idb';
import type { DocMeta, PageData, Clip, Folder, TextIndex } from './types';

let dbp: Promise<IDBPDatabase> | null = null;

function db() {
  dbp ??= openDB('alnai-notes', 3, {
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

/** Everything the user made, for a backup. The search index is left out: it is rebuilt from the PDFs. */
export async function dumpAll() {
  const d = await db();
  const tx = d.transaction(['docs', 'pages', 'files', 'clips', 'folders']);
  const files = tx.objectStore('files');
  const [docs, pages, clips, folders, fileKeys, fileVals] = await Promise.all([
    tx.objectStore('docs').getAll(), tx.objectStore('pages').getAll(), tx.objectStore('clips').getAll(),
    tx.objectStore('folders').getAll(), files.getAllKeys(), files.getAll(),
  ]);
  await tx.done;
  return {
    docs: docs as DocMeta[], pages: pages as PageData[], clips: clips as Clip[], folders: folders as Folder[],
    files: fileKeys.map((k, i) => ({ id: String(k), bytes: new Uint8Array(fileVals[i] as ArrayBuffer) })),
  };
}

/** Adds a backup to the library. Items with the same id are replaced; nothing else is touched. */
export async function restoreAll(b: { docs: DocMeta[]; pages: PageData[]; clips: Clip[]; folders: Folder[]; files: { id: string; bytes: Uint8Array }[] }) {
  const d = await db();
  const tx = d.transaction(['docs', 'pages', 'files', 'clips', 'folders'], 'readwrite');
  await Promise.all([
    ...b.folders.map((x) => tx.objectStore('folders').put(x)),
    ...b.pages.map((x) => tx.objectStore('pages').put(x)),
    ...b.clips.map((x) => tx.objectStore('clips').put(x)),
    ...b.files.map((f) => tx.objectStore('files').put(f.bytes.slice().buffer, f.id)),
    ...b.docs.map((x) => tx.objectStore('docs').put(x)),
    tx.done,
  ]);
}
