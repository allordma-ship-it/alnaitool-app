/**
 * Smoke test of the real app in Chromium (the built web version, the same code the Android app runs).
 * It does not replace a test on the tablet, but it catches the app failing to start, a document that
 * cannot be drawn in, saved or reopened, and a backup that cannot be restored.
 *
 *   npm run build && npm run e2e
 */
import { test, expect, type Page } from '@playwright/test';

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

async function draw(page: Page) {
  await page.mouse.move(560, 380);
  await page.mouse.down();
  for (let i = 1; i <= 12; i++) await page.mouse.move(560 + i * 20, 380 + i * 6);
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
  if (await save.isVisible({ timeout: 1000 }).catch(() => false)) await save.click();
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
