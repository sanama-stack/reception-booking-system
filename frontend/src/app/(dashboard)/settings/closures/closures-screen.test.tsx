import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithToasts } from '@/test/harness';
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
