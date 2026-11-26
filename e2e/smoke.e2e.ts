/**
 * Smoke test of the real app in Chromium (the built web version, the same code the Android app runs).
 * It does not replace a test on the tablet, but it catches the app failing to start, a document that
 * cannot be drawn in, saved or reopened, and a backup that cannot be restored.
 *
 *   npm run build && npm run e2e
 */
import { test, expect, type Page } from '@playwright/test';
import { readFile, writeFile } from 'node:fs/promises';
import { PDFDocument, PDFName, PDFString } from 'pdf-lib';

/** Total number of strokes on every page, read straight from the app's database. */
const strokeCount = (page: Page) => page.evaluate(() => new Promise<number>((resolve, reject) => {
  const open = indexedDB.open('alnai-notes');
  open.onerror = () => reject(open.error);
  open.onsuccess = () => {
    const db = open.result;
    const q = db.transaction('pages').objectStore('pages').getAll();
    q.onsuccess = () => { resolve((q.result as { strokes: unknown[] }[]).reduce((n, p) => n + p.strokes.length, 0)); db.close(); };
  };
}));

/** One stroke across the middle of the first page – once the page is laid out (it may still be settling just after opening). */
async function draw(page: Page) {
  const sheet = page.locator('.page').first();
  await expect(sheet).toBeVisible();
  let box = await sheet.boundingBox();
  await expect.poll(async () => {
    const now = await sheet.boundingBox();
    const still = !!now && !!box && Math.abs(now.x - box.x) < 1 && Math.abs(now.y - box.y) < 1 && Math.abs(now.width - box.width) < 1;
    box = now;
    return still;
  }, { message: 'page stops moving', intervals: [100] }).toBe(true);
  const x = box!.x + box!.width * 0.3, y = box!.y + Math.min(box!.height, 600) * 0.4;
  await page.mouse.move(x, y);
  await page.mouse.down();
  for (let i = 1; i <= 12; i++) await page.mouse.move(x + i * 15, y + i * 5);
  await page.mouse.up();
}

async function newNotebook(page: Page) {
  await page.getByRole('button', { name: 'New notebook' }).click();
  await page.locator('dialog[open] button[value=create]').click();
  await expect(page.getByRole('button', { name: 'Undo' })).toBeVisible();
}

/** Leaves the document; answers "Save" if the app asks. */
async function back(page: Page) {
  await page.getByRole('button', { name: 'Back' }).click();
  const save = page.locator('dialog[open] button[value=save]');
  if (await save.isVisible({ timeout: 1000 }).catch(() => false)) {
    // the main button keeps its coloured background (white text on the plain one could not be read)
    expect(await save.evaluate((e) => getComputedStyle(e).backgroundImage)).toContain('gradient');
    await save.click();
  }
  await expect(page.getByRole('button', { name: 'New notebook' })).toBeVisible();
}

/** uncaught errors in each test's page */
const pageErrors = new WeakMap<Page, string[]>();

test.beforeEach(async ({ page }) => {
  const errors: string[] = [];
  pageErrors.set(page, errors);
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('dialog', (d) => void d.accept()); // confirm() for delete and restore
  // English labels, so the test reads the same buttons whatever the default language
  await page.addInitScript(() => { if (!localStorage.getItem('alnai-settings')) localStorage.setItem('alnai-settings', JSON.stringify({ lang: 'en' })); });
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'New notebook' })).toBeVisible();
});

test.afterEach(async ({ page }) => {
  expect(pageErrors.get(page), 'uncaught errors in the page').toEqual([]);
});

test('write in a notebook, undo, redo, leave and open it again', async ({ page }) => {
  await newNotebook(page);
  await draw(page);
  await expect.poll(() => strokeCount(page)).toBe(1);
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect.poll(() => strokeCount(page)).toBe(0);
  await page.getByRole('button', { name: 'Redo' }).click();
  await expect.poll(() => strokeCount(page)).toBe(1);

  await back(page);
  const card = page.locator('.card-title', { hasText: 'Untitled notebook' });
  await expect(card).toBeVisible();
  await card.click();
  await expect(page.getByRole('button', { name: 'Undo' })).toBeVisible();
  expect(await strokeCount(page)).toBe(1);
});

