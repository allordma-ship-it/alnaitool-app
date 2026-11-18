import { Capacitor, registerPlugin } from '@capacitor/core';
import { makePin, checkPin, normalizePin, lockoutSeconds, type PinRecord } from './pin';
import { listFolders, listDocs, putDoc, putFolder } from './db';
import type { DocMeta, Folder } from './types';
import { t } from './i18n';
import { h, modal } from './ui';
import { icons } from './icons';

interface BiometricPlugin {
  isAvailable(): Promise<{ available: boolean; code?: number }>;
  authenticate(o: { title: string; subtitle?: string; cancel?: string }): Promise<{ ok: boolean; code?: number; message?: string }>;
}
const Biometric = registerPlugin<BiometricPlugin>('Biometric');

const KEY = 'alnai-lock';
interface LockConfig { pin: PinRecord; bio: boolean; fails?: number; until?: number }

function load(): LockConfig | null {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    return v?.pin?.hash ? v : null;
  } catch {
    return null;
  }
}
function save(c: LockConfig | null) {
  try {
    if (c) localStorage.setItem(KEY, JSON.stringify(c));
    else localStorage.removeItem(KEY);
  } catch { /* private mode */ }
}

/** A PIN exists. Without one, lock flags (e.g. from a backup restored on another device) are ignored. */
export function lockEnabled(): boolean {
  return !!load();
}

// ---------------------------------------------------------------- what is locked, what is open

let lockedFolders = new Set<string>();
/** unlocked during this visit; everything locks again when the app goes to the background */
const unlocked = new Set<string>();
document.addEventListener('visibilitychange', () => { if (document.hidden) unlocked.clear(); });

/** Keep the list of locked folders current (the library calls this on every refresh). */
export function setLockedFolders(folders: Folder[]) {
  lockedFolders = new Set(folders.filter((f) => f.locked).map((f) => f.id));
}

/** Locked by itself or by its folder (and a PIN is set). */
export function isLocked(d: DocMeta): boolean {
  return lockEnabled() && (!!d.locked || (!!d.folderId && lockedFolders.has(d.folderId)));
}

/** Locked and not opened with fingerprint/PIN during this visit: hide its pictures, text and clips. */
export function isHidden(d: DocMeta): boolean {
  return isLocked(d) && !unlocked.has(d.id) && !(d.folderId && unlocked.has(d.folderId));
}

export function folderHidden(f: Folder): boolean {
  return lockEnabled() && !!f.locked && !unlocked.has(f.id);
}

/** Ids of documents whose contents must stay hidden right now (for clips and search). */
export async function hiddenDocIds(): Promise<Set<string>> {
  if (!lockEnabled()) return new Set();
  setLockedFolders(await listFolders());
  return new Set((await listDocs()).filter(isHidden).map((d) => d.id));
}

/** Asks for fingerprint or PIN if needed; true when the document may be opened. */
export async function ensureUnlocked(d: DocMeta): Promise<boolean> {
  setLockedFolders(await listFolders());
  if (!isHidden(d)) return true;
  if (!(await verify(d.title))) return false;
  unlocked.add(d.id);
  return true;
}

export async function ensureFolderUnlocked(f: Folder): Promise<boolean> {
  if (!folderHidden(f)) return true;
  if (!(await verify(f.name))) return false;
  unlocked.add(f.id);
  return true;
}

// ---------------------------------------------------------------- fingerprint

export async function biometricAvailable(): Promise<boolean> {
  if (!Capacitor.isNativePlatform()) return false;
  try {
    return (await Biometric.isAvailable()).available;
  } catch {
    return false;
  }
}

