import { defineConfig, devices } from '@playwright/test';

// Smoke test of the built app in Chromium: `npm run build && npm run e2e`.
export default defineConfig({
  testDir: 'e2e',
  testMatch: '*.e2e.ts',
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://localhost:4173',
    // a tablet-sized window
    viewport: { width: 1280, height: 800 },
    acceptDownloads: true,
    trace: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } } }],
  webServer: {
    command: 'npx vite preview --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
  },
});
