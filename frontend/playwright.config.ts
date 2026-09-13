import { defineConfig } from '@playwright/test';

/**
 * Playwright configuration for the isolated admin media acceptance journey.
 *
 * `scripts/run_media_acceptance.py` starts the disposable Compose project, the real media gateway
 * bundle and the fixtures, then launches this config with MEDIA_ACCEPTANCE_* variables. The report
 * file is supplied through PLAYWRIGHT_JSON_OUTPUT_NAME by the same runner.
 *
 * H.264 playback requires a branded browser: Playwright's bundled Chromium ships without
 * proprietary codecs, so the runner selects the installed Google Chrome or Microsoft Edge channel
 * (`MEDIA_ACCEPTANCE_BROWSER_CHANNEL`) and the API decodes the protected HLS with it.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 600_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  outputDir: 'test-results',
  use: {
    baseURL: process.env.MEDIA_ACCEPTANCE_BASE_URL ?? 'http://127.0.0.1:18081',
    channel: (process.env.MEDIA_ACCEPTANCE_BROWSER_CHANNEL ?? 'chromium') as 'chromium' | 'chrome' | 'msedge',
    headless: true,
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    video: 'off',
    launchOptions: {
      args: ['--autoplay-policy=no-user-gesture-required'],
    },
  },
});
