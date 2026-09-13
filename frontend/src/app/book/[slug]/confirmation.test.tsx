import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { BookedAppointment, PublicBusiness } from '@/lib/public';
import { Confirmation } from './confirmation';

/**
 * What the card calls itself, and what it claims while doing so.
 *
 * The `moved` variant exists because a reschedule used to produce no card at all: the server landed
 * on a date, `reschedule_appointment` returned it, and nothing carried it to the panel — so the
 * model's prose was the only account of the new time a Customer could read. That is the surface
 * issue #17 measures, and it is the one place docs/05-ai-architecture.md §6's strongest control was
 * simply missing.
 *
 * **The point of these tests is that `kind` changes wording and nothing else.** Every figure on the
 * card is read from one `BookedAppointment` either way, so the second test asserts the *shared*
 * facts under `moved` rather than only the differing label — a variant that quietly dropped the
 * price or the time would otherwise pass.
 */

const BUSINESS: PublicBusiness = {
  name: 'Aria Studio',
  description: null,
  addressLine: null,
  city: null,
  country: null,
  phone: null,
  email: null,
  website: null,
  timezone: 'Asia/Tbilisi',
  currency: 'GEL',
  cancellationWindowHours: 24,
  cancellationPolicy: null,
  receptionistAvailable: true,
  hours: [],
};

const APPOINTMENT: BookedAppointment = {
  id: '11111111-1111-1111-1111-111111111111',
  confirmationCode: '7QK4M2XR',
  startsAt: '2026-09-21T12:00:00+04:00',
  endsAt: '2026-09-21T13:00:00+04:00',
  timezone: 'Asia/Tbilisi',
  service: { name: 'Haircut', durationMinutes: 60 },
  employee: { fullName: 'Nino Beridze' },
  price: { amount: '60.00', currency: 'GEL' },
  confirmationSent: true,
};

describe('the confirmation card', () => {
  it('calls a booking Booked, and says to keep the code', () => {
    render(<Confirmation appointment={APPOINTMENT} business={BUSINESS} />);

    expect(screen.getByText('Booked')).toBeInTheDocument();
    expect(screen.getByText(/your confirmation code$/i)).toBeInTheDocument();
    expect(screen.getByText(/keep this\./i)).toBeInTheDocument();
  });

  it('calls a move Moved, and does not claim the code is new', () => {
    render(<Confirmation appointment={APPOINTMENT} business={BUSINESS} kind="moved" />);

    expect(screen.getByText('Moved')).toBeInTheDocument();
    expect(screen.queryByText('Booked')).not.toBeInTheDocument();
    // A reschedule deliberately does not reissue the Confirmation Code, so the card must not tell
    // a Customer to write down a code they have had since they booked.
    expect(screen.getByText(/confirmation code is unchanged/i)).toBeInTheDocument();
    expect(screen.getByText(/does not issue a new code/i)).toBeInTheDocument();
    expect(screen.queryByText(/^keep this\./i)).not.toBeInTheDocument();
  });

  it('states the same facts under either kind, because they come from one record', () => {
    render(<Confirmation appointment={APPOINTMENT} business={BUSINESS} kind="moved" />);

    // The code itself is shown on a move — it is still how a Customer proves the appointment is
    // theirs — even though the sentence around it changes.
    expect(screen.getByText('7QK4M2XR')).toBeInTheDocument();
    expect(screen.getByText(/haircut with nino beridze/i)).toBeInTheDocument();
    // The business's zone, never the browser's: the suite runs at Asia/Tbilisi and a card that
    // read the browser would agree here by accident, so the zone is named on the card itself.
    expect(screen.getByText(/times in Asia\/Tbilisi/i)).toBeInTheDocument();
    expect(screen.getByText(/12:00/)).toBeInTheDocument();
    // confirmationSent is true, so the card promises a message rather than warning there is none.
    expect(screen.getByText(/a confirmation is on its way/i)).toBeInTheDocument();
  });
});
