import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.mjs',
  timeout: 90_000,
  expect: { timeout: 30_000 },
  workers: 1,
  retries: 0,
  reporter: [['list'], ['junit', { outputFile: 'results/junit.xml' }]],
  outputDir: 'results/browser',
  use: {
    browserName: 'chromium',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    launchOptions: { args: ['--unsafely-treat-insecure-origin-as-secure=http://web,http://hosted-web'] },
  },
});
