import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EMPLOYEE } from '@/test/fixtures';
import { renderScreen, serve } from '@/test/harness';
import { setRouteParams } from '@/test/navigation';
import EmployeeDetailPage from './page';

/**
 * The sibling of `services/[id]/page.test.tsx`, and the same single claim: this page's toggle is
 * wired to a real endpoint for the id in the route, and a refusal from it reaches the owner.
 *
 * `active-toggle`'s own behaviour is asserted from two list screens already; nothing here
 * repeats it.
 */

describe('the employee detail page, when the toggle is refused', () => {
  it('shows the server sentence rather than losing it between page and control', async () => {
    setRouteParams({ id: EMPLOYEE.id });
    serve({
      kind: 'body',
      bodies: {
        [`/employees/${EMPLOYEE.id}/schedule`]: { timezone: 'UTC', schedule: [] },
        [`/employees/${EMPLOYEE.id}/time-off`]: { timezone: 'UTC', timeOff: [] },
        [`/employees/${EMPLOYEE.id}`]: { ...EMPLOYEE, active: false },
        '/services': { services: [] },
      },
      refusing: {
        kind: 'failing',
        path: `/employees/${EMPLOYEE.id}/activate`,
        status: 409,
        code: 'EMPLOYEE_HAS_NO_SERVICES',
        detail: 'Give them a service to provide before activating them.',
      },
    });
    renderScreen(<EmployeeDetailPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Activate' }));

    expect(
      await screen.findByText('Give them a service to provide before activating them.'),
    ).toBeInTheDocument();
  });
});
