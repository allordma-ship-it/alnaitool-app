import { openDB, type IDBPDatabase } from 'idb';
import type { DocMeta, PageData, Clip, Folder, TextIndex, CustomTemplate } from './types';
import { needKey, cachedKey, sealJson, openJson, isSealed, sealFile, openFile, sealedFileKeyId, type Sealed } from './vault';

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
/**
 * Saves a document's details. A locked (sealed) document keeps no thumbnail, and pages or files added to it since
 * the last save are encrypted now (they were written a moment before the document listed them).
 */
export async function putDoc(doc: DocMeta) {
  if (doc.sealed) delete doc.thumb;
  await (await db()).put('docs', doc);
  if (owners) noteOwner(await owners, doc);
  if (doc.sealed && cachedKey(doc.sealed.k)) await sweep(doc);
  if (docHook) void docHook(doc).catch((e) => console.error('lock', e));
}
let docHook: ((doc: DocMeta) => Promise<void>) | null = null;
/** lock.ts: told after every saved document, to encrypt or decrypt it when its lock changed. */
export function onDocSaved(fn: (doc: DocMeta) => Promise<void>) {
  docHook = fn;
}
export async function getPages(ids: string[]): Promise<PageData[]> {
  const d = await db();
  const tx = d.transaction('pages');
  const res = await Promise.all(ids.map((id) => tx.store.get(id)));
  await tx.done;
  return Promise.all((res as StoredPage[]).map((p) => (p ? openPage(p) : p))) as Promise<PageData[]>;
}
export async function putPages(pages: PageData[]) {
  const stored = await Promise.all(pages.map(async (p) => {
    const k = await keyOfPage(p.id);
    return k ? sealPage(p, k) : p;
  }));
  const d = await db();
  const tx = d.transaction('pages', 'readwrite');
  await Promise.all([...stored.map((p) => tx.store.put(p)), tx.done]);
}
export async function deletePages(ids: string[]) {
  const d = await db();
  const tx = d.transaction('pages', 'readwrite');
  await Promise.all([...ids.map((id) => tx.store.delete(id)), tx.done]);
}
export async function putFile(id: string, data: ArrayBuffer) {
  const k = await keyOfFile(id);
  if (k) known.add(id);
  await (await db()).put('files', k ? await sealFile(needKey(k), k, data, `file:${id}`) : data, id);
}
export async function getFile(id: string) {
  const raw = (await (await db()).get('files', id)) as ArrayBuffer | undefined;
  const k = raw && sealedFileKeyId(raw);
  if (!raw || !k) return raw;
  known.add(id);
  return openFile(needKey(k), raw, `file:${id}`);
}
/** Stores bytes exactly as given (a restore: files of locked documents arrive already encrypted). */
export async function putFileRaw(id: string, data: ArrayBuffer) {
  await (await db()).put('files', data, id);
}
/** The stored bytes as they are – encrypted for a locked document (backups copy them like that). */
export async function getFileRaw(id: string) {
  return (await (await db()).get('files', id)) as ArrayBuffer | undefined;
}
/**
 * Clips of a locked document whose key is not unlocked come back without picture, text and note, marked `sealed`;
 * they can be counted and deleted, not saved.
 */
