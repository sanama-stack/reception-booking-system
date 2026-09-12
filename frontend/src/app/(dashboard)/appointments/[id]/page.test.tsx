import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderScreen, serve } from '@/test/harness';
import { setRouteParams } from '@/test/navigation';
import AppointmentDetailPage from './page';
import { APPOINTMENT, NO_EMPLOYEES, NO_SERVICES } from '@/test/fixtures';

/**
 * The audit trail's empty state — rule 7's third state for this screen (docs/09-phase-plan.md §5).
 *
 * It is the one empty state in the application that is a *defect report* rather than an invitation:
 * every appointment records its own creation in the same transaction, so a trail with nothing in it
 * means a row was written outside the normal path. The copy says so, and this asserts that it does
 * rather than that the page merely survives an empty list.
 *
 * The page reads three resources — the appointment, the services and the employees — because the
 * reschedule panel must open instantly. All three are served, so an unanswered one cannot make the
 * screen render its error state while a test looks for an empty one.
 */
/** Every path `fetch` was called with, as the client wrote it — `/api` prefix and all. */
function requestedPaths(): string[] {
  return vi
    .mocked(globalThis.fetch)
    .mock.calls.map(([input]) => new URL(String(input), 'http://localhost').pathname);
}

describe('AppointmentDetailPage, for an appointment with no history', () => {
  it('names the trail as empty rather than drawing an empty list', async () => {
    setRouteParams({ id: APPOINTMENT.appointment.id });
    serve({
      kind: 'body',
      bodies: {
        '/appointments': { ...APPOINTMENT, history: [] },
        '/services': NO_SERVICES,
        '/employees': NO_EMPLOYEES,
      },
    });
    renderScreen(<AppointmentDetailPage />);

    expect(await screen.findByText('No history')).toBeInTheDocument();
    expect(
      screen.getByText(/an empty trail means something was written outside the normal path/i),
    ).toBeVisible();

    // The page asked for *this* appointment. Worth asserting once: the suite's `useParams` stub
    // used to answer `{}`, which had this page request `/appointments/undefined` — a path the
    // harness matches by prefix, so every assertion above would have passed just as well against
    // a page that had stopped reading its own route.
    expect(requestedPaths()).toContain('/api/appointments/appointment-1');
  });

  it('draws the trail instead when there is one, which is what makes the case above a case', async () => {
    setRouteParams({ id: APPOINTMENT.appointment.id });
    serve({
      kind: 'body',
      bodies: {
        '/appointments': APPOINTMENT,
        '/services': NO_SERVICES,
        '/employees': NO_EMPLOYEES,
      },
    });
    renderScreen(<AppointmentDetailPage />);

    // The heading is on both renders; the empty state is not. Asserting its absence here is what
    // separates "the empty state appears when the trail is empty" from "the empty state always
    // appears", which the case above cannot tell apart on its own.
    expect(await screen.findByText('History')).toBeInTheDocument();
    expect(screen.queryByText('No history')).not.toBeInTheDocument();
  });
});
