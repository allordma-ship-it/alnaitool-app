import './styles.css';
import { applyLang, applyTheme } from './i18n';
import { Library } from './library';
import { Editor } from './editor';
import type { DocMeta } from './types';
import { getDoc } from './db';
import { Capacitor } from '@capacitor/core';

applyLang();
applyTheme();
const app = document.getElementById('app')!;

// Ask the browser not to evict our notes under storage pressure.
void navigator.storage?.persist?.();

let library: Library;
let editor: Editor | null = null;

function showLibrary() {
  library = new Library(openDoc);
  app.replaceChildren(library.root);
  void library.mount();
}

async function openDocAt(docId: string, page: number) {
  const doc = await getDoc(docId);
  if (!doc) return;
  doc.lastPage = page;
  openDoc(doc);
}

function openDoc(doc: DocMeta) {
  // Android back gesture closes the document instead of leaving the app.
  const onPop = () => void ed.close();
  const ed = new Editor(doc, () => {
    editor = null;
    window.removeEventListener('popstate', onPop);
    if (history.state?.doc) history.back();
    showLibrary();
  }, (docId, page) => void openDocAt(docId, page));
  app.replaceChildren(ed.root);
  history.pushState({ doc: doc.id }, '', '');
  window.addEventListener('popstate', onPop);
  editor = ed;
  void ed.open();
}

// Android system back: close the top dialog, then the document, then leave the app.
if (Capacitor.isNativePlatform()) {
  void import('@capacitor/app').then(({ App }) =>
    App.addListener('backButton', () => {
      const dialogs = document.querySelectorAll<HTMLDialogElement>('dialog[open]');
      if (dialogs.length) dialogs[dialogs.length - 1].close('cancel');
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
