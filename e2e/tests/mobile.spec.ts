import { test, expect } from '@playwright/test';
import { freshTenant, registerBusiness } from './support';

/**
 * The 360 px run of the public page — docs/08-testing-strategy.md §8, last line.
 *
 * It measures `scrollWidth` rather than looking at a screenshot, which is the phase-10 lesson
 * (T26): the sweep that found a page-widening `sr-only` label found it by measurement, because
 * nothing about the rendering looked wrong.
 *
 * This registers its own tenant rather than reusing the flow's. The two projects can run in either
 * order and `fullyParallel` is off, so sharing one would make this depend on a test it cannot see.
 */
test('the public booking page does not scroll sideways at 360 px', async ({ page }) => {
  const tenant = freshTenant();
  const slug = await registerBusiness(page, tenant);

  await page.goto(`/book/${slug}`);
  await expect(page.getByRole('heading', { name: tenant.businessName })).toBeVisible({
    timeout: 30_000,
  });

  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
    // The widest element, so a failure names the thing to fix rather than only the symptom.
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

  expect(
    overflow.scrollWidth,
    `the page is wider than the viewport. Widest elements: ${JSON.stringify(overflow.widest)}`,
  ).toBeLessThanOrEqual(overflow.clientWidth);
});
