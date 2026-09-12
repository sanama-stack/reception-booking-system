import { test, expect } from '@playwright/test';
import { expectNoSidewaysScroll, measureOverflow } from './overflow';
import { freshTenant, registerBusiness } from './support';

/**
 * The 360 px sweep — docs/08-testing-strategy.md §8's last line, and phase 11's row asking for the
 * hand-run phase-10 sweep to be *asserted* rather than eyeballed.
 *
 * **It lives here rather than in the frontend's Vitest suite, and that is a measurement not a
 * preference.** jsdom has no layout engine: a `div` explicitly 1200 px wide reports `scrollWidth`,
 * `offsetWidth` and `clientWidth` of 0, so the natural assertion there reads `0 <= 0` and passes
 * for every page forever. That is a vacuous assertion of exactly the kind this project has twice
 * paid for (T49, and the mark-completed step that was a no-op for as long as the flow existed).
 * A real browser is the only thing that can answer this question, and the `mobile` project is
 * where one already runs at 360 px.
 *
 * This registers its own tenant rather than reusing the flow's. The two projects can run in either
 * order and `fullyParallel` is off, so sharing one would make this depend on a test it cannot see.
 */

/** Signed out. `GuestGuard` keeps a signed-in visitor off the last two, so they are swept first. */
const PUBLIC_ROUTES = ['/', '/login', '/register'];

/**
 * Everything behind the sign-in that needs no id in its path.
 *
 * The detail routes are deliberately absent: each needs a row this tenant would have to create
 * first, and the sweep's value is breadth. `/book/{slug}` is swept separately because its path is
 * only known once the business exists.
 */
const DASHBOARD_ROUTES: Array<{ route: string; lands?: string }> = [
  { route: '/dashboard' },
  { route: '/appointments' },
  { route: '/appointments/new' },
  { route: '/calendar' },
  { route: '/conversations' },
  { route: '/customers' },
  { route: '/employees' },
  { route: '/employees/new' },
  { route: '/services' },
  { route: '/services/new' },
  // A section, not a screen: it redirects to its first page, and the sweep says so rather than
  // being surprised by it.
  { route: '/settings', lands: '/settings/profile' },
  { route: '/settings/profile' },
  { route: '/settings/hours' },
  { route: '/settings/booking' },
  { route: '/settings/closures' },
  { route: '/settings/faqs' },
  { route: '/analytics' },
];

test('no screen scrolls sideways at 360 px', async ({ page }) => {
  /**
   * Every route measured, with its numbers, printed at the end.
   *
   * **Its first CI run passed in 3.9 seconds** — for a registration and twenty-one page loads — and
   * nothing in the log could say whether it had swept anything, because the `list` reporter prints
   * the test and not its steps. A green tick is not evidence that a sweep swept. These lines are.
   */
  const measured: Array<{ route: string; scrollWidth: number; clientWidth: number }> = [];

  function record(route: string, overflow: { scrollWidth: number; clientWidth: number }) {
    measured.push({ route, scrollWidth: overflow.scrollWidth, clientWidth: overflow.clientWidth });
  }

  /**
   * The measurement, proven live before it is trusted.
   *
   * Every assertion below is an *absence* — nothing was too wide — and an absence is what a broken
   * measurement also reports. So the first step makes something too wide on purpose and requires
   * the measurement to notice. If this ever stops failing, every pass after it means nothing, and
   * the sweep says so here rather than being believed for another year.
   */
  await test.step('the measurement notices an element that is too wide', async () => {
    await page.goto('/');
    const before = await measureOverflow(page);
    expect(before.scrollWidth, 'the landing page is already too wide').toBeLessThanOrEqual(
      before.clientWidth,
    );

    await page.evaluate(() => {
      const plant = document.createElement('div');
      plant.id = 'overflow-plant';
      plant.style.width = '900px';
      plant.style.height = '1px';
      document.body.appendChild(plant);
    });

    const planted = await measureOverflow(page);
    expect(
      planted.scrollWidth,
      'a 900px element was added and the page did not get wider — this sweep measures nothing',
    ).toBeGreaterThan(planted.clientWidth);

    await page.evaluate(() => document.getElementById('overflow-plant')?.remove());
  });

  for (const route of PUBLIC_ROUTES) {
    await test.step(`signed out: ${route}`, async () =>
      record(route, await expectNoSidewaysScroll(page, route)));
  }

  const tenant = freshTenant();
  const slug = await registerBusiness(page, tenant);

  for (const { route, lands } of DASHBOARD_ROUTES) {
    await test.step(`signed in: ${route}`, async () =>
      record(route, await expectNoSidewaysScroll(page, route, lands)));
  }

  await test.step(`public booking page: /book/${slug}`, async () => {
    await page.goto(`/book/${slug}`);
    await expect(page.getByRole('heading', { name: tenant.businessName })).toBeVisible({
      timeout: 30_000,
    });

    const overflow = await measureOverflow(page);
    expect(
      overflow.scrollWidth,
      `the booking page is wider than the viewport. Widest: ${JSON.stringify(overflow.widest)}`,
    ).toBeLessThanOrEqual(overflow.clientWidth);
    record(`/book/${slug}`, overflow);
  });

  // The count is asserted, not just printed. A loop that silently visited nothing would otherwise
  // produce an empty table and a green tick.
  const expected = PUBLIC_ROUTES.length + DASHBOARD_ROUTES.length + 1;
  expect(measured.length, 'routes measured').toBe(expected);

  // eslint-disable-next-line no-console -- this is the evidence the run leaves behind
  console.log(
    `\n360px sweep — ${measured.length} routes measured at ${measured[0]?.clientWidth}px:\n` +
      measured
        .map(({ route, scrollWidth, clientWidth }) => `  ${scrollWidth}/${clientWidth}  ${route}`)
        .join('\n'),
  );
});
