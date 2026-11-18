import { defineConfig, type Plugin } from 'vite';
import { readFileSync } from 'node:fs';

/** Emits a service worker that precaches every built file, so the app works fully offline. */
function serviceWorker(): Plugin {
  return {
    name: 'alnai-sw',
    apply: 'build',
    generateBundle(_opts, bundle) {
      const files = ['./', ...Object.keys(bundle).filter((f) => !f.startsWith('ocr/')).map((f) => `./${f}`), './manifest.webmanifest', './icons/icon.svg', './icons/icon-192.png', './icons/icon-512.png'];
      const version = Date.now().toString(36);
      const source = `
const CACHE = 'alnai-${version}';
const FILES = ${JSON.stringify(files)};
self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  if (req.mode === 'navigate') {
    e.respondWith(fetch(req).catch(() => caches.match('./', { ignoreSearch: true })));
    return;
  }
  e.respondWith(caches.match(req, { ignoreSearch: true }).then((hit) => hit || fetch(req)));
});
`;
      this.emitFile({ type: 'asset', fileName: 'sw.js', source });
    },
  };
}

/**
 * Text recognition (Tesseract) works offline: its worker, engine and the Arabic, German and English language files
 * are copied into the app under ocr/ instead of being fetched from the internet.
 */
const OCR_FILES: Record<string, string> = {
  'ocr/worker.min.js': 'node_modules/tesseract.js/dist/worker.min.js',
  // LSTM-only engine, with and without SIMD (every recent Android WebView has SIMD)
  'ocr/tesseract-core-simd-lstm.wasm.js': 'node_modules/tesseract.js-core/tesseract-core-simd-lstm.wasm.js',
  'ocr/tesseract-core-lstm.wasm.js': 'node_modules/tesseract.js-core/tesseract-core-lstm.wasm.js',
  'ocr/ara.traineddata.gz': 'node_modules/@tesseract.js-data/ara/4.0.0_best_int/ara.traineddata.gz',
  'ocr/deu.traineddata.gz': 'node_modules/@tesseract.js-data/deu/4.0.0_best_int/deu.traineddata.gz',
  'ocr/eng.traineddata.gz': 'node_modules/@tesseract.js-data/eng/4.0.0_best_int/eng.traineddata.gz',
};

function ocrAssets(): Plugin {
  return {
    name: 'notiva-ocr',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const path = (req.url ?? '').split('?')[0].replace(/^\//, '');
        const src = OCR_FILES[path];
        if (!src) return next();
        res.setHeader('Content-Type', path.endsWith('.js') ? 'text/javascript' : 'application/octet-stream');
        res.end(readFileSync(src));
      });
    },
    generateBundle() {
      for (const [fileName, src] of Object.entries(OCR_FILES)) this.emitFile({ type: 'asset', fileName, source: readFileSync(src) });
    },
  };
}

export default defineConfig({
  base: './',
  plugins: [ocrAssets(), serviceWorker()],
  build: { target: 'es2022', chunkSizeWarningLimit: 2000 },
});
