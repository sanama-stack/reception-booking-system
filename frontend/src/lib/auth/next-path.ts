/** Where a signed-in visitor belongs when no destination was requested. */
export const DEFAULT_SIGNED_IN_PATH = '/dashboard';

/**
 * The auth group. A `next` pointing at one of these is refused rather than followed: GuestGuard
 * sends a signed-in visitor to `next`, so `/login?next=/login` would redirect to itself forever.
 */
const AUTH_PATHS = ['/login', '/register'];

/**
 * Narrows a caller-supplied `?next=` to a destination that is safe to redirect to.
 *
 * Two separate hazards, and both are why this is one function rather than a check copied into
 * each caller — a redirect sanitiser that exists in two places is a redirect sanitiser that will
 * eventually only be fixed in one:
 *
 *  - **Open redirect.** `?next=https://evil.example` or `?next=//evil.example` would turn the
 *    login page into a credential-harvesting hop. Only site-relative paths are honoured. A
 *    backslash is rejected alongside `/`, because browsers normalise `/\evil.example` into the
 *    protocol-relative form.
 *  - **Redirect loop.** A `next` inside the auth group bounces against the guard that read it.
 *
 * Anything rejected falls back rather than throwing: a malformed `next` is a bad link, not an
 * error worth showing someone who is simply trying to sign in.
 */
export function safeNextPath(
  requested: string | null | undefined,
  fallback: string = DEFAULT_SIGNED_IN_PATH,
): string {
  if (!requested || !requested.startsWith('/')) return fallback;
  if (requested[1] === '/' || requested[1] === '\\') return fallback;

  const path = (requested.split(/[?#]/)[0] ?? '').replace(/\/+$/, '');
  if (AUTH_PATHS.includes(path)) return fallback;

  return requested;
}
