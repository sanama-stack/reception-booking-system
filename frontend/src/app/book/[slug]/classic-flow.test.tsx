import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { PublicBusiness, PublicService } from '@/lib/public';
import { renderScreen, serve } from '@/test/harness';
import { ClassicFlow } from './classic-flow';

/**
 * The public booking failure — the one a stranger meets, and the first write in this suite whose
 * reads have to succeed before it can happen at all.
 *
 * Every mode the harness had was all-or-nothing, so a screen could only be shown a server that
 * refused everything: the availability read would fail first and the test would end up asserting
 * a loading state. `serve({ kind: 'body', refusing })` is what lets the times arrive, a time be
 * chosen, and only the save be refused — which is the shape twenty-six of the remaining entries
 * in `test/screens/catalogue.ts` need.
 *
 * The stale-slot case is the one worth the most here: its promise is not a sentence but a
 * behaviour — the times are recalculated, the chosen one is dropped, **and everything typed
 * stays.** That last clause is a claim the reader can check, which is why the component's own
 * comment calls a page that breaks it a lie rather than a bug.
 */

const TODAY = '2026-09-20';

const BUSINESS: PublicBusiness = {
  name: 'Aria Studio',
  description: null,
  addressLine: null,
  city: null,
  country: null,
  phone: null,
  email: null,
  website: null,
  timezone: 'UTC',
  currency: 'GEL',
  cancellationWindowHours: 24,
  cancellationPolicy: null,
  receptionistAvailable: false,
  hours: [],
};

/** One service, so it is pre-selected and the flow opens at the part under test. */
const SERVICES: PublicService[] = [
  {
    id: 'service-1',
    name: 'Haircut',
    description: null,
    durationMinutes: 30,
    price: { amount: '40.00', currency: 'GEL' },
  },
];

const SLOT = {
  startsAt: '2026-09-21T09:00:00Z',
  endsAt: '2026-09-21T09:30:00Z',
  employee: { id: 'employee-1', fullName: 'Nino Beridze' },
};

const BODIES = {
  '/public/businesses/aria/availability': {
    timezone: 'UTC',
    days: [{ date: '2026-09-21', slots: [SLOT] }],
    emptyReason: null,
  },
  '/public/businesses/aria/employees': [
    { id: 'employee-1', fullName: 'Nino Beridze', jobTitle: null },
  ],
};

function flow() {
  renderScreen(<ClassicFlow slug="aria" business={BUSINESS} services={SERVICES} today={TODAY} />);
}

/** Chooses the one time on offer and fills in the details, up to but not including the save. */
async function chooseATimeAndFillDetails(): Promise<void> {
  await userEvent.click(await screen.findByRole('button', { name: '09:00' }));
  await userEvent.type(screen.getByLabelText('Full name'), 'Data Sanamashvili');
  await userEvent.type(screen.getByLabelText('Phone'), '+995555123456');
}

async function book(): Promise<void> {
  await userEvent.click(screen.getByRole('button', { name: /^confirm/i }));
}

describe('the public booking form, refused', () => {
  it('shows the message the server sent rather than one of its own', async () => {
    serve({
      kind: 'body',
      bodies: BODIES,
      refusing: {
        kind: 'failing',
        status: 422,
        code: 'OUTSIDE_BOOKING_WINDOW',
        detail: 'That time is no longer within the booking window.',
      },
    });
    flow();

    await chooseATimeAndFillDetails();
    await book();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That time is no longer within the booking window.',
    );
  });

  /**
   * The refusal the flow is built around: somebody else took the time first.
   *
   * Three things are promised and all three are asserted, because the third is the one a page
   * could quietly break while still looking correct.
   */
  it('keeps everything typed when the chosen time has gone', async () => {
    serve({
      kind: 'body',
      bodies: BODIES,
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'SLOT_UNAVAILABLE',
        detail: 'That time has just been taken.',
      },
    });
    flow();

    await chooseATimeAndFillDetails();
    await book();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('That time has just been taken.');
    expect(alert).toHaveTextContent(/everything you typed has been kept/i);

    // The claim the banner makes, checked rather than taken on trust.
    expect(screen.getByLabelText('Full name')).toHaveValue('Data Sanamashvili');
    expect(screen.getByLabelText('Phone')).toHaveValue('+995555123456');
  });

  /**
   * `Retry-After` is a header, and the screen turns it into a sentence with the singular right.
   * Asserting only the server's message here would have made this case a duplicate of the one
   * above it, which is how a test that watches nothing gets written.
   */
  it('says when to try again, in seconds, if the server said so', async () => {
    serve({
      kind: 'body',
      bodies: BODIES,
      refusing: {
        kind: 'failing',
        status: 429,
        code: 'RATE_LIMITED',
        detail: 'Too many booking attempts.',
        retryAfterSeconds: 30,
      },
    });
    flow();

    await chooseATimeAndFillDetails();
    await book();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Too many booking attempts.');
    expect(alert).toHaveTextContent('Try again in 30 seconds.');
  });

  it('puts each fielded message against the field it names', async () => {
    serve({
      kind: 'body',
      bodies: BODIES,
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'The request could not be completed.',
        errors: [{ field: 'customer.phone', message: 'That does not look like a phone number.' }],
      },
    });
    flow();

    await chooseATimeAndFillDetails();
    await book();

    expect(await screen.findByText('That does not look like a phone number.')).toBeInTheDocument();
  });

  /**
   * `classic-flow.tsx` carries the comment that says nothing reaches its non-`ApiError` branch,
   * citing the client. That was true about the fetch and false about the parse until last
   * sitting's fix; this is the assertion that keeps it true.
   */
  it('says something when the server answers with something it cannot read', async () => {
    serve({ kind: 'body', bodies: BODIES, refusing: { kind: 'unreadable' } });
    flow();

    await chooseATimeAndFillDetails();
    await book();

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not read/i);
  });
});