test('settings open and show the backup section', async ({ page }) => {
  await page.getByRole('button', { name: 'Settings' }).click();
  await expect(page.getByRole('button', { name: 'Create backup' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Restore backup' })).toBeVisible();
});

test('a PDF opens, takes ink, and exports with it as a PDF', async ({ page }, info) => {
  // pdf.js and pdf-lib are loaded only when needed: watch that they really arrive
  const scripts: string[] = [];
  page.on('request', (r) => { if (r.resourceType() === 'script') scripts.push(new URL(r.url()).pathname); });

  const src = await PDFDocument.create();
  for (let i = 0; i < 2; i++) src.addPage([595, 842]).drawText(`Lungenfunktion ${i + 1}`, { x: 60, y: 760, size: 24 });
  const file = info.outputPath('Lecture.pdf');
  await writeFile(file, await src.save());

  // one imported file opens straight away, in reading mode
  await page.locator('input[type=file][multiple]').first().setInputFiles(file);
  const reading = page.getByRole('button', { name: 'Reading mode' });
  await expect(reading).toBeVisible();
  await expect(page.locator('.page-label')).toHaveText('1 / 2');
  await expect.poll(() => scripts.some((s) => /\/assets\/pdf-[^/]+\.js$/.test(s)), { message: 'pdf.js loaded' }).toBe(true);

  // reading mode lets nothing draw: switch it off, then write on the page
  await reading.click();
  await expect(page.getByRole('button', { name: 'Undo' })).toBeVisible();
  await draw(page);
  await expect.poll(() => strokeCount(page)).toBe(1);

  await page.getByRole('button', { name: 'Share / convert' }).click();
  const download = page.waitForEvent('download');
  await page.locator('dialog[open] button[value=pdf]').click();
  const out = await readFile(await (await download).path());
  expect(out.subarray(0, 5).toString()).toBe('%PDF-');
  expect((await PDFDocument.load(out)).getPageCount()).toBe(2);
});

test('a backup brings back a deleted notebook', async ({ page }) => {
  await newNotebook(page);
  await draw(page);
  await expect.poll(() => strokeCount(page)).toBe(1);
  await back(page);

  // back up (the web version downloads the file)
  await page.getByRole('button', { name: 'Settings' }).click();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Create backup' }).click();
  const file = await (await download).path();
  await page.locator('dialog[open] button[value=ok]').click();

  // delete the notebook
  await page.locator('.card .more').first().click();
  await page.locator('dialog[open] button[value=delete]').click();
  await expect(page.locator('.card-title', { hasText: 'Untitled notebook' })).toHaveCount(0);
  expect(await strokeCount(page)).toBe(0);

  // restore: the app reloads with the notebook back
  await page.getByRole('button', { name: 'Settings' }).click();
  const reload = page.waitForEvent('load');
  await page.locator('dialog[open] input[type=file]').setInputFiles(file);
  await reload;
  await expect(page.locator('.card-title', { hasText: 'Untitled notebook' })).toBeVisible();
  expect(await strokeCount(page)).toBe(1);
});

test.describe('on a phone', () => {
  test.use({ viewport: { width: 360, height: 780 }, hasTouch: true, isMobile: true });

  test('every editor button stays on screen (tool bar wraps, undo/redo reachable)', async ({ page }) => {
    await newNotebook(page);
    const outside = await page.evaluate(() => [...document.querySelectorAll('.topbar button, .dock button, .nav-pill button')]
      .filter((b) => (b as HTMLElement).offsetParent)
      .filter((b) => { const r = b.getBoundingClientRect(); return r.left < -1 || r.right > innerWidth + 1; })
      .map((b) => b.getAttribute('aria-label') ?? b.className));
    expect(outside).toEqual([]);
    await expect(page.getByRole('button', { name: 'Undo' })).toBeInViewport();
    await expect(page.getByRole('button', { name: 'Redo' })).toBeInViewport();
  });
});

// ---------------------------------------------------------------- locked documents are encrypted

const SECRET = 'Spirometrie-Patient-Vertraulich';
const PIN = '482916';

/** What is stored for the document titled `title`, read straight from the database (no app code involved). */
const rawState = (page: Page, title: string, secret: string) => page.evaluate(([title, secret]) => new Promise<{
  sealed: boolean; fileSealed: boolean; fileHasSecret: boolean; pagesSealed: boolean; anyStoreHasSecret: boolean;
}>((resolve) => {
  const open = indexedDB.open('alnai-notes');
  open.onsuccess = () => {
    const db = open.result;
    const tx = db.transaction(['docs', 'pages', 'files', 'clips', 'text']);
    const all = (s: string) => new Promise<unknown[]>((r) => { const q = tx.objectStore(s).getAll(); q.onsuccess = () => r(q.result); });
    void Promise.all([all('docs'), all('pages'), all('files'), all('clips'), all('text')]).then(([docs, pages, files, clips, text]) => {
      const doc = (docs as { id: string; title: string; sealed?: unknown; pageIds: string[] }[]).find((d) => d.title === title)!;
      const pageRecs = (pages as { id: string; sealed?: unknown; strokes?: unknown }[]).filter((p) => doc.pageIds.includes(p.id));
      const fileReq = tx.objectStore('files').get(doc.id);
      fileReq.onsuccess = () => {
        const bytes = new Uint8Array(fileReq.result as ArrayBuffer);
        const latin = (b: Uint8Array) => Array.from(b, (c) => String.fromCharCode(c)).join('');
        const everything = latin(bytes) + JSON.stringify([docs, pages, clips, text]) + (files as ArrayBuffer[]).map((f) => latin(new Uint8Array(f))).join('');
        resolve({
          sealed: !!doc.sealed,
          fileSealed: latin(bytes.subarray(0, 7)) === 'NVSEAL1',
          fileHasSecret: latin(bytes).includes(secret),
          pagesSealed: pageRecs.length > 0 && pageRecs.every((p) => !!p.sealed && p.strokes === undefined),
          anyStoreHasSecret: everything.includes(secret),
        });
        db.close();
      };
    });
  };
}), [title, secret] as const);

async function importSecretPdf(page: Page, info: { outputPath: (n: string) => string }) {
  const src = await PDFDocument.create();
  for (let i = 0; i < 2; i++) src.addPage([595, 842]).drawText(`${SECRET} ${i + 1}`, { x: 60, y: 760, size: 18 });
  // page text is written hex-encoded; a literal string in the catalog keeps the secret readable in the file itself
  src.catalog.set(PDFName.of('NotivaTestSecret'), PDFString.of(SECRET));
  const bytes = await src.save({ useObjectStreams: false });
  expect(Buffer.from(bytes).toString('latin1'), 'the test PDF has its secret in plain text').toContain(SECRET);
  const file = info.outputPath('Befund.pdf');
  await writeFile(file, bytes);
  await page.locator('input[type=file][multiple]').first().setInputFiles(file);
  await expect(page.getByRole('button', { name: 'Reading mode' })).toBeVisible();
  await back(page);
}

/** Locks the document from its menu; the first time this chooses the PIN and returns the recovery code shown. */
async function lockFromMenu(page: Page, title: string): Promise<string | null> {
  await page.locator('.card', { has: page.locator('.card-title', { hasText: title }) }).locator('.more').click();
  await page.locator('dialog[open] button[value=lock]').click();
  const pins = page.locator('dialog[open] .pin-input');
  await expect(pins.first()).toBeVisible();
  if (await pins.count() === 2) {
    await pins.nth(0).fill(PIN);
    await pins.nth(1).fill(PIN);
    await page.locator('dialog[open] button[value=ok]').click();
    const code = (await page.locator('dialog[open] .recovery-code').textContent())!.trim();
    const done = page.locator('dialog[open] button[value=ok]');
    await expect(done, 'the code must be confirmed as written down first').toBeDisabled();
    await page.locator('dialog[open] input[type=checkbox]').check();
    await done.click();
    return code;
  }
  return null;
}

async function enterPin(page: Page, pin: string) {
  await page.locator('dialog[open] .pin-input').fill(pin);
  await page.locator('dialog[open] button[value=ok]').click();
}

test.describe('locked documents', () => {
  test('are encrypted at rest and in backups, need the PIN, and a backup opens elsewhere only with the recovery code', async ({ page, browser }, info) => {
    await importSecretPdf(page, info);
    expect((await rawState(page, 'Befund', SECRET)).fileHasSecret, 'before locking the PDF is stored as it is').toBe(true);

    const code = await lockFromMenu(page, 'Befund');
    expect(code).toMatch(/^[0-9A-Z]{4}(-[0-9A-Z]{4}){7}$/);
    await expect.poll(async () => (await rawState(page, 'Befund', SECRET)).fileSealed).toBe(true);
    const st = await rawState(page, 'Befund', SECRET);
    expect(st).toMatchObject({ sealed: true, fileSealed: true, fileHasSecret: false, pagesSealed: true, anyStoreHasSecret: false });

    // a restart forgets the key: the PIN is needed, a wrong one does not open
    await page.reload();
    await page.locator('.card-title', { hasText: 'Befund' }).click();
    await enterPin(page, '000000');
    await expect(page.locator('dialog[open] .pin-err')).toHaveText('Wrong PIN');
    await enterPin(page, PIN);
    await expect(page.getByRole('button', { name: 'Reading mode' })).toBeVisible();
    await expect(page.locator('.page-label')).toHaveText('1 / 2');
    await back(page);

    // the backup carries the document encrypted
    await page.getByRole('button', { name: 'Settings' }).click();
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Create backup' }).click();
    const backupFile = await (await download).path();
    await page.locator('dialog[open] button[value=ok]').click();
    const backupBytes = await readFile(backupFile);
    expect(backupBytes.toString('latin1').includes(SECRET), 'the secret must not be readable in the backup').toBe(false);

    // another device (empty browser): restore, then only the recovery code opens it
    const other = await browser.newContext({ viewport: { width: 1280, height: 800 }, acceptDownloads: true });
    const p2 = await other.newPage();
    const errors: string[] = [];
    p2.on('pageerror', (e) => errors.push(e.message));
    p2.on('dialog', (d) => void d.accept());
    await p2.addInitScript(() => { if (!localStorage.getItem('alnai-settings')) localStorage.setItem('alnai-settings', JSON.stringify({ lang: 'en' })); });
    await p2.goto('/');
    await p2.getByRole('button', { name: 'Settings' }).click();
    const reload = p2.waitForEvent('load');
    await p2.locator('dialog[open] input[type=file]').setInputFiles(backupFile);
    await reload;
    expect((await rawState(p2, 'Befund', SECRET)).anyStoreHasSecret, 'restored still encrypted').toBe(false);
    await p2.locator('.card-title', { hasText: 'Befund' }).click();
    await p2.locator('dialog[open] .recovery-input').fill('0000-0000-0000-0000-0000-0000-0000-0000');
    await p2.locator('dialog[open] button[value=ok]').click();
    await expect(p2.locator('dialog[open] .pin-err')).toHaveText('Wrong recovery code');
    await p2.locator('dialog[open] .recovery-input').fill(code!.toLowerCase().replace(/-/g, ' '));
    await p2.locator('dialog[open] button[value=ok]').click();
    await expect(p2.getByRole('button', { name: 'Reading mode' })).toBeVisible();
    await expect(p2.locator('.page-label')).toHaveText('1 / 2');
    expect(errors).toEqual([]);
    await other.close();
  });

  test('removing the lock decrypts the document again', async ({ page }, info) => {
    await importSecretPdf(page, info);
    await lockFromMenu(page, 'Befund');
    await expect.poll(async () => (await rawState(page, 'Befund', SECRET)).fileSealed).toBe(true);
    await page.reload();
    // unlock from the menu: asks for the PIN, then stores the document plain again
    await page.locator('.card', { has: page.locator('.card-title', { hasText: 'Befund' }) }).locator('.more').click();
    await page.locator('dialog[open] button[value=lock]').click();
    await enterPin(page, PIN);
    await expect.poll(async () => (await rawState(page, 'Befund', SECRET)).sealed).toBe(false);
    expect(await rawState(page, 'Befund', SECRET)).toMatchObject({ fileSealed: false, fileHasSecret: true, pagesSealed: false });
    await page.locator('.card-title', { hasText: 'Befund' }).click();
    await expect(page.getByRole('button', { name: 'Reading mode' })).toBeVisible(); // no PIN asked any more
  });
});
