import { Capacitor, registerPlugin } from '@capacitor/core';
import { makePin, checkPin, normalizePin, lockoutSeconds, type PinRecord } from './pin';
import { listFolders, listDocs, putDoc, putFolder, sealDoc, unsealDoc, onDocSaved } from './db';
import {
  newRawKey, newKeyId, newRecoveryCode, normalizeRecoveryCode, wrapKey, unwrapKey, importDataKey, rememberKey, cachedKey,
  wipeKeys, keysFresh, isWrap, PIN_ITER, REC_ITER, type KeyEntry, type Wrap,
} from './vault';
import type { DocMeta, Folder } from './types';
import { t } from './i18n';
import { h, modal, toast } from './ui';
import { icons } from './icons';

interface BiometricPlugin {
  isAvailable(): Promise<{ available: boolean; code?: number }>;
  authenticate(o: { title: string; subtitle?: string; cancel?: string }): Promise<{ ok: boolean; code?: number; message?: string }>;
}
const Biometric = registerPlugin<BiometricPlugin>('Biometric');

const KEY = 'alnai-lock';
/** keys: the data keys that encrypt locked documents (the first one encrypts new locks), wrapped with the PIN */
interface LockConfig { pin: PinRecord; bio: boolean; fails?: number; until?: number; keys?: KeyEntry[] }

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

/** A PIN exists. */
export function lockEnabled(): boolean {
  return !!load();
}

// ---------------------------------------------------------------- keys of restored backups

/** Keys that came with a restored backup and are not yet opened here: only that backup's recovery code opens them. */
const PENDING = 'notiva-backup-keys';
export interface BackupKey { id: string; rec: Wrap }

function pendingKeys(): BackupKey[] {
  try {
    const v = JSON.parse(localStorage.getItem(PENDING) ?? '[]') as BackupKey[];
    return Array.isArray(v) ? v.filter((k) => typeof k?.id === 'string' && isWrap(k.rec)) : [];
  } catch {
    return [];
  }
}
function savePending(list: BackupKey[]) {
  try { localStorage.setItem(PENDING, JSON.stringify(list)); } catch { /* private mode */ }
}

/** A restore brought these keys: remember the ones this device does not have yet. */
export function addBackupKeys(keys: BackupKey[]) {
  const have = new Set([...(load()?.keys ?? []).map((k) => k.id), ...pendingKeys().map((k) => k.id)]);
  savePending([...pendingKeys(), ...keys.filter((k) => !have.has(k.id) && isWrap(k.rec))]);
}

/** What a backup carries: every key, wrapped only with its recovery code (a PIN could be guessed offline). */
export function backupKeys(): BackupKey[] {
  return [...(load()?.keys ?? []).map((k) => ({ id: k.id, rec: k.rec })), ...pendingKeys()];
}

// ---------------------------------------------------------------- what is locked, what is open

let lockedFolders = new Set<string>();
/** unlocked during this visit; everything locks again when the app goes to the background */
const unlocked = new Set<string>();
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) return;
  unlocked.clear();
  wipeKeys();
});

/** Keep the list of locked folders current (the library calls this on every refresh). */
export function setLockedFolders(folders: Folder[]) {
  lockedFolders = new Set(folders.filter((f) => f.locked).map((f) => f.id));
}

const wantsLock = (d: DocMeta) => !!d.locked || (!!d.folderId && lockedFolders.has(d.folderId));

/** Encrypted, or locked by itself or by its folder (and a PIN is set). */
export function isLocked(d: DocMeta): boolean {
  return !!d.sealed || (lockEnabled() && wantsLock(d));
}

/** Locked and not opened during this visit (or its key is not unlocked): hide its pictures, text and clips. */
export function isHidden(d: DocMeta): boolean {
  if (!isLocked(d)) return false;
  if (d.sealed && !cachedKey(d.sealed.k)) return true;
  return !unlocked.has(d.id) && !(d.folderId && unlocked.has(d.folderId));
}

export function folderHidden(f: Folder): boolean {
  return lockEnabled() && !!f.locked && !unlocked.has(f.id);
}

/** Ids of documents whose contents must stay hidden right now (for clips and search). */
export async function hiddenDocIds(): Promise<Set<string>> {
  setLockedFolders(await listFolders());
  return new Set((await listDocs()).filter(isHidden).map((d) => d.id));
}

