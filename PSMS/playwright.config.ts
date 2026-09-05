import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/globalSetup.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
  },
});
