import { expect, type Page } from '@playwright/test';

/**
 * Does this page scroll sideways?
 *
 * Measured, not looked at — the phase-10 lesson (T26). That sweep found a page-widening `sr-only`
 * label, which nothing about the rendering looked wrong about and no screenshot could have shown.
 */
export interface Overflow {
  scrollWidth: number;
  clientWidth: number;
  /** The widest elements, so a failure names the thing to fix and not only the symptom. */
  widest: Array<{ selector: string; right: number }>;
}

export async function measureOverflow(page: Page): Promise<Overflow> {
  return page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
    widest: Array.from(document.querySelectorAll('*'))
      .map((element) => ({
        selector:
          element.tagName.toLowerCase() +
          (element.className && typeof element.className === 'string'
            ? `.${element.className.split(/\s+/).filter(Boolean).slice(0, 3).join('.')}`
            : ''),
        right: Math.round(element.getBoundingClientRect().right),
      }))
      .sort((a, b) => b.right - a.right)
      .slice(0, 3),
  }));
}

/**
 * Waits for the page to be worth measuring, then measures it.
 *
 * Three conditions, and each rules out a way the sweep could go green having measured nothing:
 *
 * - **The URL is still the route asked for.** A guard that bounced the visitor to `/login` would
 *   otherwise have the sweep measure the sign-in form eighteen times and report eighteen passes —
 *   the shape of T52, where six probes agreed about a page that was not the one under test.
 * - **Something rendered.** A blank body cannot overflow, so a route that failed to render at all
 *   is the easiest possible pass.
 * - **Nothing is still loading.** A screen measured mid-spinner is a screen whose widest element
 *   has not been drawn yet — the table, which is the thing most likely to be too wide.
 *
 * Returns what it measured, so the caller can put the numbers in the log. A sweep whose only output
 * is a green tick cannot be told apart from a sweep that visited nothing — which is exactly the
 * question its first CI run raised, at 3.9s for twenty-one routes.
 */
export async function expectNoSidewaysScroll(
  page: Page,
  route: string,
  /**
   * Where this route is *expected* to end up, when that is not itself — `/settings` is a section
   * rather than a screen and redirects to its first page. Written down rather than worked around,
   * so the check still catches a bounce nobody intended.
   */
  lands = route,
): Promise<Overflow> {
  await page.goto(route);

  await page.waitForURL((url) => new URL(url).pathname === lands, { timeout: 30_000 });

  await expect(page.locator('h1, h2').first(), `${route} rendered no heading`).toBeVisible({
    timeout: 30_000,
  });
  await expect(
    page.getByRole('status', { name: 'Loading' }),
    `${route} was still loading`,
  ).toHaveCount(0, { timeout: 30_000 });

  const overflow = await measureOverflow(page);

  expect(
    overflow.scrollWidth,
    `${route} is wider than the viewport (${overflow.scrollWidth} > ${overflow.clientWidth}). Widest: ${JSON.stringify(overflow.widest)}`,
  ).toBeLessThanOrEqual(overflow.clientWidth);

  return overflow;
}
