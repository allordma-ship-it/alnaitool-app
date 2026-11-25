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
import { reconcileReminders, onReminderTap } from './reminders';
import { maybeAutoBackup } from './backup';
import { documentOpened, documentClosed } from './vault';

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
  autoBackupSoon();
}

// Automatic backup (Android app): only while the library is on screen, never while you write in a document.
let autoTimer = 0;
function autoBackupSoon() {
  clearTimeout(autoTimer);
  autoTimer = window.setTimeout(() => {
    if (!editor && !document.hidden) void maybeAutoBackup().then((made) => { if (made && !editor) void library.refresh(); });
  }, 15_000);
}
document.addEventListener('visibilitychange', () => { if (!document.hidden && !editor) autoBackupSoon(); });

async function openDocAt(docId: string, page: number) {
  const doc = await getDoc(docId);
  if (!doc) return;
  doc.lastPage = page;
  void openDoc(doc);
}

// ---------------------------------------------------------------- tabs

const TABS_KEY = 'alnai-tabs';
const MAX_TABS = 8;
let tabs: string[] = (() => {
  try { return (JSON.parse(localStorage.getItem(TABS_KEY) ?? '[]') as string[]).filter((x) => typeof x === 'string'); } catch { return []; }
})();
function saveTabs() {
  try { localStorage.setItem(TABS_KEY, JSON.stringify(tabs)); } catch { /* private mode */ }
}
function addTab(id: string) {
  if (!tabs.includes(id)) tabs.push(id);
  while (tabs.length > MAX_TABS) tabs.splice(tabs.findIndex((x) => x !== id), 1);
  saveTabs();
}

const shell = h('div', { class: 'doc-shell' });
const tabbar = h('nav', { class: 'tabbar', 'aria-label': t('tabs') });
const content = h('div', { class: 'doc-content' });
shell.append(tabbar, content);

/** Puts a document view (one editor, or the split view) on screen, under the tab strip. */
function showContent(node: HTMLElement) {
  content.replaceChildren(node);
  if (shell.parentElement !== app) app.replaceChildren(shell);
  void renderTabs();
}

async function renderTabs() {
  const metas = (await Promise.all(tabs.map((id) => getDoc(id)))).filter((d): d is DocMeta => !!d);
  if (metas.length !== tabs.length) { tabs = metas.map((d) => d.id); saveTabs(); }
  tabbar.replaceChildren();
  tabbar.hidden = metas.length < 2;
  const active = editor?.docId;
  for (const d of metas) {
    const on = d.id === active;
    const close = h('span', { class: 'tab-x', role: 'button', 'aria-label': t('closeTab'), title: t('closeTab') }, '×');
    const tab = h('button', { type: 'button', class: `tab ${on ? 'active' : ''}`, 'aria-current': on ? 'page' : undefined, title: d.title },
      h('span', { class: 'tab-title', dir: 'auto' }, d.title), close);
    tab.addEventListener('click', (e) => {
      if ((e.target as HTMLElement).closest('.tab-x')) return void closeTab(d.id);
      if (!on) void openDoc(d);
    });
    tabbar.append(tab);
  }
  tabbar.querySelector('.tab.active')?.scrollIntoView({ inline: 'nearest', block: 'nearest' });
}

async function closeTab(id: string) {
  const i = tabs.indexOf(id);
  if (i < 0) return;
  tabs.splice(i, 1);
  saveTabs();
  if (editor?.docId !== id) return void renderTabs();
  // closing the open document: show its neighbour, or the library when it was the last one
  const next = tabs[Math.min(i, tabs.length - 1)];
  const doc = next ? await getDoc(next) : undefined;
  if (doc) await openDoc(doc);
  else if (!(await editor.close())) addTab(id); // chose to stay: the tab comes back
  void renderTabs();
}

/** true while one document is being swapped for another (no trip through the library) */
let switching = false;

/** Every way of opening a document passes here, so a locked one always asks for fingerprint or PIN. */
async function openDoc(doc: DocMeta) {
  // already open: just go to the page asked for
  if (editor && editor.docId === doc.id) {
    if (doc.lastPage != null) editor.jumpTo(doc.lastPage);
    return;
  }
  if (!(await ensureUnlocked(doc))) return;
  const wasOpen = !!editor;
  if (editor) {
    switching = true;
    let left = false;
    try { left = await editor.close(); } finally { switching = false; }
    // "Cancel" in the save question: stay where you are
    if (!left) return;
  }
  // Android back gesture closes the document instead of leaving the app.
  const onPop = () => void ed.close().then((left) => {
    // the back gesture already left this page's history entry: put it back when you chose to stay
    if (!left) history.pushState({ doc: doc.id }, '', '');
  });
  // keys stay in memory while a document is open, so it can still save after the app is hidden
  documentOpened();
  const ed = new Editor(doc, async () => {
    documentClosed();
    editor = null;
    window.removeEventListener('popstate', onPop);
    if (side) await side.close();
    side = null;
    splitRoot = null;
    if (switching) return;
    if (history.state?.doc) history.back();
    showLibrary();
  }, (docId, page) => void openDocAt(docId, page), { onSplit: () => void pickSplit(doc) });
  editor = ed;
  addTab(doc.id);
  showContent(ed.root);
  if (wasOpen) history.replaceState({ doc: doc.id }, '', '');
  else history.pushState({ doc: doc.id }, '', '');
  window.addEventListener('popstate', onPop);
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
  if (side && !(await side.close())) return;
  documentOpened();
  const ed = new Editor(doc, () => { documentClosed(); closeSide(ed); }, undefined, { side: true });
  if (!splitRoot) {
    const divider = h('div', { class: 'split-divider', role: 'separator', 'aria-orientation': 'vertical', tabindex: 0 });
    splitRoot = h('div', { class: 'split-root' }, editor.root, divider);
    splitRoot.style.setProperty('--side', String(splitShare()));
    dragDivider(divider, splitRoot);
    showContent(splitRoot);
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
    showContent(editor.root);
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
// reminders: tapping one opens its page; after a reinstall or restore they are scheduled again
void onReminderTap((docId, page) => void openDocAt(docId, page));
void reconcileReminders();

// The Android app ships its files inside the APK, so only the web version needs a service worker.
if ('serviceWorker' in navigator && import.meta.env.PROD && !Capacitor.isNativePlatform()) {
  window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js'));
}
