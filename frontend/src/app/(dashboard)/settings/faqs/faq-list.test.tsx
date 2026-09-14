import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithToasts, serve } from '@/test/harness';
import { FaqsCard } from './faq-list';

/** Rule 7's empty state for the receptionist's questions. */
describe('FaqsCard, with no questions', () => {
  it('suggests what to write rather than leaving an empty card', () => {
    renderWithToasts(<FaqsCard list={{ faqs: [] }} onChanged={() => Promise.resolve()} />);

    expect(screen.getByText('No questions yet')).toBeInTheDocument();
    expect(screen.getByText(/parking, payment, what to bring/)).toBeVisible();
  });
});

/**
 * Three writes on one card, refused three different ways — and the split is the point.
 *
 * Adding banners, because the form is still there holding what was typed. Removing toasts,
 * because the row it was about has gone. **Reordering toasts and then re-reads**, which neither
 * of the others does: a reorder is several `patchFaq` calls, so a failure can leave the list
 * half-moved, and the only honest thing a screen can do is stop guessing and ask the server what
 * the order actually is now.
 */
describe('FaqsCard, when a write is refused', () => {
  const TWO = {
    faqs: [
      { id: 'faq-1', question: 'Do you take walk-ins?', answer: 'Yes, when we can.', sortOrder: 1 },
      { id: 'faq-2', question: 'Is there parking?', answer: 'On the street.', sortOrder: 2 },
    ],
  };

  it('banners the server sentence when the add is refused', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'FAQ_LIMIT_REACHED',
        detail: 'You already have as many questions as the receptionist can hold.',
      },
    });
    renderWithToasts(<FaqsCard list={{ faqs: [] }} onChanged={() => Promise.resolve()} />);

    await userEvent.type(screen.getByLabelText('Question'), 'Do you do colour?');
    await userEvent.type(screen.getByLabelText('Answer'), 'Yes, by appointment.');
    await userEvent.click(screen.getByRole('button', { name: 'Add question' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'You already have as many questions as the receptionist can hold.',
    );
  });

  it('keeps what was typed so the owner can shorten it rather than retype it', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderWithToasts(<FaqsCard list={{ faqs: [] }} onChanged={() => Promise.resolve()} />);

    await userEvent.type(screen.getByLabelText('Question'), 'Do you do colour?');
    await userEvent.type(screen.getByLabelText('Answer'), 'Yes, by appointment.');
    await userEvent.click(screen.getByRole('button', { name: 'Add question' }));

    await screen.findByRole('alert');
    expect(screen.getByLabelText('Question')).toHaveValue('Do you do colour?');
    expect(screen.getByLabelText('Answer')).toHaveValue('Yes, by appointment.');
  });

  it('toasts the server sentence when a removal is refused', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'FAQ_IN_USE',
        detail: 'That question is being used by the receptionist right now.',
      },
    });
    renderWithToasts(<FaqsCard list={TWO} onChanged={() => Promise.resolve()} />);

    await userEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]!);
    await userEvent.click(screen.getByRole('button', { name: 'Remove question' }));

    expect(
      await screen.findByText('That question is being used by the receptionist right now.'),
    ).toBeInTheDocument();
  });

  /**
   * The case only reordering has. Several writes go out, so a refusal can leave the order
   * half-applied — and the screen cannot know how far it got. Re-reading is the only honest
   * answer, and it is the opposite of what every other refusal on this card does.
   */
  it('re-reads the list after a failed reorder, because it may be half-moved', async () => {
    const onChanged = vi.fn(() => Promise.resolve());
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 500,
        code: 'INTERNAL_ERROR',
        detail: 'The order could not be saved.',
      },
    });
    renderWithToasts(<FaqsCard list={TWO} onChanged={onChanged} />);

    await userEvent.click(screen.getByRole('button', { name: 'Move "Is there parking?" up' }));

    expect(await screen.findByText('The order could not be saved.')).toBeInTheDocument();
    expect(onChanged).toHaveBeenCalled();
  });
});
