import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EMPLOYEES, SERVICE } from '@/test/fixtures';
import { renderScreen, serve } from '@/test/harness';
import { setRouteParams } from '@/test/navigation';
import ServiceDetailPage from './page';

/**
 * The thinnest entry in the write catalogue, and worth exactly one thing.
 *
 * `active-toggle`'s behaviour on a refusal is already asserted from two screens, so nothing here
 * re-tests it. What this page owns is the **wiring**: that its toggle is connected to a real
 * endpoint for the id in the route, and that a refusal from that endpoint reaches the owner
 * rather than being swallowed between the page and the shared control.
 *
 * The route matters. `useParams` answered `{}` for every page until `test/navigation.ts` held a
 * route, which left a `[id]` page requesting `/services/undefined` — a path the harness matches
 * by prefix anyway, so the test would have passed *because* the id was missing.
 */

describe('the service detail page, when the toggle is refused', () => {
  it('shows the server sentence rather than losing it between page and control', async () => {
    setRouteParams({ id: SERVICE.id });
    serve({
      kind: 'body',
      bodies: {
        [`/services/${SERVICE.id}`]: { ...SERVICE, active: false },
        '/employees': EMPLOYEES,
        '/business/hours': { timezone: 'UTC', hours: [] },
      },
      refusing: {
        kind: 'failing',
        // `/activate`, which is the real endpoint — `serviceApi.setActive` posts to
        // `/activate` or `/deactivate` rather than to one path with a flag.
        path: `/services/${SERVICE.id}/activate`,
        status: 409,
        code: 'SERVICE_HAS_NO_EMPLOYEES',
        detail: 'Assign someone who can perform this service before activating it.',
      },
    });
    renderScreen(<ServiceDetailPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Activate' }));

    expect(
      await screen.findByText('Assign someone who can perform this service before activating it.'),
    ).toBeInTheDocument();
  });
});
