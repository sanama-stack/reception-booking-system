import { vi } from 'vitest';
import { render, type RenderResult } from '@testing-library/react';
import { ToastProvider } from '@/components/ui';
import { SessionProvider, type Session } from '@/lib/auth';

/**
 * What a screen needs around it to be rendered by a test, and how its server is answered.
 *
 * **`fetch` is stubbed, not `@/lib/api/client`.** The client is the thing that turns a response
 * into the `ApiError` a screen renders — its status handling, its `detail`/`title` fallback, its
 * `NETWORK_ERROR` case — and a mocked `api.get` would replace all of that with whatever the test
 * decided an error looks like. Stubbing the transport keeps the real client on the path, so an
 * error state is shown here for the same reason it would be shown in a browser.
 */

/**
 * The signed-in owner every dashboard screen is rendered for.
 *
 * **At UTC, deliberately.** The suite runs at `Asia/Tbilisi` (`vitest.config.mts`), so a screen
 * that read the browser's zone instead of this business's would be four hours out — the same
 * counterfactual the calendar tests rest on, kept in force for every screen that renders a time.
 */
export const SESSION: Session = {
  user: { id: 'user-1', email: 'owner@example.com', fullName: 'Nino Beridze' },
  business: {
    id: 'business-1',
    name: 'Salon Aria',
    slug: 'salon-aria',
    timezone: 'UTC',
    currency: 'GEL',
  },
  role: 'OWNER',
};

/** Response bodies by path prefix — the longest matching prefix wins. */
export type Bodies = Record<string, unknown>;

/**
 * How a request is refused — the two shapes a screen has to tell apart.
 *
 * `failing` is a refusal the server described: a code, a sentence, and optionally the fields it
 * is about. `unreadable` is a `2xx` whose body is not JSON, which is the one way a caller could
 * be handed something that is not an `ApiError` — the case every screen's else-branch is written
 * for and almost none had ever been shown.
 */
export type Refusal =
  | { kind: 'unreadable' }
  | {
      kind: 'failing';
      status?: number;
      code?: string;
      detail?: string;
      errors?: Array<{ field: string; message: string }>;
      /**
       * Seconds, sent as the `Retry-After` header rather than in the body.
       *
       * It is a header on the wire and `client.ts` reads it from there, so a case that put the
       * number in the JSON would assert a screen against a shape no server produces.
       */
      retryAfterSeconds?: number;
    };

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/**
 * The path as the *application* writes it — `/customers`, not `/api/customers`.
 *
 * `client.ts` prefixes every request with `/api`, which is Caddy's split and not something any
 * screen knows about. Catalogued bodies are keyed the way the calling code reads, so the prefix is
 * taken off here rather than repeated in every case.
 */
function pathOf(input: RequestInfo | URL): string {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
  const { pathname } = new URL(url, 'http://localhost');
  return pathname.startsWith('/api/') ? pathname.slice('/api'.length) : pathname;
}

/**
 * One refusal, as the wire would carry it.
 *
 * Shared by the whole-server mode and the per-write one so that a write refused mid-screen is
 * byte-identical to one refused by a server that refuses everything. Two builders would be two
 * chances for a screen to pass against a shape the server never sends.
 */
