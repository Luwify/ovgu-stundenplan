import { defineConfig, devices } from '@playwright/test'

// Runs against the production build (vite preview) in the official Playwright container (see scripts/e2e.sh).
// WebKit is the closest engine to iPadOS Safari; the iPad profiles cover landscape (the main view), portrait and the
// narrow windows of Split View / Stage Manager. Chromium covers the service worker update and offline paths, which
// Playwright can only drive there.
const common = { locale: 'de-DE', timezoneId: 'Europe/Berlin' }

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  fullyParallel: true,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:4173', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: 'npx vite preview --port 4173 --strictPort --host 127.0.0.1', url: 'http://127.0.0.1:4173', reuseExistingServer: false },
  projects: [
    { name: 'ipad-gen7-landscape', use: { ...devices['iPad (gen 7) landscape'], ...common } },
    { name: 'ipad-gen7-portrait', use: { ...devices['iPad (gen 7)'], ...common } },
    { name: 'ipad-pro11-landscape', use: { ...devices['iPad Pro 11 landscape'], ...common } },
    { name: 'ipad-pro11-portrait', use: { ...devices['iPad Pro 11'], ...common } },
    // Split View: a third (~375 px) and half (~597 px) of an iPad Pro 11 in landscape.
    { name: 'ipad-splitview-third', use: { ...devices['iPad Pro 11 landscape'], viewport: { width: 375, height: 834 }, ...common } },
    { name: 'ipad-splitview-half', use: { ...devices['iPad Pro 11 landscape'], viewport: { width: 597, height: 834 }, ...common } },
    { name: 'webkit-phone', use: { ...devices['iPhone 13'], ...common } },
    { name: 'chromium-phone', use: { ...devices['Pixel 7'], ...common } },
  ],
})
