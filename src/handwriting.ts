import { Capacitor, registerPlugin } from '@capacitor/core';
import type { Stroke } from './types';
import { settings, saveSettings, type Lang } from './settings';
import { t } from './i18n';
import { h, modal, toast } from './ui';

interface InkRecognizerPlugin {
  /** lines → strokes → flat [x0,y0,x1,y1,…]; returns one recognised string per line */
  recognize(opts: { language: string; lines: number[][][]; width: number; height: number }): Promise<{ lines: string[] }>;
}
const InkRecognizer = registerPlugin<InkRecognizerPlugin>('InkRecognizer');

/**
 * Splits strokes into lines of writing: a stroke joins the current line when its vertical centre
 * falls inside the line's band. Writing order inside a line is kept, which the recogniser relies on.
 */
export function groupLines(strokes: Stroke[]): Stroke[][] {
  const order = new Map(strokes.map((s, i) => [s.id, i]));
  const byTop = [...strokes].sort((a, b) => a.bbox[1] - b.bbox[1]);
  const lines: { top: number; bottom: number; items: Stroke[] }[] = [];
  for (const s of byTop) {
    const cy = (s.bbox[1] + s.bbox[3]) / 2;
    const line = lines.find((l) => cy >= l.top && cy <= l.bottom);
    if (line) {
      line.items.push(s);
      // dots and marks may widen the band a little, but a tall stroke must not swallow the next line
      const h0 = line.bottom - line.top;
      line.top = Math.min(line.top, Math.max(s.bbox[1], line.top - h0 * 0.3));
      line.bottom = Math.max(line.bottom, Math.min(s.bbox[3], line.bottom + h0 * 0.3));
    } else lines.push({ top: s.bbox[1], bottom: s.bbox[3], items: [s] });
  }
  lines.sort((a, b) => a.top - b.top);
  return lines.map((l) => l.items.sort((a, b) => order.get(a.id)! - order.get(b.id)!));
}

function median(v: number[]) {
  const s = [...v].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)] ?? 0;
}

async function recognise(strokes: Stroke[], language: Lang): Promise<string> {
  const lines = groupLines(strokes);
  const payload = lines.map((line) =>
    line.map((s) => {
      const flat: number[] = [];
      for (let i = 0; i < s.pts.length; i += 3) flat.push(s.pts[i], s.pts[i + 1]);
      return flat;
    }),
  );
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const s of strokes) {
    x0 = Math.min(x0, s.bbox[0]); y0 = Math.min(y0, s.bbox[1]);
    x1 = Math.max(x1, s.bbox[2]); y1 = Math.max(y1, s.bbox[3]);
  }
  const lineH = median(lines.map((l) => Math.max(...l.map((s) => s.bbox[3])) - Math.min(...l.map((s) => s.bbox[1]))));
  const res = await InkRecognizer.recognize({ language, lines: payload, width: Math.max(1, x1 - x0), height: Math.max(1, lineH) });
  return res.lines.join('\n');
}

/** Shows the recognised text for review. Resolves null when cancelled. */
export async function convertHandwriting(strokes: Stroke[]): Promise<{ text: string; replace: boolean; size: number } | null> {
  if (!strokes.length) return null;
  if (!Capacitor.isNativePlatform()) {
    toast(t('hwUnavailable'));
    return null;
  }
  const lines = groupLines(strokes);
  const lineH = median(lines.map((l) => Math.max(...l.map((s) => s.bbox[3])) - Math.min(...l.map((s) => s.bbox[1]))));
  const size = Math.round(Math.max(11, Math.min(30, lineH * 0.62)));

  const langSel = h('select');
  const cur = settings.hwLang ?? settings.lang;
  for (const [v, label] of [['ar', 'العربية'], ['de', 'Deutsch'], ['en', 'English']] as const)
    langSel.append(h('option', { value: v, selected: v === cur }, label));
  const out = h('textarea', { class: 'text hw-result', dir: 'auto', rows: Math.min(8, Math.max(3, lines.length + 1)) });
  const status = h('p', { class: 'hint' }, t('recognizing'));
  const replaceBtn = h('button', { value: 'replace', class: 'primary', disabled: true }, t('replaceInk'));
  const keepBtn = h('button', { value: 'keep', disabled: true }, t('keepInk'));
  const form = h('form', { method: 'dialog', class: 'hw-dialog' },
    h('h2', {}, t('toText')),
    h('label', { class: 'row' }, h('span', {}, t('hwLanguage')), langSel),
    out,
    status,
    h('div', { class: 'actions' }, h('button', { value: 'cancel' }, t('cancel')), keepBtn, replaceBtn),
  );

  let run = 0;
  const go = async () => {
    const id = ++run;
    const lang = langSel.value as Lang;
    settings.hwLang = lang;
    saveSettings();
    replaceBtn.disabled = keepBtn.disabled = true;
    status.textContent = `${t('recognizing')} ${t('hwDownload')}`;
    try {
      const text = await recognise(strokes, lang);
      if (id !== run) return;
      out.value = text;
      status.textContent = '';
      replaceBtn.disabled = keepBtn.disabled = false;
    } catch (err) {
      if (id !== run) return;
      console.error(err);
      status.textContent = `${t('hwFailed')}: ${err instanceof Error ? err.message : String(err)}`;
    }
  };
  langSel.addEventListener('change', () => void go());
  out.addEventListener('input', () => (replaceBtn.disabled = keepBtn.disabled = !out.value.trim()));
  const { closed } = modal(form);
  void go();
  const v = await closed;
  run++;
  const text = out.value.trim();
  if ((v !== 'replace' && v !== 'keep') || !text) return null;
  return { text, replace: v === 'replace', size };
}
