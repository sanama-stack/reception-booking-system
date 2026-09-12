import { test, expect } from '@playwright/test';
import {
  freshTenant,
  registerBusiness,
  signIn,
  waitForMail,
  manageLinkFrom,
  RECEPTIONIST_CUSTOMER,
} from './support';

/**
 * The single end-to-end flow — docs/08-testing-strategy.md §8.
 *
 * One test, not eight. Every step depends on the state the one before it left, and splitting them
 * would either re-run the setup eight times or leave seven tests that cannot be run on their own
 * anyway. `test.step` is what makes the output readable.
 *
 * The Receptionist leg runs against the fake provider (ADR-0011), so it is deterministic and calls
 * no model. Everything else is the application exactly as it ships.
 *
 * **Every getByText here ends in `.first()`, deliberately.** getByText matches on substrings and on
 * ancestors, so a bare one resolves to several elements more often than not — and a strict-mode
 * violation THROWS rather than retrying, which silently cancels the very wait that was supposed to
 * let the page settle. Three separate rounds of this flow failed that way while the application was
 * working correctly underneath. Where identity actually matters the assertion is a role and an
 * accessible name instead, which is unambiguous by construction.
 */
test('a business is configured, booked twice, managed, cancelled and reported on', async ({
  page,
  request,
}) => {
  const tenant = freshTenant();
  let slug = '';
  let classicCode = '';
  let receptionistCode = '';

  await test.step('register an owner', async () => {
    slug = await registerBusiness(page, tenant);
    // The dashboard's heading greets the owner; the business name is a paragraph and a <dd>, not a
    // heading. It IS a heading on /book/{slug}, which is what made the first version of this line
    // look reasonable and fail here.
    await expect(page.getByRole('heading', { name: /^Welcome, / })).toBeVisible();
    await expect(page.getByText(tenant.businessName).first()).toBeVisible();
  });

  await test.step('add an employee and give them a working schedule', async () => {
    await page.goto('/employees/new');
    await page.getByLabel('Full name').fill(tenant.employeeName);
    await page.getByRole('button', { name: 'Add employee' }).click();

    // The detail page is where a schedule is set, and registration leaves every day closed.
    await page.waitForURL(/\/employees\/[0-9a-f-]{36}$/i, { timeout: 30_000 });

    // One day opened, then copied across — the control's own default interval is 09:00-17:00,
    // which is also BusinessDefaults' opening hours, so nothing has to be typed.
    await page.getByRole('button', { name: 'Add a working day' }).first().click();
    await page.getByRole('button', { name: 'Copy to all days' }).first().click();
    await page.getByRole('button', { name: 'Save working schedule' }).click();
    await expect(page.getByText(/working schedule.*saved/i).first()).toBeVisible();
  });

  await test.step('add a service the employee can perform', async () => {
    await page.goto('/services/new');
    await page.getByLabel('Name').fill(tenant.serviceName);
    await page.getByLabel('Length (minutes)').fill('30');
    await page.getByLabel(/^Price/).fill('40');
    // Assigned here rather than afterwards: a service nobody can perform cannot be booked, and the
    // public page would show an empty grid instead of failing usefully.
    await page.getByText(tenant.employeeName).first().click();
    await page.getByRole('button', { name: 'Add service' }).click();
    // The detail page, not back to the list: the form routes to /services/{id} on both its success
    // path and its "created, but the assignment failed" path.
    await page.waitForURL(/\/services\/[0-9a-f-]{36}$/i, { timeout: 30_000 });
    await expect(page.getByText(tenant.serviceName).first()).toBeVisible();
    // The assignment is what makes the service bookable, and it is saved by a second request that
    // can fail on its own — leaving a service that exists and cannot be booked. Assert it rather
    // than assume it, because the public page would otherwise just show an empty grid later.
    await expect(page.getByText(tenant.employeeName).first()).toBeVisible();
  });

  await test.step('a stranger books through the Classic Flow', async () => {
    // A fresh context: the owner's session must have nothing to do with this.
    const publicPage = await page.context().browser()!.newContext();
    const book = await publicPage.newPage();
    await book.goto(`${test.info().project.use.baseURL}/book/${slug}`);

    await book.getByRole('button', { name: new RegExp(tenant.serviceName) }).click();

    // The day strip sets an explicit aria-label — "<date>, N times" — so the accessible name is
    // never the visible "Tue 9". Requiring a DIGIT before "times" is what separates a bookable day
    // from one labelled "no times", which also ends in that word.
    const day = book.getByRole('button', { name: /,\s\d+\stimes?$/ });
    await expect(day.first()).toBeVisible({ timeout: 30_000 });

    // The LAST bookable day in the fortnight, not the first. The soonest slot can be inside the
    // 24-hour Cancellation Window, and this is the booking the flow later cancels through the
    // Manage Link — the trap the demo script hit as T44.
    await day.last().click();

    const slot = book.getByRole('button', { name: /^\d{1,2}:\d{2}/ });
    await expect(slot.first()).toBeVisible({ timeout: 30_000 });
    await slot.first().click();

    await book.getByLabel('Full name').fill(tenant.customerName);
    await book.getByLabel('Phone').fill(tenant.customerPhone);
    await book.getByLabel('Email').fill(tenant.customerEmail);
    await book.getByRole('button', { name: 'Confirm booking' }).click();

    // The confirmation's own heading, not getByText('Booked'): that matches the substring in
    // "What would you like booked?" and in "fully booked", and a strict-mode violation THROWS
    // rather than retrying — so the 30s wait never waited at all.
    await expect(
      book.getByRole('heading', { name: `${tenant.serviceName} with ${tenant.employeeName}` }),
    ).toBeVisible({ timeout: 30_000 });
    classicCode = await confirmationCode(book);
    expect(classicCode, 'the Classic Flow shows a Confirmation Code').not.toBe('');
    await publicPage.close();
  });

  await test.step('a stranger books by talking to the Receptionist', async () => {
    const chatContext = await page.context().browser()!.newContext();
    const chat = await chatContext.newPage();
    await chat.goto(`${test.info().project.use.baseURL}/book/${slug}`);

    await chat
      .getByLabel('Message the receptionist')
      .fill(`Hi, I would like a ${tenant.serviceName} please.`);
    await chat.getByRole('button', { name: 'Send' }).click();

    // The fake provider needs three tool round trips before it answers, so this is slower than a
    // form post and deliberately gets its own timeout.
    await expect(
      chat.getByRole('heading', { name: `${tenant.serviceName} with ${tenant.employeeName}` }),
    ).toBeVisible({ timeout: 60_000 });
    receptionistCode = await confirmationCode(chat);
    expect(receptionistCode, 'the Receptionist shows a Confirmation Code').not.toBe('');
    expect(receptionistCode, 'the two bookings are different appointments').not.toBe(classicCode);
    await chatContext.close();
  });

  await test.step('both confirmations arrive, and the Manage Link resolves', async () => {
    const mail = await waitForMail(request, tenant.customerEmail);
    expect(mail.Subject.toLowerCase()).toContain('confirm');

    const link = await manageLinkFrom(request, mail.ID);
    const manageContext = await page.context().browser()!.newContext();
    const manage = await manageContext.newPage();
    await manage.goto(link);

    await expect(manage.getByText(tenant.serviceName).first()).toBeVisible({ timeout: 30_000 });
    await expect(manage.getByText(classicCode).first()).toBeVisible();

    await test.step('and the customer cancels with it', async () => {
      await manage.getByRole('button', { name: 'Cancel appointment' }).click();
      // The dialog's confirm carries the same words as the button that opened it, so this is
      // scoped to the dialog rather than matching the page's first hit.
      await manage
        .getByRole('dialog')
        .getByRole('button', { name: 'Cancel appointment' })
        .click();
      await expect(manage.getByText(/cancelled/i).first()).toBeVisible({ timeout: 30_000 });
    });
    await manageContext.close();
  });

  await test.step('the owner sees both bookings, one badged AI and one cancelled', async () => {
    // Sign OUT first. The owner has been signed in since registration, and GuestGuard keeps a
    // signed-in visitor off /login — it renders the pending screen and redirects — so going
    // straight there means waiting forever for an Email field that will never render. §8 says
    // "log back in", and this is the only step that exercises the sign-in path at all.
    await page.goto('/dashboard');
    await page.getByRole('button', { name: 'Sign out' }).click();
    // No `$`: signOut() replaces with '/login', but the dashboard's AuthGuard can win the race and
    // replace with `/login?next=<path>` instead, so the URL may carry a query string.
    await page.waitForURL(/\/login(\?|$)/, { timeout: 30_000 });
    await signIn(page, tenant.email, tenant.password);

    await page.goto('/appointments');
    await expect(page.getByText(tenant.customerName).first()).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(RECEPTIONIST_CUSTOMER).first()).toBeVisible();
    await expect(page.getByText(/cancelled/i).first()).toBeVisible();

    // The AI badge lives on the calendar, not on the list — see §4 of the handoff.
    await page.goto('/calendar');
    await expect(page.getByTitle('Booked by the AI receptionist').first()).toBeVisible({
      timeout: 30_000,
    });
  });

  await test.step('marking one completed moves analytics revenue', async () => {
    await page.goto('/analytics');
    const before = await revenue(page);

    await page.goto('/appointments');
    await page.getByText(RECEPTIONIST_CUSTOMER).first().click();
    await page.waitForURL(/\/appointments\/[0-9a-f-]{36}$/i, { timeout: 30_000 });
    await expect(page.getByText('Booked with the receptionist').first()).toBeVisible();

    await page.getByRole('button', { name: 'Mark completed' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Mark completed' }).click();
    await expect(page.getByText(/completed/i).first()).toBeVisible({ timeout: 30_000 });

    await page.goto('/analytics');
    await expect
      .poll(async () => revenue(page), { timeout: 30_000 })
      .toBeGreaterThan(before);
  });
});

/**
 * The Confirmation Code, read from inside the card that captions it.
 *
 * Scoped rather than matched by shape: a bare /^[A-Z0-9]{6,10}$/ over the whole page can land on
 * any short upper-case string that happens to be rendered, and would do it silently.
 */
async function confirmationCode(page: import('@playwright/test').Page): Promise<string> {
  // `exact` and `.first()` both matter: getByText matches an ancestor whose text merely CONTAINS
  // the caption as well as the caption itself, so `..` off a bare match resolves to two parents
  // and violates strict mode.
  const card = page.getByText('Your confirmation code', { exact: true }).first().locator('..');
  await expect(card).toBeVisible({ timeout: 30_000 });
  return (await card.getByText(/^[A-Z0-9]{6,10}$/).first().innerText()).trim();
}

/** The Revenue tile's number, as a number. */
async function revenue(page: import('@playwright/test').Page): Promise<number> {
  const tile = page.getByText('Revenue', { exact: true }).first().locator('..');
  await expect(tile).toBeVisible({ timeout: 30_000 });
  const text = await tile.innerText();
  const match = text.match(/([\d,]+\.\d{2})/);
  return match ? Number(match[1].replace(/,/g, '')) : 0;
}
