import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { Resource } from '@/lib/api/use-resource';
import { ResourceGate } from './resource-gate';

/**
 * The loading and error halves of rule 7 (docs/09-phase-plan.md §5), where they actually live.
 *
 * Every screen in the application routes its resource through this one component, so these two
 * states are the same everywhere by construction and are asserted here once. *Empty* is not here,
 * and cannot be: an empty closure list and an empty FAQ list say different things and offer
 * different next steps, so each screen owns its own and each is asserted against that screen.
 *
 * What this file cannot tell you is whether a given screen uses the gate at all. That is
 * `src/test/screens/coverage.test.ts`, and the two together are the claim.
 */

function resource<T>(over: Partial<Resource<T>>): Resource<T> {
  return {
    data: null,
    error: null,
    loading: false,
    reload: () => Promise.resolve(),
    set: () => {},
    ...over,
  };
}

const FAILED = new ApiError({
  code: 'INTERNAL_ERROR',
  message: 'The schedule could not be read.',
  status: 500,
});

describe('ResourceGate', () => {
  it('shows a busy spinner while the first load is in flight', () => {
    render(
      <ResourceGate resource={resource<string>({ loading: true })}>
        {(data) => <p>{data}</p>}
      </ResourceGate>,
    );

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    // Announced as well as drawn — a spinner a screen reader cannot see is not a loading state.
    expect(screen.getByText('Loading', { selector: '.sr-only' })).toBeInTheDocument();
  });

  it("renders the server's own message when the load failed, not one of its own", () => {
    render(
      <ResourceGate resource={resource<string>({ error: FAILED })}>
        {(data) => <p>{data}</p>}
      </ResourceGate>,
    );

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('The schedule could not be read.');
    expect(alert).toHaveTextContent('INTERNAL_ERROR');
  });

  it('offers a retry that actually reloads', async () => {
    const reload = vi.fn(() => Promise.resolve());
    render(
      <ResourceGate resource={resource<string>({ error: FAILED, reload })}>
        {(data) => <p>{data}</p>}
      </ResourceGate>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(reload).toHaveBeenCalledOnce();
  });

  it('renders the content once there is data', () => {
    render(
      <ResourceGate resource={resource<string>({ data: 'the schedule' })}>
        {(data) => <p>{data}</p>}
      </ResourceGate>,
    );

    expect(screen.getByText('the schedule')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  /**
   * A reload keeps what is on screen rather than replacing it with a spinner. Asserted because it
   * is a deliberate choice in the gate and in `useResource` — `loading` is true only for the first
   * load — and because the alternative makes every save flicker.
   */
  it('keeps the current content on screen during a reload', () => {
    render(
      <ResourceGate resource={resource<string>({ data: 'the schedule', loading: true })}>
        {(data) => <p>{data}</p>}
      </ResourceGate>,
    );

    expect(screen.getByText('the schedule')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  /**
   * An error arriving while data is on screen does not blank the screen either. This is the same
   * rule read from the other side, and it is why the data check comes first in the gate.
   */
  it('keeps the current content on screen when a reload fails', () => {
    render(
      <ResourceGate resource={resource<string>({ data: 'the schedule', error: FAILED })}>
        {(data) => <p>{data}</p>}
      </ResourceGate>,
    );

    expect(screen.getByText('the schedule')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
