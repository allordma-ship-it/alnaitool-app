import { ocrDocument, ocrLangs, setOcrLangs, stopOcr, OCR_LANGS, type OcrLang } from './ocr';
import type { DocMeta } from './types';
import { t } from './i18n';
import { h, modal, toast } from './ui';

const LABEL: Record<OcrLang, () => string> = { ara: () => 'العربية', deu: () => 'Deutsch', eng: () => 'English' };

/** Language chips (at least one stays on); returns the box and a getter. */
export function langPicker(): { el: HTMLElement; langs: () => OcrLang[] } {
  const chosen = new Set(ocrLangs());
  const el = h('div', { class: 'tag-chips wrap ocr-langs' });
  for (const l of OCR_LANGS) {
    const b = h('button', { type: 'button', class: `tag-chip ${chosen.has(l) ? 'active' : ''}`, 'aria-pressed': String(chosen.has(l)) }, LABEL[l]());
    b.addEventListener('click', () => {
      if (chosen.has(l) && chosen.size > 1) chosen.delete(l);
      else chosen.add(l);
      b.classList.toggle('active', chosen.has(l));
      b.setAttribute('aria-pressed', String(chosen.has(l)));
    });
    el.append(b);
  }
  return { el, langs: () => OCR_LANGS.filter((l) => chosen.has(l)) };
}

let running = false;

/** Reads a document's pages in the background, with a progress note; the app stays usable. */
export async function runOcr(doc: DocMeta, langs = ocrLangs()) {
  if (running) { toast(t('ocrBusy')); return; }
  running = true;
  setOcrLangs(langs);
  const bar = h('div', { class: 'ocr-progress', role: 'status' }, h('span', {}, `${t('ocrRunning')} · ${doc.title}`), h('i', {}, h('b')));
  document.body.append(bar);
  const fill = bar.querySelector('b')!;
  const label = bar.querySelector('span')!;
  try {
    const found = await ocrDocument(doc, langs, (done, total) => {
      fill.style.width = `${Math.round((done / Math.max(1, total)) * 100)}%`;
      label.textContent = `${t('ocrRunning')} · ${doc.title} · ${Math.min(done + 1, total)} / ${total}`;
    });
    toast(found ? `${t('ocrDone')}: ${found} ${t('pages')}` : t('ocrNothing'));
  } catch (e) {
    console.error(e);
    toast(`${t('ocrFailed')}: ${e instanceof Error ? e.message : String(e)}`);
  } finally {
    bar.remove();
    running = false;
    void stopOcr();
  }
}

/** "Recognise text" in a document's menu: choose languages, then read it. */
export async function ocrDialog(doc: DocMeta) {
  const pick = langPicker();
  const form = h('form', { method: 'dialog', class: 'new-nb' },
    h('h2', {}, t('ocrTitle')),
    h('p', { class: 'hint' }, t('ocrHint')),
    h('div', { class: 'row col' }, h('span', {}, t('ocrLanguages')), pick.el),
    h('div', { class: 'actions' },
      h('button', { type: 'submit', value: 'cancel' }, t('cancel')),
      h('button', { type: 'submit', value: 'go', class: 'primary' }, t('ocrStart'))),
  );
  if ((await modal(form).closed) !== 'go') return;
  void runOcr(doc, pick.langs());
}
