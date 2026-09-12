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

// ---------------------------------------------------------------------------
// The rows the id-taking routes need (G25)
// ---------------------------------------------------------------------------

/** One of each thing a `/{id}` route renders, for a tenant that has just registered. */
export interface DetailRows {
  appointmentId: string;
  customerId: string;
  serviceId: string;
  employeeId: string;
  conversationId: string;
}

/**
 * Reads the body before asserting, so a failure says what the server actually answered.
 *
 * `expect(response.ok())` on its own reports a bare status, and the status is the least useful
 * half of a validation failure — the field and the message are in the body.
 */
async function json<T>(
  page: Page,
  method: 'get' | 'post' | 'put',
  path: string,
  data?: unknown,
  timeout = 30_000,
): Promise<T> {
  const response = await page.request[method](
    `/api${path}`,
    data === undefined ? { timeout } : { data, timeout },
  );
  const body = await response.text();
  expect(
    response.ok(),
    `${method.toUpperCase()} ${path} answered ${response.status()}: ${body.slice(0, 500)}`,
  ).toBeTruthy();
  return JSON.parse(body) as T;
}

/**
 * Creates one employee, one service, one appointment, one customer and one conversation.
 *
 * **Through the API rather than the UI, deliberately.** The sweep's question is whether a rendered
 * detail page fits in 360 px, and driving five forms to reach five pages would spend most of the
 * run proving things `flow.spec.ts` already proves — and would fail for form reasons in a spec that
 * measures layout.
 *
 * `page.request` carries the session cookies the registration left behind, so these are the same
 * authenticated calls the screens themselves make.
 *
 * The conversation is the one that cannot be created by a plain write: it has to be *talked* into
 * existing. That is also why it is worth having — the transcript renders each tool call's arguments
 * and results as pretty-printed JSON, which is the widest content this application draws anywhere,
 * and an empty conversation would give the sweep nothing to measure.
 */
export async function seedDetailRows(
  page: Page,
  tenant: ReturnType<typeof freshTenant>,
  slug: string,
): Promise<DetailRows> {
  const employee = await json<{ id: string }>(page, 'post', '/employees', {
    fullName: tenant.employeeName,
  });

  // All seven days at the business's own opening hours (BusinessDefaults: 09:00-17:00, Mon-Fri).
  // The engine intersects the two, so this is five working days a week — and a fortnight always
  // contains some, which is what stops the slot search below coming back empty.
  await json(page, 'put', `/employees/${employee.id}/schedule`, {
    schedule: [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({
      dayOfWeek,
      startsAt: '09:00',
      endsAt: '17:00',
    })),
  });

  const service = await json<{ id: string }>(page, 'post', '/services', {
    name: tenant.serviceName,
    durationMinutes: 30,
    price: SERVICE_PRICE,
  });
  // A service nobody can perform has no availability, so the booking below would have nothing to
  // choose — the same assignment the flow makes on the service form.
  await json(page, 'put', `/services/${service.id}/employees`, { employeeIds: [employee.id] });

  const from = isoDay(0);
  const to = isoDay(13);
  const availability = await json<{
    days: { slots: { startsAt: string; employee: { id: string } }[] }[];
  }>(page, 'get', `/availability?serviceId=${service.id}&from=${from}&to=${to}`);

  const slot = availability.days.flatMap((day) => day.slots)[0];
  expect(slot, `no bookable slot between ${from} and ${to} for a business open every day`).toBeTruthy();

  const booked = await json<{ appointment: { id: string; customer: { id: string } } }>(
    page,
    'post',
    '/appointments',
    {
      serviceId: service.id,
      employeeId: slot!.employee.id,
      startsAt: slot!.startsAt,
      customerName: tenant.customerName,
      customerPhone: tenant.customerPhone,
    },
  );

  const session = await json<{ conversationId: string; sessionToken: string }>(
    page,
    'post',
    `/public/businesses/${slug}/chat/session`,
    {},
  );
  // Three tool round trips against the fake provider before it answers, so this is slower than any
  // other call here and gets its own timeout — the same 60s the flow allows its chat leg.
  await json(
    page,
    'post',
    `/public/businesses/${slug}/chat`,
    { sessionToken: session.sessionToken, message: `Hi, I would like a ${tenant.serviceName}.` },
    120_000,
  );

  return {
    appointmentId: booked.appointment.id,
    customerId: booked.appointment.customer.id,
    serviceId: service.id,
    employeeId: employee.id,
    conversationId: session.conversationId,
  };
}

/** A date `offsetDays` from now, as the availability query spells one. */
function isoDay(offsetDays: number): string {
  return new Date(Date.now() + offsetDays * 86_400_000).toISOString().slice(0, 10);
}
