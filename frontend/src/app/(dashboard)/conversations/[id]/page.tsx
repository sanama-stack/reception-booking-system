'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { useSession } from '@/lib/auth';
import { conversationPath, type ConversationDetail } from '@/lib/conversations';
import { Transcript } from './transcript';

/**
 * One conversation, in full.
 *
 * **The tool calls are the point of this screen.** The prose is what the customer saw and is the
 * least reliable thing here; the tool rows are what the Receptionist actually *did*, with the
 * arguments it sent and the results it got back. "It told my customer the wrong price" is answered
 * by reading a `get_service_details` result, and by nothing else — which is why they are rendered
 * as the JSON that crossed the boundary rather than summarised into a sentence.
 */
export default function ConversationPage() {
  // `useParams`, matching every other dashboard detail screen — this is a client component, so the
  // promise `params` prop would have to be unwrapped with `use` for no gain over the hook.
  const { id } = useParams<{ id: string }>();
  const { session } = useSession();
  const conversation = useResource<ConversationDetail>(conversationPath(id));

  if (!session) return null;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <Link href="/conversations" className="text-ink-muted text-sm hover:underline">
          ← Conversations
        </Link>
        <h1 className="text-ink mt-2 text-2xl font-semibold tracking-tight">Conversation</h1>
      </div>

      <ResourceGate resource={conversation}>
        {(detail) => <Transcript detail={detail} timezone={session.business.timezone} />}
      </ResourceGate>
    </div>
  );
}