async function fingerprint(subtitle: string): Promise<boolean> {
  try {
    return (await Biometric.authenticate({ title: t('unlockTitle'), subtitle, cancel: t('usePin') })).ok;
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------- dialogs

/** Fingerprint first (if turned on), PIN otherwise. Resolves true when it was really you. */
export async function verify(what: string): Promise<boolean> {
  const cfg = load();
  if (!cfg) return true;
  if (cfg.bio && (await biometricAvailable()) && (await fingerprint(what))) {
    save({ ...cfg, fails: 0, until: 0 });
    return true;
  }
  return pinDialog(what);
}

function pinField(label: string) {
  const input = h('input', {
    type: 'password', inputmode: 'numeric', autocomplete: 'off', maxlength: 8, class: 'text pin-input', dir: 'ltr', 'aria-label': label,
  });
  return { input, row: h('label', { class: 'row col' }, h('span', {}, label), input) };
}

function pinDialog(what: string): Promise<boolean> {
  return new Promise((resolve) => {
    const { input, row } = pinField(t('enterPin'));
    const err = h('p', { class: 'pin-err', role: 'alert' });
    const ok = h('button', { type: 'submit', value: 'ok', class: 'primary' }, t('unlock'));
    const mark = h('div', { class: 'lock-mark', 'aria-hidden': 'true' });
    mark.innerHTML = icons.lock;
    const form = h('form', { method: 'dialog', class: 'new-nb lock-dlg' },
      mark, h('h2', {}, t('unlockTitle')), h('p', { class: 'hint' }, what), row, err,
      h('div', { class: 'actions' }, h('button', { type: 'submit', value: 'cancel', formnovalidate: true }, t('cancel')), ok),
    );
    const { dlg } = modal(form);
    let done = false;
    const finish = (v: boolean) => { if (!done) { done = true; resolve(v); if (dlg.open) dlg.close(); } };
    dlg.addEventListener('close', () => finish(false));
    const waitTick = () => {
      const cfg = load();
      const left = Math.ceil(((cfg?.until ?? 0) - Date.now()) / 1000);
      ok.disabled = left > 0;
      if (left > 0) { err.textContent = `${t('pinWait')} ${left} s`; setTimeout(waitTick, 1000); }
      else if (err.textContent?.startsWith(t('pinWait'))) err.textContent = '';
    };
    waitTick();
    form.addEventListener('submit', async (e) => {
      const btn = (e as SubmitEvent).submitter as HTMLButtonElement | null;
      if (btn?.value === 'cancel') return; // closes the dialog -> false
      e.preventDefault();
      const cfg = load();
      if (!cfg) return finish(true);
      if ((cfg.until ?? 0) > Date.now()) return;
      const pin = normalizePin(input.value);
      if (pin && (await checkPin(pin, cfg.pin))) {
        save({ ...cfg, fails: 0, until: 0 });
        return finish(true);
      }
      const fails = (cfg.fails ?? 0) + 1;
      save({ ...cfg, fails, until: Date.now() + lockoutSeconds(fails) * 1000 });
      err.textContent = t('wrongPin');
      input.value = '';
      input.focus();
      waitTick();
    });
    requestAnimationFrame(() => input.focus());
  });
}

/** Choose a PIN (typed twice). Returns false if cancelled. */
export function setupPin(): Promise<boolean> {
  return new Promise((resolve) => {
    const a = pinField(t('newPin'));
    const b = pinField(t('repeatPin'));
    const err = h('p', { class: 'pin-err', role: 'alert' });
    const mark = h('div', { class: 'lock-mark', 'aria-hidden': 'true' });
    mark.innerHTML = icons.lock;
    const form = h('form', { method: 'dialog', class: 'new-nb lock-dlg' },
      mark, h('h2', {}, t('setPinTitle')), h('p', { class: 'hint' }, t('setPinHint')), a.row, b.row, err,
      h('div', { class: 'actions' },
        h('button', { type: 'submit', value: 'cancel', formnovalidate: true }, t('cancel')),
        h('button', { type: 'submit', value: 'ok', class: 'primary' }, t('save'))),
    );
    const { dlg } = modal(form);
    let done = false;
    const finish = (v: boolean) => { if (!done) { done = true; resolve(v); if (dlg.open) dlg.close(); } };
    dlg.addEventListener('close', () => finish(false));
    form.addEventListener('submit', async (e) => {
      const btn = (e as SubmitEvent).submitter as HTMLButtonElement | null;
      if (btn?.value === 'cancel') return;
      e.preventDefault();
      const p1 = normalizePin(a.input.value), p2 = normalizePin(b.input.value);
      if (!p1) { err.textContent = t('pinRule'); return; }
      if (p1 !== p2) { err.textContent = t('pinMismatch'); b.input.value = ''; b.input.focus(); return; }
      const old = load();
      save({ pin: await makePin(p1), bio: old?.bio ?? (await biometricAvailable()), fails: 0, until: 0 });
      finish(true);
    });
    requestAnimationFrame(() => a.input.focus());
  });
}

// ---------------------------------------------------------------- lock / unlock items

/** Locks or unlocks a document. The first lock asks you to choose a PIN; removing a lock asks for it. */
export async function toggleDocLock(d: DocMeta): Promise<boolean> {
  if (d.locked && lockEnabled()) {
    if (!(await verify(d.title))) return false;
    delete d.locked;
  } else {
    if (!lockEnabled() && !(await setupPin())) return false;
    d.locked = true;
    unlocked.delete(d.id);
  }
  await putDoc(d);
  return true;
}

export async function toggleFolderLock(f: Folder): Promise<boolean> {
  if (f.locked && lockEnabled()) {
    if (!(await verify(f.name))) return false;
    delete f.locked;
  } else {
    if (!lockEnabled() && !(await setupPin())) return false;
    f.locked = true;
    unlocked.delete(f.id);
  }
  await putFolder(f);
  return true;
}

// ---------------------------------------------------------------- settings

export function bioEnabled(): boolean {
  return !!load()?.bio;
}

export async function setBio(on: boolean): Promise<boolean> {
  const cfg = load();
  if (!cfg) return false;
  if (on && !(await fingerprint(t('bioConfirm')))) return false;
  save({ ...cfg, bio: on });
  return true;
}

export async function changePin(): Promise<boolean> {
  if (!(await verify(t('changePin')))) return false;
  return setupPin();
}

/** Turns the whole feature off: checks it is you, then removes every lock and the PIN. */
export async function turnOffLock(): Promise<boolean> {
  if (!lockEnabled()) return true;
  if (!(await verify(t('lockOff')))) return false;
  for (const d of await listDocs()) if (d.locked) { delete d.locked; await putDoc(d); }
  for (const f of await listFolders()) if (f.locked) { delete f.locked; await putFolder(f); }
  save(null);
  unlocked.clear();
  return true;
}
