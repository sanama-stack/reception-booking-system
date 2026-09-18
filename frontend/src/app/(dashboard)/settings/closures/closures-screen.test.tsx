import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithToasts, serve } from '@/test/harness';
import { ClosuresScreen } from './closures-screen';

/** Rule 7's empty state for closures. The page fetches, so loading and error are the gate's. */
describe('ClosuresScreen, with no closures', () => {
  it('says what a closure is for rather than showing a bare heading', () => {
    renderWithToasts(
      <ClosuresScreen
        list={{ timezone: 'UTC', closures: [] }}
        onChanged={() => Promise.resolve()}
      />,
    );

    expect(screen.getByText('No closures')).toBeInTheDocument();
    expect(screen.getByText(/Your opening hours apply every week/)).toBeVisible();
  });
});

/**
 * The two writes on this screen, which fail in two different places on purpose.
 *
 * Adding fails into a **banner** above a form that is still on screen and still holds what was
 * typed. Removing fails into a **toast**, because the row it was about is gone from under the
 * dialog. The catalogue records that split; nothing had ever pressed either button.
 */
describe('ClosuresScreen, when a write is refused', () => {
  const EMPTY = { timezone: 'UTC', closures: [] };
  const ONE = {
    timezone: 'UTC',
    closures: [
      {
        id: 'closure-1',
        startsAt: '2026-12-24T00:00:00Z',
        endsAt: '2026-12-26T23:59:59Z',
        startDate: '2026-12-24',
        endDate: '2026-12-26',
        reason: 'Winter break',
      },
    ],
  };

  /**
   * `fireEvent.change`, not `userEvent.type`, because both inputs are `type="date"`.
   *
   * jsdom does not implement the date editor, so typing characters into one leaves its value
   * empty — and the submit button is `disabled={!startDate || !endDate}`, so the press silently
   * does nothing and the test fails looking for a banner that was never asked for. That failure
   * reads exactly like a broken screen. Setting the value directly is what a date picker does.
   */
  async function addAClosure(): Promise<void> {
    fireEvent.change(screen.getByLabelText('First day closed'), {
      target: { value: '2026-12-24' },
    });
    fireEvent.change(screen.getByLabelText('Last day closed'), { target: { value: '2026-12-26' } });
    await userEvent.click(screen.getByRole('button', { name: 'Add closure' }));
  }

  it('banners the server sentence when the add is refused', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'CLOSURE_OVERLAPS',
        detail: 'That range overlaps a closure you already have.',
      },
    });
    renderWithToasts(<ClosuresScreen list={EMPTY} onChanged={() => Promise.resolve()} />);

    await addAClosure();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That range overlaps a closure you already have.',
    );
  });

  it('puts a fielded message against the date it names, and no banner', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'endDate', message: 'The last day cannot be before the first.' }],
      },
    });
    renderWithToasts(<ClosuresScreen list={EMPTY} onChanged={() => Promise.resolve()} />);

    await addAClosure();

    expect(await screen.findByText('The last day cannot be before the first.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  /**
   * The dates stay. A refused add that also cleared the form would make the owner retype what the
   * banner is asking them to correct.
   */
  it('keeps what was typed so the owner can correct it', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderWithToasts(<ClosuresScreen list={EMPTY} onChanged={() => Promise.resolve()} />);

    await addAClosure();

    await screen.findByRole('alert');
    expect(screen.getByLabelText('First day closed')).toHaveValue('2026-12-24');
    expect(screen.getByLabelText('Last day closed')).toHaveValue('2026-12-26');
  });

  /** A removal has no form left to banner, so it toasts — the other half of the split. */
  it('toasts the server sentence when a removal is refused', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'CLOSURE_IN_PAST',
        detail: 'A closure that has already started cannot be removed.',
      },
    });
    renderWithToasts(<ClosuresScreen list={ONE} onChanged={() => Promise.resolve()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }));
    await userEvent.click(screen.getByRole('button', { name: 'Remove closure' }));

    expect(
      await screen.findByText('A closure that has already started cannot be removed.'),
    ).toBeInTheDocument();
  });
});