function refusalResponse(refusal: Refusal): Response {
  if (refusal.kind === 'unreadable') {
    return new Response('<html>upstream said something else</html>', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }
  return new Response(
    JSON.stringify({
      code: refusal.code ?? 'INTERNAL_ERROR',
      detail: refusal.detail ?? 'The request could not be completed.',
      ...(refusal.errors ? { errors: refusal.errors } : {}),
    }),
    {
      status: refusal.status ?? 500,
      headers: {
        'Content-Type': 'application/json',
        ...(refusal.retryAfterSeconds !== undefined
          ? { 'Retry-After': String(refusal.retryAfterSeconds) }
          : {}),
      },
    },
  );
}

/**
 * Answers `/auth/me` from {@link SESSION} and everything else according to `mode`.
 *
 * `/auth/me` is answered in every mode on purpose. A dashboard page renders `null` until it has a
 * session, so a stub that left the session pending would produce an empty document — and a test
 * looking for a spinner would then be asserting the absence of a page rather than the presence of
 * a loading state.
 */
export function serve(
  mode:
    | { kind: 'pending' }
    /**
     * Field-level messages go in the wire's own shape rather than the client's. `fieldErrors` is
     * keyed by field name, but the server sends an `errors` array and `client.ts` does the
     * keying — a case handing over the keyed map would skip the one translation a screen's field
     * messages depend on.
     */
    | (Refusal & { kind: 'failing' })
    | { kind: 'unreadable' }
    /**
     * Reads that succeed and, optionally, a write that does not.
     *
     * The mode the write half of the catalogue needs. Until it existed every mode was
     * all-or-nothing, so a screen could be shown a server that refused *everything* — which never
     * reaches the save button, because the read behind the screen fails first and the loading
     * state is what the test ends up asserting. `refusing` applies to any request that is not a
     * GET, narrowed by `path` when one screen writes to more than one place.
     */
    | { kind: 'body'; bodies: Bodies; refusing?: Refusal & { path?: string } },
): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const path = pathOf(input);
      // Taken from `init`, which is where `client.ts` puts it; a `Request` object would carry its
      // own, and nothing in this application builds one.
      const method = (init?.method ?? 'GET').toUpperCase();

      if (path.endsWith('/auth/me')) return Promise.resolve(jsonResponse(SESSION));

      switch (mode.kind) {
        case 'pending':
          // Never settles, which is what "still loading" is. The component is unmounted by
          // Testing Library's cleanup before this could matter.
          return new Promise<Response>(() => {});

        case 'failing':
          return Promise.resolve(refusalResponse(mode));

        case 'unreadable':
          // A success whose body is not JSON — an upstream error page, a truncated reply. The
          // interesting case because it is the one way a caller could be handed something that is
          // not an `ApiError`, and every screen branches on that distinction.
          return Promise.resolve(refusalResponse(mode));

        case 'body': {
          const match = Object.keys(mode.bodies)
            .filter((prefix) => path.startsWith(prefix))
            .sort((a, b) => b.length - a.length)[0];

          /**
           * A write is refused before its body is looked for — the point of this mode is a screen
           * whose reads worked, so the refusal happens where the save is.
           *
           * `refusing.path` competes with the catalogued prefixes under **the same
           * longest-match rule**, rather than winning outright. Two endpoints often share a
           * prefix — `…/chat` and `…/chat/session` — and a refusal aimed at the shorter one
           * would otherwise take the longer one down with it, which no server does and which
           * would stop the screen ever reaching the write under test.
           */
          const refusalWins =
            mode.refusing !== undefined &&
            method !== 'GET' &&
            (mode.refusing.path === undefined ||
              (path.startsWith(mode.refusing.path) &&
                mode.refusing.path.length >= (match?.length ?? 0)));

          if (refusalWins) return Promise.resolve(refusalResponse(mode.refusing!));

          // Loud rather than empty. A screen asking for something the case did not anticipate
          // would otherwise render an error state, and the test would read that as the screen
          // behaving correctly in a case it is not in.
          if (match === undefined) {
            return Promise.reject(
              new Error(
                `No body catalogued for ${path}. Known: ${Object.keys(mode.bodies).join(', ') || '(none)'}`,
              ),
            );
          }
          return Promise.resolve(jsonResponse(mode.bodies[match]));
        }
      }
    }),
  );
}

/**
 * Renders a screen inside the providers `app/layout.tsx` puts around every page, in the same
 * nesting — the session outside, the toasts inside.
 *
 * Mirrored rather than approximated: a screen that saves shows a toast, and a screen rendered
 * without the provider throws *"useToast must be used inside a ToastProvider"* on a path the test
 * was not looking at, which reads as a broken screen rather than a missing wrapper.
 */
export function renderScreen(node: React.ReactNode): RenderResult {
  return render(
    <SessionProvider>
      <ToastProvider>{node}</ToastProvider>
    </SessionProvider>,
  );
}

/**
 * For a component that fetches nothing and only needs the toasts — a screen handed its data as a
 * prop, whose page does the reading. No session provider, so no `/auth/me`, so no `serve()` is
 * needed and no request is made at all.
 */
export function renderWithToasts(node: React.ReactNode): RenderResult {
  return render(<ToastProvider>{node}</ToastProvider>);
}