/** Asks for PIN (or fingerprint, or a backup's recovery code) if needed; true when the document may be opened. */
export async function ensureUnlocked(d: DocMeta): Promise<boolean> {
  setLockedFolders(await listFolders());
  if (d.sealed && !cachedKey(d.sealed.k)) {
    if (!(await obtainKey(d.sealed.k, d.title))) return false;
    unlocked.add(d.id);
    return true;
  }
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

// ---------------------------------------------------------------- encrypting and decrypting documents

/** One at a time: a document is never encrypted and decrypted by two runs at once. */
let queue: Promise<unknown> = Promise.resolve();
function serial<T>(fn: () => Promise<T>): Promise<T> {
  const run = queue.then(fn, fn);
  queue = run.catch(() => null);
  return run;
}

/** Encrypts a document that should be locked, decrypts one that no longer is – when the key needed is unlocked. */
async function reconcileDoc(d: DocMeta) {
  const own = load()?.keys?.[0]?.id;
  const want = !!own && lockEnabled() && wantsLock(d);
  if (want && !d.sealed && own && cachedKey(own)) await sealDoc(d, own);
  else if (!want && d.sealed && cachedKey(d.sealed.k)) await unsealDoc(d);
}

/** Brings every document in line with its lock (after a PIN was entered, a lock changed, or the lock was turned off). */
export function reconcileAll(): Promise<void> {
  return serial(async () => {
    setLockedFolders(await listFolders());
    for (const d of await listDocs()) await reconcileDoc(d);
  });
}

// a document saved anywhere (new notebook in a locked folder, moved into one, …) follows its lock at once
onDocSaved((d) => serial(() => reconcileDoc(d)));

// ---------------------------------------------------------------- keys

function keysReady(cfg: LockConfig): boolean {
  return !!cfg.keys?.length && cfg.keys.every((k) => cachedKey(k.id));
}

/** After the right PIN: opens the keys (or, the first time, makes one and shows its recovery code). */
async function openKeys(pin: string) {
  const cfg = load();
  if (!cfg) return;
  if (!cfg.keys?.length) { await createKey(pin); return; }
  for (const e of cfg.keys) {
    if (cachedKey(e.id)) continue;
    try {
      rememberKey(e.id, await importDataKey(await unwrapKey(e.pin, pin)));
    } catch {
      toast(t('keyBroken'));
    }
  }
  keysFresh();
}

async function createKey(pin: string) {
  const raw = newRawKey(), code = newRecoveryCode();
  const entry: KeyEntry = { id: newKeyId(), pin: await wrapKey(raw, pin, PIN_ITER), rec: await wrapKey(raw, code, REC_ITER) };
  const cfg = load();
  if (!cfg) return;
  save({ ...cfg, keys: [entry, ...(cfg.keys ?? [])] });
  rememberKey(entry.id, await importDataKey(raw));
  await showRecoveryCode(code, false);
}

/** The key `k` of a document: the PIN opens this device's keys, a restored backup's key needs that backup's recovery code. */
async function obtainKey(k: string, what: string): Promise<boolean> {
  const cfg = load();
  if (cfg?.keys?.some((e) => e.id === k)) {
    if ((await pinDialog(what)) === null) return false;
  } else {
    const pending = pendingKeys().find((e) => e.id === k);
    if (!pending) { toast(t('keyMissing')); return false; }
    const raw = await recoveryDialog(pending.rec, what);
    if (!raw) return false;
    rememberKey(k, await importDataKey(raw));
    // with a PIN on this device the key joins the others, so the PIN opens it from now on
    if (cfg) {
      const pin = await pinDialog(what);
      if (pin) {
        const now = load();
        if (now) save({ ...now, keys: [...(now.keys ?? []), { id: k, pin: await wrapKey(raw, pin, PIN_ITER), rec: pending.rec }] });
        savePending(pendingKeys().filter((e) => e.id !== k));
      }
    }
  }
  await reconcileAll();
  return !!cachedKey(k);
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

/**
 * Fingerprint (if turned on) or PIN. Resolves true when it was really you. The fingerprint cannot open an encryption
 * key, so while the keys are not unlocked in this visit the PIN is asked for.
 */
export async function verify(what: string): Promise<boolean> {
  const cfg = load();
  if (!cfg) return true;
  if (keysReady(cfg) && cfg.bio && (await biometricAvailable()) && (await fingerprint(what))) {
    save({ ...cfg, fails: 0, until: 0 });
    return true;
  }
  const ok = (await pinDialog(what)) !== null;
  if (ok) await reconcileAll();
  return ok;
}

function pinField(label: string) {
  const input = h('input', {
    type: 'password', inputmode: 'numeric', autocomplete: 'off', maxlength: 8, class: 'text pin-input', dir: 'ltr', 'aria-label': label,
  });
  return { input, row: h('label', { class: 'row col' }, h('span', {}, label), input) };
}

/** Asks for the PIN; on the right one opens the keys and resolves with the PIN, or null when cancelled. */
function pinDialog(what: string): Promise<string | null> {
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
    const finish = (v: string | null) => { if (!done) { done = true; resolve(v); if (dlg.open) dlg.close(); } };
    dlg.addEventListener('close', () => finish(null));
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
      if (btn?.value === 'cancel') return; // closes the dialog -> null
      e.preventDefault();
      const cfg = load();
      if (!cfg) return finish('');
      if ((cfg.until ?? 0) > Date.now() || ok.disabled) return;
      const pin = normalizePin(input.value);
      ok.disabled = true;
      if (pin && (await checkPin(pin, cfg.pin))) {
        save({ ...cfg, fails: 0, until: 0 });
        err.textContent = t('opening');
        await openKeys(pin);
        return finish(pin);
      }
      ok.disabled = false;
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

/** Choose a PIN (typed twice). Resolves with it, or null if cancelled. */
function choosePin(): Promise<string | null> {
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
    const finish = (v: string | null) => { if (!done) { done = true; resolve(v); if (dlg.open) dlg.close(); } };
    dlg.addEventListener('close', () => finish(null));
    form.addEventListener('submit', (e) => {
      const btn = (e as SubmitEvent).submitter as HTMLButtonElement | null;
      if (btn?.value === 'cancel') return;
      e.preventDefault();
      const p1 = normalizePin(a.input.value), p2 = normalizePin(b.input.value);
      if (!p1) { err.textContent = t('pinRule'); return; }
      if (p1 !== p2) { err.textContent = t('pinMismatch'); b.input.value = ''; b.input.focus(); return; }
      finish(p1);
    });
    requestAnimationFrame(() => a.input.focus());
  });
}

/** Choose the first PIN: makes the encryption key and shows its recovery code. Returns false if cancelled. */
export async function setupPin(): Promise<boolean> {
  const pin = await choosePin();
  if (!pin) return false;
  save({ pin: await makePin(pin), bio: load()?.bio ?? (await biometricAvailable()), fails: 0, until: 0, keys: load()?.keys });
  await openKeys(pin);
  return true;
}

/**
 * Shown once when a key is made (or renewed): the only way into locked documents without the PIN, and the only key
 * to locked documents in a backup. Stays until "written down" is ticked.
 */
async function showRecoveryCode(code: string, renewed: boolean): Promise<void> {
  for (;;) {
    const ack = h('input', { type: 'checkbox' });
    const ok = h('button', { type: 'submit', value: 'ok', class: 'primary', disabled: true }, t('done'));
    ack.addEventListener('change', () => { ok.disabled = !ack.checked; });
    const copy = h('button', { type: 'button' }, t('copy'));
    copy.addEventListener('click', () => void navigator.clipboard?.writeText(code).then(() => toast(t('copied'))).catch(() => {}));
    const mark = h('div', { class: 'lock-mark', 'aria-hidden': 'true' });
    mark.innerHTML = icons.lock;
    const form = h('form', { method: 'dialog', class: 'new-nb lock-dlg recovery-dlg' },
      mark, h('h2', {}, t('recoveryTitle')),
      h('p', { class: 'hint' }, t(renewed ? 'recoveryRenewedHint' : 'recoveryHint')),
      h('p', { class: 'recovery-code', dir: 'ltr' }, code),
      h('div', { class: 'actions start' }, copy),
      h('label', { class: 'row' }, h('span', {}, t('recoveryAck')), ack),
      h('div', { class: 'actions' }, ok),
    );
    const { dlg, closed } = modal(form);
    dlg.addEventListener('cancel', (e) => { if (!ack.checked) e.preventDefault(); });
    await closed;
    if (ack.checked) return;
  }
}

/** Asks for a backup's recovery code; resolves with the key it opens, or null when cancelled. */
function recoveryDialog(rec: Wrap, what: string): Promise<Uint8Array<ArrayBuffer> | null> {
  return new Promise((resolve) => {
    const input = h('input', { type: 'text', autocomplete: 'off', autocapitalize: 'characters', spellcheck: false, class: 'text recovery-input', dir: 'ltr', 'aria-label': t('recoveryTitle'), placeholder: 'XXXX-XXXX-…' });
    const err = h('p', { class: 'pin-err', role: 'alert' });
    const ok = h('button', { type: 'submit', value: 'ok', class: 'primary' }, t('unlock'));
    const mark = h('div', { class: 'lock-mark', 'aria-hidden': 'true' });
    mark.innerHTML = icons.lock;
    const form = h('form', { method: 'dialog', class: 'new-nb lock-dlg' },
      mark, h('h2', {}, t('enterRecovery')), h('p', { class: 'hint' }, what), h('p', { class: 'hint' }, t('enterRecoveryHint')),
      h('label', { class: 'row col' }, h('span', {}, t('recoveryTitle')), input), err,
      h('div', { class: 'actions' }, h('button', { type: 'submit', value: 'cancel', formnovalidate: true }, t('cancel')), ok),
    );
    const { dlg } = modal(form);
    let done = false;
    const finish = (v: Uint8Array<ArrayBuffer> | null) => { if (!done) { done = true; resolve(v); if (dlg.open) dlg.close(); } };
    dlg.addEventListener('close', () => finish(null));
    form.addEventListener('submit', async (e) => {
      const btn = (e as SubmitEvent).submitter as HTMLButtonElement | null;
      if (btn?.value === 'cancel') return;
      e.preventDefault();
      const code = normalizeRecoveryCode(input.value);
      if (!code) { err.textContent = t('wrongRecovery'); return; }
      ok.disabled = true;
      err.textContent = t('opening');
      try {
        finish(await unwrapKey(rec, code));
      } catch {
        ok.disabled = false;
        err.textContent = t('wrongRecovery');
      }
    });
    requestAnimationFrame(() => input.focus());
  });
}

// ---------------------------------------------------------------- lock / unlock items

/** Locks or unlocks a document (and encrypts or decrypts it). The first lock asks you to choose a PIN. */
export async function toggleDocLock(d: DocMeta): Promise<boolean> {
  if (d.locked && lockEnabled()) {
    if (!(await verify(d.title))) return false;
    delete d.locked;
  } else {
    if (!lockEnabled()) { if (!(await setupPin())) return false; }
    else if (!keysReady(load()!) && (await pinDialog(d.title)) === null) return false;
    d.locked = true;
    unlocked.delete(d.id);
  }
  await putDoc(d);
  await serial(() => reconcileDoc(d));
  return true;
}

export async function toggleFolderLock(f: Folder): Promise<boolean> {
  if (f.locked && lockEnabled()) {
    if (!(await verify(f.name))) return false;
    delete f.locked;
  } else {
    if (!lockEnabled()) { if (!(await setupPin())) return false; }
    else if (!keysReady(load()!) && (await pinDialog(f.name)) === null) return false;
    f.locked = true;
    unlocked.delete(f.id);
  }
  await putFolder(f);
  await reconcileAll();
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

/** New PIN: the keys are wrapped again with it (the documents themselves stay as they are). */
export async function changePin(): Promise<boolean> {
  const old = await pinDialog(t('changePin'));
  if (old === null) return false;
  const pin = await choosePin();
  if (!pin) return false;
  const cfg = load();
  if (!cfg) return false;
  const keys: KeyEntry[] = [];
  for (const e of cfg.keys ?? []) keys.push({ ...e, pin: await wrapKey(await unwrapKey(e.pin, old), pin, PIN_ITER) });
  save({ ...cfg, pin: await makePin(pin), keys, fails: 0, until: 0 });
  return true;
}

/** A new recovery code for this device's key (backups made before keep needing the old one). */
export async function renewRecoveryCode(): Promise<boolean> {
  const pin = await pinDialog(t('newRecoveryCode'));
  if (pin === null) return false;
  const cfg = load();
  const own = cfg?.keys?.[0];
  if (!cfg || !own) return false;
  const code = newRecoveryCode();
  const rec = await wrapKey(await unwrapKey(own.pin, pin), code, REC_ITER);
  save({ ...cfg, keys: [{ ...own, rec }, ...(cfg.keys ?? []).slice(1)] });
  await showRecoveryCode(code, true);
  return true;
}

/** Turns the whole feature off: checks it is you, decrypts every document it can, removes every lock and the PIN. */
export async function turnOffLock(): Promise<boolean> {
  if (!lockEnabled()) return true;
  if ((await pinDialog(t('lockOff'))) === null) return false;
  await serial(async () => {
    for (const f of await listFolders()) if (f.locked) { delete f.locked; await putFolder(f); }
    setLockedFolders([]);
    for (const d of await listDocs()) {
      if (d.locked) { delete d.locked; await putDoc(d); }
      if (d.sealed && cachedKey(d.sealed.k)) await unsealDoc(d);
    }
  });
  // a document still encrypted (a backup's key never opened here) keeps its way in: that backup's recovery code
  savePending([...pendingKeys(), ...backupKeys().filter((k) => !pendingKeys().some((p) => p.id === k.id))]);
  save(null);
  unlocked.clear();
  wipeKeys();
  return true;
}
