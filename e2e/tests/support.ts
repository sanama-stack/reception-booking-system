import { expect, type APIRequestContext, type Page } from '@playwright/test';

/**
 * A tenant nothing else shares.
 *
 * `make up-e2e` runs in its own compose project with its own database volume, so the stack starts
 * empty — but a second run against a stack that was not torn down would collide on the slug and the
 * email. Both are stamped, so re-running without `make down-e2e` still works.
 */
export function freshTenant() {
  const stamp = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  return {
    businessName: `E2E Salon ${stamp}`,
    ownerName: 'Owner Ovna',
    email: `owner-${stamp}@e2e.local`,
    password: 'e2e-password-1234',
    serviceName: 'Haircut',
    employeeName: 'Nino Beridze',
    // The Classic Flow's customer. The Receptionist's is the fake provider's own constant, and the
    // two must differ so §7's assertions can tell the two bookings apart.
    customerName: 'Clara Classic',
    customerPhone: '+995555000222',
    customerEmail: `clara-${stamp}@e2e.local`,
  };
}

/** What the flow charges for its one service, and therefore the revenue one completion produces. */
export const SERVICE_PRICE = 40;

/** The fake provider's fixed identity — infra/fake-provider/server.js, ADR-0011. */
export const RECEPTIONIST_CUSTOMER = 'E2E Customer';

export const MAILPIT = process.env.E2E_MAILPIT_URL ?? 'http://localhost:9183';

export interface Mail {
  ID: string;
  To: { Address: string }[];
  Subject: string;
}

/**
 * Mail is sent by a poller, not by the request that books, so it arrives *after* the confirmation
 * card. Polling is the honest way to wait for it: a fixed sleep either flakes or wastes the
 * difference, and the poller's interval is a minute in production config.
 */
export async function waitForMail(
  request: APIRequestContext,
  to: string,
  timeoutMs = 120_000,
): Promise<Mail> {
  const deadline = Date.now() + timeoutMs;
  let seen: string[] = [];
  while (Date.now() < deadline) {
    const response = await request.get(`${MAILPIT}/api/v1/messages?limit=200`);
    if (response.ok()) {
      const body = (await response.json()) as { messages?: Mail[] };
      const messages = body.messages ?? [];
      seen = messages.flatMap((message) => message.To.map((address) => address.Address));
      const match = messages.find((message) =>
        message.To.some((address) => address.Address.toLowerCase() === to.toLowerCase()),
      );
      if (match) return match;
    }
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
  throw new Error(
    `No mail for ${to} within ${timeoutMs}ms. Mailpit holds mail for: ${seen.join(', ') || '(nothing)'}`,
  );
}

/** The Manage Link out of the message body, as a customer would follow it. */
export async function manageLinkFrom(request: APIRequestContext, id: string): Promise<string> {
  const response = await request.get(`${MAILPIT}/api/v1/message/${id}`);
  expect(response.ok(), 'Mailpit returned the message').toBeTruthy();
  const body = (await response.json()) as { Text?: string; HTML?: string };
  const source = `${body.Text ?? ''}\n${body.HTML ?? ''}`;
  const match = source.match(/https?:\/\/[^\s"'<>]*\/manage\/[A-Za-z0-9._~+/=-]+/);
  if (!match) throw new Error(`No Manage Link in message ${id}. Body was:\n${source.slice(0, 2000)}`);
  // The HTML copy can carry an entity-encoded query; the path itself never does.
  return match[0].replace(/&amp;/g, '&');
}

/** Register, and return the slug the dashboard reports rather than one recomputed from the name. */
export async function registerBusiness(
  page: Page,
  tenant: ReturnType<typeof freshTenant>,
): Promise<string> {
  await page.goto('/register');
  await page.getByLabel('Business name').fill(tenant.businessName);
  await page.getByLabel('Your name').fill(tenant.ownerName);
  await page.getByLabel('Email').fill(tenant.email);
  await page.getByLabel('Password').fill(tenant.password);
  await page.getByRole('button', { name: 'Create business' }).click();

  await page.waitForURL('**/dashboard', { timeout: 30_000 });

  // Read rather than derive: slugification is the server's rule, and a copy of it here would be a
  // second implementation that can disagree with the first.
  const printed = await page.getByText(/^\/book\//).first().innerText();
  const slug = printed.trim().replace(/^\/book\//, '');
  expect(slug, 'the dashboard names the public page').not.toBe('');
  return slug;
}

export async function signIn(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: /sign in/i }).click();
  await page.waitForURL('**/dashboard', { timeout: 30_000 });
}
