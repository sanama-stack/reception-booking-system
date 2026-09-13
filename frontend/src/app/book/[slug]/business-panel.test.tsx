import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { PublicBusiness } from '@/lib/public';
import { BusinessPanel } from './business-panel';

/**
 * Whether the right-hand column offers a Receptionist, and what stands there when it does not.
 *
 * The second half is the one worth a test. `business-panel.tsx` rendered the panel conditionally
 * with no else for ten phases, so an unavailable Receptionist was an absence rather than a
 * statement — and the README's "the panel says so" described a state no file produced (G42, issue
 * #37). An absence is exactly what a test that only asserts the panel is gone would also accept,
 * so both assertions are made: the panel is gone AND something says why.
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

function panel(receptionistAvailable: boolean) {
  render(<BusinessPanel slug="aria" business={{ ...BUSINESS, receptionistAvailable }} />);
}

describe('the booking pages right-hand column', () => {
  it('offers the Receptionist when there is one', () => {
    panel(true);

    // The live panel's own heading, which the note deliberately does not reuse.
    expect(screen.getByRole('heading', { name: /ask the receptionist/i })).toBeInTheDocument();
    // A textbox is the thing only the real one has.
    expect(screen.getByRole('textbox')).toBeInTheDocument();
  });

  it('says so when there is none, and keeps the rest of the column', () => {
    panel(false);

    expect(screen.getByRole('heading', { name: /booking assistant/i })).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: /ask the receptionist/i }),
    ).not.toBeInTheDocument();
    expect(screen.getByText(/isn't available right now/i)).toBeInTheDocument();
    // Gone, not merely quiet.
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    // And the reference material below it is untouched, because this is a closed door and not a
    // failed page.
    expect(screen.getByRole('heading', { name: /changes and cancellations/i })).toBeInTheDocument();
  });
});
