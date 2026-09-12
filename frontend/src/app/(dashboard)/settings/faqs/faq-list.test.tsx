import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithToasts } from '@/test/harness';
import { FaqsCard } from './faq-list';

/** Rule 7's empty state for the receptionist's questions. */
describe('FaqsCard, with no questions', () => {
  it('suggests what to write rather than leaving an empty card', () => {
    renderWithToasts(<FaqsCard list={{ faqs: [] }} onChanged={() => Promise.resolve()} />);

    expect(screen.getByText('No questions yet')).toBeInTheDocument();
    expect(screen.getByText(/parking, payment, what to bring/)).toBeVisible();
  });
});