export async function listClips(docId?: string): Promise<Clip[]> {
  const d = await db();
  const all = (docId ? await d.getAllFromIndex('clips', 'docId', docId) : await d.getAll('clips')) as Clip[];
  const open = await Promise.all(all.map(openClip));
  return open.sort((a, b) => b.createdAt - a.createdAt);
}
export async function putClip(c: Clip) {
  if (c.sealed) throw new Error('a clip of a locked document cannot be saved without its key');
  const k = (await ownersIndex()).key.get(c.docId);
  await (await db()).put('clips', k ? await sealClip(c, k) : c);
  if (k && c.audioId) await sealFileIfPlain(c.audioId, k);
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
  const raw = (await (await db()).get('text', docId)) as (TextIndex & { sealed?: Sealed }) | undefined;
  if (!raw || !isSealed(raw.sealed)) return raw;
  return { docId, ...(await openJson<Omit<TextIndex, 'docId'>>(needKey(raw.sealed.k), raw.sealed, `text:${docId}`)) } as TextIndex;
}
export async function putTextIndex(ix: TextIndex) {
  const k = (await ownersIndex()).key.get(ix.docId);
  await (await db()).put('text', k ? await sealText(ix, k) : ix);
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
  owners = null;
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

// ---------------------------------------------------------------- encryption of locked documents (see vault.ts)
//
// A document with `sealed: { k }` has its pages, its own files (the PDF, pictures, voice memos), its clips' picture,
// text and note, and its text index stored encrypted with key k. Reads decrypt with the key in memory, or throw
// LockedError; writes to a sealed document encrypt. sealDoc/unsealDoc switch a whole document; both can be
// interrupted and run again (every record says itself whether it is encrypted).

type StoredPage = PageData | { id: string; sealed: Sealed };

/** which document each page and file belongs to, and the key of every sealed document */
interface Owners { page: Map<string, string>; file: Map<string, string>; key: Map<string, string> }
let owners: Promise<Owners> | null = null;
/** ids of records known to be stored encrypted (so saving a sealed document does not re-read them) */
const known = new Set<string>();

function ownersIndex(): Promise<Owners> {
  owners ??= (async () => {
    const o: Owners = { page: new Map(), file: new Map(), key: new Map() };
    for (const doc of (await (await db()).getAll('docs')) as DocMeta[]) noteOwner(o, doc);
    return o;
  })();
  return owners;
}
function noteOwner(o: Owners, doc: DocMeta) {
  for (const p of doc.pageIds) o.page.set(p, doc.id);
  for (const f of [doc.id, ...(doc.fileIds ?? [])]) o.file.set(f, doc.id);
  if (doc.sealed) o.key.set(doc.id, doc.sealed.k);
  else o.key.delete(doc.id);
}
async function keyOfPage(id: string) {
  const o = await ownersIndex();
  const doc = o.page.get(id);
  return doc ? o.key.get(doc) : undefined;
}
async function keyOfFile(id: string) {
  const o = await ownersIndex();
  const doc = o.file.get(id);
  return doc ? o.key.get(doc) : undefined;
}

async function sealPage(p: PageData, k: string): Promise<StoredPage> {
  const { id, ...rest } = p;
  known.add(id);
  return { id, sealed: await sealJson(needKey(k), k, rest, `page:${id}`) };
}
async function openPage(p: StoredPage): Promise<PageData> {
  if (!('sealed' in p) || !isSealed(p.sealed)) return p as PageData;
  known.add(p.id);
  return { id: p.id, ...(await openJson<Omit<PageData, 'id'>>(needKey(p.sealed.k), p.sealed, `page:${p.id}`)) };
}

/** the parts of a clip that are encrypted; the rest (which document, page, when) stays readable */
const CLIP_SECRET = ['image', 'text', 'note', 'rect', 'audioMime'] as const;
async function sealClip(c: Clip, k: string): Promise<Clip> {
  const secret: Record<string, unknown> = {};
  const rest: Record<string, unknown> = { ...c };
  for (const f of CLIP_SECRET) { secret[f] = c[f]; delete rest[f]; }
  return { ...(rest as unknown as Clip), note: '', sealed: await sealJson(needKey(k), k, secret, `clip:${c.id}`) };
}
async function openClip(c: Clip): Promise<Clip> {
  if (!isSealed(c.sealed)) return c;
  const key = cachedKey(c.sealed.k);
  if (!key) return c;
  const { sealed, ...rest } = c;
  return { ...rest, ...(await openJson<Partial<Clip>>(key, sealed, `clip:${c.id}`)) } as Clip;
}

async function sealText(ix: TextIndex, k: string) {
  const { docId, ...rest } = ix;
  return { docId, sealed: await sealJson(needKey(k), k, rest, `text:${docId}`) };
}

async function sealFileIfPlain(id: string, k: string) {
  const d = await db();
  const raw = (await d.get('files', id)) as ArrayBuffer | undefined;
  if (raw && !sealedFileKeyId(raw)) await d.put('files', await sealFile(needKey(k), k, raw, `file:${id}`), id);
  known.add(id);
}

/** Files of other documents and of page templates: never encrypted with this one, or they would lock the others too. */
async function sharedFiles(docId: string) {
  const d = await db();
  const out = new Set<string>();
  for (const x of (await d.getAll('docs')) as DocMeta[]) if (x.id !== docId) for (const f of [x.id, ...(x.fileIds ?? [])]) out.add(f);
  for (const tp of (await d.getAll('templates')) as CustomTemplate[]) out.add(tp.fileId);
  return out;
}

/** Records of a sealed document saved before the document listed them (a new page or picture): encrypt them. */
async function sweep(doc: DocMeta) {
  const k = doc.sealed!.k;
  const d = await db();
  for (const id of doc.pageIds) {
    if (known.has(id)) continue;
    const raw = (await d.get('pages', id)) as StoredPage | undefined;
    if (raw && !('sealed' in raw)) await d.put('pages', await sealPage(raw, k));
    known.add(id);
  }
  const todo = [doc.id, ...(doc.fileIds ?? [])].filter((f) => !known.has(f));
  if (!todo.length) return;
  const shared = await sharedFiles(doc.id);
  for (const f of todo) if (!shared.has(f)) await sealFileIfPlain(f, k);
}

/**
 * Rewrites one page encrypted or decrypted. An open document may save the same page meanwhile: the page is read
 * again just before writing, and if it changed, the newer one is converted instead (never an older copy written over it).
 */
async function convertPage(id: string, convert: (raw: StoredPage) => Promise<StoredPage> | null) {
  const d = await db();
  for (let tries = 0; tries < 5; tries++) {
    const raw = (await d.get('pages', id)) as StoredPage | undefined;
    const job = raw && convert(raw);
    if (!job) return;
    const out = await job;
    const tx = d.transaction('pages', 'readwrite');
    const now = (await tx.store.get(id)) as StoredPage | undefined;
    if (JSON.stringify(now) === JSON.stringify(raw)) {
      await Promise.all([tx.store.put(out), tx.done]);
      return;
    }
    await tx.done; // saved meanwhile: start over with the newer page
  }
}

/** Encrypts everything of `doc` with key k (which must be unlocked). Safe to run again after an interruption. */
export async function sealDoc(doc: DocMeta, k: string) {
  needKey(k); // stop before touching anything when the key is not unlocked
  const d = await db();
  // marked first: a document cut off half way is still treated as locked, and finished on the next run
  doc.sealed = { k };
  delete doc.thumb;
  await d.put('docs', doc);
  if (owners) noteOwner(await owners, doc);
  for (const id of doc.pageIds) await convertPage(id, (raw) => ('sealed' in raw ? null : sealPage(raw, k)));
  const shared = await sharedFiles(doc.id);
  const clips = (await d.getAllFromIndex('clips', 'docId', doc.id)) as Clip[];
  for (const f of [doc.id, ...(doc.fileIds ?? []), ...clips.flatMap((c) => (c.audioId ? [c.audioId] : []))]) {
    if (!shared.has(f)) await sealFileIfPlain(f, k);
  }
  for (const c of clips) if (!isSealed(c.sealed)) await d.put('clips', await sealClip(c, k));
  const ix = (await d.get('text', doc.id)) as (TextIndex & { sealed?: Sealed }) | undefined;
  if (ix && !isSealed(ix.sealed)) await d.put('text', await sealText(ix, k));
}

/** Decrypts everything of a sealed document and marks it plain again. Its key must be unlocked. */
export async function unsealDoc(doc: DocMeta) {
  if (!doc.sealed) return;
  needKey(doc.sealed.k);
  const d = await db();
  for (const id of doc.pageIds) await convertPage(id, (raw) => ('sealed' in raw ? openPage(raw) : null));
  const clips = (await d.getAllFromIndex('clips', 'docId', doc.id)) as Clip[];
  for (const f of [doc.id, ...(doc.fileIds ?? []), ...clips.flatMap((c) => (c.audioId ? [c.audioId] : []))]) {
    const raw = (await d.get('files', f)) as ArrayBuffer | undefined;
    const fk = raw && sealedFileKeyId(raw);
    if (raw && fk) await d.put('files', await openFile(needKey(fk), raw, `file:${f}`), f);
    known.delete(f);
  }
  for (const c of clips) {
    if (!isSealed(c.sealed)) continue;
    const { sealed, ...rest } = c;
    await d.put('clips', { ...rest, ...(await openJson<Partial<Clip>>(needKey(sealed.k), sealed, `clip:${c.id}`)) });
  }
  const ix = (await d.get('text', doc.id)) as (TextIndex & { sealed?: Sealed }) | undefined;
  if (ix && isSealed(ix.sealed)) await d.put('text', { docId: doc.id, ...(await openJson<object>(needKey(ix.sealed.k), ix.sealed, `text:${doc.id}`)) });
  for (const id of doc.pageIds) known.delete(id);
  delete doc.sealed;
  await d.put('docs', doc);
  if (owners) noteOwner(await owners, doc);
}
