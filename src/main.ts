import './styles.css';
import { applyLang, applyTheme, t } from './i18n';
import { Library } from './library';
import { Editor } from './editor';
import { A4, uid, type DocMeta, type PageData } from './types';
import { getDoc, listDocs, putDoc, putPages } from './db';
import { settings } from './settings';
import { icons } from './icons';
import { h, modal } from './ui';
import { Capacitor } from '@capacitor/core';
import { ensureUnlocked } from './lock';

applyLang();
applyTheme();
const app = document.getElementById('app')!;

// Ask the browser not to evict our notes under storage pressure.
void navigator.storage?.persist?.();

let library: Library;
let editor: Editor | null = null;
/** the second document, beside the first one */
let side: Editor | null = null;
let splitRoot: HTMLElement | null = null;

function showLibrary() {
  library = new Library((d) => void openDoc(d));
  app.replaceChildren(library.root);
  void library.mount();
}

async function openDocAt(docId: string, page: number) {
  const doc = await getDoc(docId);
  if (!doc) return;
  doc.lastPage = page;
  void openDoc(doc);
}

/** Every way of opening a document passes here, so a locked one always asks for fingerprint or PIN. */
async function openDoc(doc: DocMeta) {
  if (!(await ensureUnlocked(doc))) return;
  // Android back gesture closes the document instead of leaving the app.
  const onPop = () => void ed.close();
  const ed = new Editor(doc, async () => {
    editor = null;
    window.removeEventListener('popstate', onPop);
    if (side) await side.close();
    side = null;
    splitRoot = null;
    if (history.state?.doc) history.back();
    showLibrary();
  }, (docId, page) => void openDocAt(docId, page), { onSplit: () => void pickSplit(doc) });
  app.replaceChildren(ed.root);
  history.pushState({ doc: doc.id }, '', '');
  window.addEventListener('popstate', onPop);
  editor = ed;
  void ed.open();
}

// ---------------------------------------------------------------- split view

const SPLIT_KEY = 'alnai-split';
function splitShare(): number {
  try { return Math.min(0.75, Math.max(0.25, Number(localStorage.getItem(SPLIT_KEY)) || 0.5)); } catch { return 0.5; }
}

/** Opens `doc` beside the open document (or replaces the one already beside it). */
async function openSide(doc: DocMeta) {
  if (!editor) return;
  if (!(await ensureUnlocked(doc))) return;
  if (side) await side.close();
  const ed = new Editor(doc, () => closeSide(ed), undefined, { side: true });
  if (!splitRoot) {
    const divider = h('div', { class: 'split-divider', role: 'separator', 'aria-orientation': 'vertical', tabindex: 0 });
    splitRoot = h('div', { class: 'split-root' }, editor.root, divider);
    splitRoot.style.setProperty('--side', String(splitShare()));
    dragDivider(divider, splitRoot);
    app.replaceChildren(splitRoot);
  }
  splitRoot.append(ed.root);
  side = ed;
  Editor.active = ed;
  void ed.open();
}

function closeSide(ed: Editor) {
  if (side !== ed) return;
  side = null;
  if (splitRoot && editor) {
    app.replaceChildren(editor.root);
    splitRoot = null;
  }
}

/** The divider moves with finger, pen or mouse; the share is remembered. */
function dragDivider(divider: HTMLElement, root: HTMLElement) {
  divider.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    divider.setPointerCapture(e.pointerId);
    const r = root.getBoundingClientRect();
    const portrait = getComputedStyle(root).flexDirection === 'column';
    const rtl = document.documentElement.dir === 'rtl';
    const move = (ev: PointerEvent) => {
      let share = portrait ? (r.bottom - ev.clientY) / r.height : (r.right - ev.clientX) / r.width;
      if (rtl && !portrait) share = 1 - share;
      share = Math.min(0.75, Math.max(0.25, share));
      root.style.setProperty('--side', String(share));
      try { localStorage.setItem(SPLIT_KEY, String(share)); } catch { /* private mode */ }
    };
    const up = () => {
      divider.removeEventListener('pointermove', move);
      divider.removeEventListener('pointerup', up);
      divider.removeEventListener('pointercancel', up);
    };
    divider.addEventListener('pointermove', move);
    divider.addEventListener('pointerup', up);
    divider.addEventListener('pointercancel', up);
  });
}

/** Choose what to open beside the current document: a new notebook for it, or any other document. */
async function pickSplit(current: DocMeta) {
  const docs = (await listDocs()).filter((d) => d.id !== current.id);
  // notebooks first: the usual pairing is a PDF on one side and your notes on the other
  docs.sort((a, b) => Number(b.kind === 'notebook') - Number(a.kind === 'notebook') || b.updatedAt - a.updatedAt);
  const item = (icon: string, label: string, sub: string, value: string) => {
    const b = h('button', { value, class: 'menu-item split-item' });
    b.innerHTML = icon;
    b.append(h('span', {}, h('b', {}, label), h('small', {}, sub)));
    return b;
  };
  const list = h('div', { class: 'split-list' },
    ...docs.map((d) => item(d.kind === 'pdf' ? icons.file : d.kind === 'images' ? icons.image : icons.pen, d.title,
      `${d.pageIds.length} ${t('pages')} · ${new Date(d.updatedAt).toLocaleDateString(document.documentElement.lang)}`, d.id)));
  const form = h('form', { method: 'dialog', class: 'menu split-pick' },
    h('h2', {}, t('splitView')),
    h('p', { class: 'hint' }, t('splitHint')),
    item(icons.plus, t('newNotesFor'), current.title, 'new'),
    list,
    h('button', { value: 'cancel' }, t('cancel')),
  );
  const v = await modal(form).closed;
  if (!v || v === 'cancel') return;
  if (v === 'new') {
    const page: PageData = { id: uid(), w: A4.w, h: A4.h, bg: { kind: 'template', template: 'lined', paper: 'white', rtl: settings.lang === 'ar' }, strokes: [] };
    const now = Date.now();
    const doc: DocMeta = {
      id: uid(), title: `${t('notesTitle')} – ${current.title}`, kind: 'notebook', createdAt: now, updatedAt: now,
      pageIds: [page.id], template: 'lined', paper: 'white', ...(current.folderId ? { folderId: current.folderId } : {}),
    };
    await putPages([page]);
    await putDoc(doc);
    await openSide(doc);
    return;
  }
  const doc = await getDoc(v);
  if (doc) await openSide(doc);
}

// Android system back: close the top dialog, then the document beside, then the document, then leave the app.
if (Capacitor.isNativePlatform()) {
  void import('@capacitor/app').then(({ App }) =>
    App.addListener('backButton', () => {
      const dialogs = document.querySelectorAll<HTMLDialogElement>('dialog[open]');
      if (dialogs.length) dialogs[dialogs.length - 1].close('cancel');
      else if (side) void side.close();
      else if (editor) void editor.close();
      else void App.exitApp();
    }),
  );
}

showLibrary();

// The Android app ships its files inside the APK, so only the web version needs a service worker.
if ('serviceWorker' in navigator && import.meta.env.PROD && !Capacitor.isNativePlatform()) {
  window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js'));
}
