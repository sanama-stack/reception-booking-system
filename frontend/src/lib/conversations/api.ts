/**
 * Paths for `/conversations/*`.
 *
 * Expressed as paths rather than as calls, matching `lib/scheduling` and `lib/customers`: the list
 * is read through `useResource`, which re-runs because the string moved and for no other reason.
 *
 * **Read-only, and there is nothing else to add.** `ConversationQueryController` publishes two
 * `GET`s and no writes at all — an owner can read what the Receptionist said and cannot edit it,
 * which is the only arrangement in which a transcript is evidence of anything.
 */

export function conversationsPath(
  query: { page?: number; size?: number; unofferedOnly?: boolean } = {},
): string {
  const params = new URLSearchParams();
  if (query.page !== undefined) params.set('page', String(query.page));
  if (query.size !== undefined) params.set('size', String(query.size));
  // Only when true. A `unofferedOnly=false` in the URL would make the ordinary list look like a
  // filtered one in a bookmark, and the server's default is already false.
  if (query.unofferedOnly) params.set('unofferedOnly', 'true');
  const search = params.toString();
  return search ? `/conversations?${search}` : '/conversations';
}

export function conversationPath(id: string): string {
  return `/conversations/${encodeURIComponent(id)}`;
}
