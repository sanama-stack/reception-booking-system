'use client';

import { useState } from 'react';
import { conversationsPath } from '@/lib/conversations';
import { useSession } from '@/lib/auth';
import { ConversationsScreen } from './conversations-screen';

/**
 * Every conversation the Receptionist has had.
 *
 * **Read-only, and there is no way to make it otherwise** — the controller publishes two `GET`s
 * and no writes. An owner can read what was said and cannot edit it, which is the only arrangement
 * in which a transcript is evidence of anything.
 *
 * There is no search. A conversation has no name and no natural key an owner would remember, so a
 * search box would be a box with nothing to type in it; the list is newest-first and the newest is
 * the one being asked about. Phase 10 owns whatever filtering the analytics screen wants.
 */
export default function ConversationsPage() {
  const { session } = useSession();
  const [page, setPage] = useState(0);

  if (!session) return null;

  const path = conversationsPath({ page });

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <div>
        <h1 className="text-ink text-2xl font-semibold tracking-tight">Conversations</h1>
        <p className="text-ink-muted mt-1 text-sm">
          What your customers asked the receptionist, and what it did about it.
        </p>
      </div>

      {/*
        Keyed on the path, like every other paged list here: without it, page 2's response lands in
        a component still holding page 1 and the two are indistinguishable on screen.
      */}
      <ConversationsScreen
        key={path}
        path={path}
        timezone={session.business.timezone}
        onPage={setPage}
      />
    </div>
  );
}
