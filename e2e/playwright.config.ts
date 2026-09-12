import { defineConfig, devices } from '@playwright/test';

/**
 * The single end-to-end flow — docs/08-testing-strategy.md §8.
 *
 * It runs against a system that is already up (`make up-e2e`), deliberately: the topology is five
 * containers and a database, Playwright's `webServer` cannot express that, and a flow that built
 * its own stack would be testing a stack nothing else uses. ADR-0011's fake provider is what makes
 * the Receptionist leg deterministic.
 *
 * The origin is 9180, not 9080 — `make up-e2e` runs in its own compose project with its own
 * database, so running this never touches development data.
 */
export default defineConfig({
  testDir: './tests',
  // One flow that must hold together end to end; parallel workers would race for the same tenant.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  // Five minutes, and the reason is one line in application.yml: `poll-interval-ms: 60000`. The
  // outbox poller is what turns a booking into an email, its interval is hardcoded rather than
  // configurable, and this flow waits for two of those emails — so a minute or more of the run is
  // the system behaving correctly rather than anything being slow. At 180s the budget was spent
  // before the dashboard steps began.
  timeout: 300_000,
  expect: { timeout: 15_000 },
  // Zero. A flow that passes on the second attempt is a flow nobody can trust, and this one books
  // real rows: a retry would run against a tenant the first attempt already changed.
  retries: 0,
  reporter: process.env.CI ? [['github'], ['list']] : [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:9180',
    // The full Chromium build rather than the separate headless-shell binary: one download
    // instead of two, and this flow is partly about what a real browser does — it renders pages
    // under the shipped Content-Security-Policy, which G21 says has never been exercised by one.
    //
    // Overridable because a half-finished download leaves a Chromium that launches and then dies
    // on a missing framework, and `playwright install --dry-run` still calls it installed. On a
    // machine with Google Chrome, E2E_BROWSER_CHANNEL=chrome runs the flow without the download.
    // CI leaves it unset and uses the pinned build, so what CI proves does not drift with
    // whatever browser happens to be on a developer's laptop.
    channel: process.env.E2E_BROWSER_CHANNEL ?? 'chromium',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    {
      name: 'desktop',
      // Without this the mobile spec runs here too, at desktop width, where it asserts nothing:
      // a 360 px check passing in a 1280 px viewport is a green tick for a question never asked.
      testIgnore: /mobile\.spec\.ts/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'mobile',
      testMatch: /mobile\.spec\.ts/,
      // 360 px: the width docs/09-phase-plan.md rule 7 names, and the one phase 10 could only
      // measure by hand.
      use: { ...devices['Pixel 5'], viewport: { width: 360, height: 800 } },
    },
  ],
});
