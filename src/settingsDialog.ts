import { settings, saveSettings, type ButtonAction, type Lang } from './settings';
import { t, applyLang, type Key } from './i18n';
import { h, modal } from './ui';
import { drawPenSegments } from './ink';

function select<T extends string>(label: Key, value: T, options: [T, string][], onChange: (v: T) => void) {
  const sel = h('select');
  for (const [v, text] of options) sel.append(h('option', { value: v, selected: v === value }, text));
  sel.addEventListener('change', () => onChange(sel.value as T));
  return h('label', { class: 'row' }, h('span', {}, t(label)), sel);
}

function toggle(label: Key, value: boolean, onChange: (v: boolean) => void) {
  const cb = h('input', { type: 'checkbox', checked: value });
  cb.addEventListener('change', () => onChange(cb.checked));
  return h('label', { class: 'row' }, h('span', {}, t(label)), cb);
}

/** A scratch area that shows exactly what the stylus reports: type, pressure, tilt, buttons. */
function penTester() {
  const canvas = h('canvas', { class: 'pen-test' });
  const out = h('pre', { class: 'pen-readout' }, '—');
  let pts: number[] = [];
  const dpr = window.devicePixelRatio || 1;
  const ctx = canvas.getContext('2d', { desynchronized: true })!;
  const show = (e: PointerEvent) => {
    out.textContent =
      `type=${e.pointerType}  pressure=${e.pressure.toFixed(3)}  tiltX=${e.tiltX}  tiltY=${e.tiltY}\n` +
      `buttons=${e.buttons}  button=${e.button}  ${e.buttons & 2 ? '[1st button]' : ''} ${e.buttons & 4 ? '[2nd button]' : ''} ${e.buttons & 32 ? '[eraser]' : ''}  ` +
      `size=${Math.round(e.width)}×${Math.round(e.height)}  ${'onpointerrawupdate' in window ? 'rawupdate✓' : ''}`;
  };
  const local = (e: PointerEvent) => {
    const r = canvas.getBoundingClientRect();
    return [e.clientX - r.left, e.clientY - r.top, e.pointerType === 'pen' ? e.pressure : 0.5];
  };
  requestAnimationFrame(() => {
    canvas.width = canvas.clientWidth * dpr;
    canvas.height = canvas.clientHeight * dpr;
  });
  canvas.addEventListener('pointerdown', (e) => {
    canvas.setPointerCapture(e.pointerId);
    pts = local(e);
    show(e);
  });
  canvas.addEventListener('pointermove', (e) => {
    show(e);
    if (!pts.length) return;
    for (const ce of e.getCoalescedEvents?.() ?? [e]) pts.push(...local(ce));
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const n = pts.length / 3;
    const color = e.buttons & 34 ? '#d62839' : '#2156d1';
    drawPenSegments(ctx, pts, Math.max(1, n - 8), n, color, 3);
  });
  const end = () => (pts = []);
  canvas.addEventListener('pointerup', end);
  canvas.addEventListener('pointercancel', end);
  canvas.addEventListener('contextmenu', (e) => e.preventDefault());
  canvas.addEventListener('dblclick', () => ctx.clearRect(0, 0, canvas.width, canvas.height));
  return h('div', { class: 'pen-test-wrap' }, h('h3', {}, t('penTest')), h('p', { class: 'hint' }, t('penTestHint')), canvas, out);
}

export function openSettings(onChange: () => void) {
  const actions: [ButtonAction, string][] = [
    ['eraser', t('eraser')],
    ['lasso', t('lasso')],
    ['highlighter', t('highlighter')],
    ['none', t('none')],
  ];
  const changed = () => { saveSettings(); onChange(); };
  const body = h('form', { method: 'dialog', class: 'settings' },
    h('h2', {}, t('settings')),
    select<Lang>('language', settings.lang, [['ar', 'العربية'], ['de', 'Deutsch'], ['en', 'English']], (v) => {
      settings.lang = v;
      saveSettings();
      applyLang();
      location.reload();
    }),
    h('h3', {}, t('stylus')),
    select('penOnly', settings.penOnly, [['auto', t('auto')], ['on', t('on')], ['off', t('off')]], (v) => { settings.penOnly = v; changed(); }),
    select('barrelButton', settings.barrelButton, actions, (v) => { settings.barrelButton = v; changed(); }),
    select('secondButton', settings.secondButton, actions, (v) => { settings.secondButton = v; changed(); }),
    toggle('pressure', settings.pressure, (v) => { settings.pressure = v; changed(); }),
    toggle('twoFingerUndo', settings.twoFingerUndo, (v) => { settings.twoFingerUndo = v; changed(); }),
    penTester(),
    h('p', { class: 'hint' }, t('storageWarn')),
    h('div', { class: 'actions' }, h('button', { value: 'ok', class: 'primary' }, 'OK')),
  );
  modal(body, 'settings-modal');
}
