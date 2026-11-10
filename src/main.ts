import './styles.css';
import { applyLang } from './i18n';
import { Library } from './library';
import { Editor } from './editor';
import type { DocMeta } from './types';
import { Capacitor } from '@capacitor/core';

applyLang();
const app = document.getElementById('app')!;

// Ask the browser not to evict our notes under storage pressure.
void navigator.storage?.persist?.();

let library: Library;

function showLibrary() {
  library = new Library(openDoc);
  app.replaceChildren(library.root);
  void library.mount();
}

function openDoc(doc: DocMeta) {
  // Android back gesture closes the document instead of leaving the app.
  const onPop = () => void ed.close();
  const ed = new Editor(doc, () => {
    window.removeEventListener('popstate', onPop);
    if (history.state?.doc) history.back();
    showLibrary();
  });
  app.replaceChildren(ed.root);
  history.pushState({ doc: doc.id }, '', '');
  window.addEventListener('popstate', onPop);
  void ed.open();
}

showLibrary();

// The Android app ships its files inside the APK, so only the web version needs a service worker.
if ('serviceWorker' in navigator && import.meta.env.PROD && !Capacitor.isNativePlatform()) {
  window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js'));
}
