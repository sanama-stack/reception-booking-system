'use client';

import { useState } from 'react';
import { Button, Card, Input } from '@/components/ui';
import { useSession } from '@/lib/auth';
import { customersPath } from '@/lib/customers';
import { CustomersScreen } from './customers-screen';

/**
 * Everyone who has ever booked.
 *
 * There is no "add customer", and its absence is the design rather than an omission. A Customer is
 * `(business, normalised phone)` and comes into existence by booking, which is what keeps that pair
 * an identity rather than a field somebody can fill in twice — and what stops one person becoming
 * two records with two histories.
 *
 * **The search runs when it is submitted, not on every keystroke.** Each term is a new question and
 * therefore a new request; firing one per character would spend eight requests answering a
 * five-letter name, and the last two would race. Enter, or the button.
 */
export default function CustomersPage() {
  const { session } = useSession();
  const [term, setTerm] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);

  if (!session) return null;

  const path = customersPath({ ...(query ? { q: query } : {}), page });

  function search(next: string) {
    setQuery(next);
    setPage(0);
  }

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <div>
        <h1 className="text-ink text-2xl font-semibold tracking-tight">Customers</h1>
        <p className="text-ink-muted mt-1 text-sm">
          Everyone who has booked with you, and what they booked.
        </p>
      </div>

      <Card>
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            search(term.trim());
          }}
        >
          <Input
            label="Search"
            value={term}
            className="min-w-64"
            onChange={(event) => setTerm(event.target.value)}
            hint="Name, phone number or email."
          />
          <Button type="submit">Search</Button>
          {query !== '' && (
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                setTerm('');
                search('');
              }}
            >
              Clear
            </Button>
          )}
        </form>
      </Card>

      <CustomersScreen
        key={path}
        path={path}
        searching={query !== ''}
        timezone={session.business.timezone}
        onClearSearch={() => {
          setTerm('');
          search('');
        }}
        onPage={setPage}
      />
    </div>
  );
}
