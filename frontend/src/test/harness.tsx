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
    | { kind: 'failing'; status?: number; code?: string; detail?: string }
    | { kind: 'body'; bodies: Bodies },
): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL): Promise<Response> => {
      const path = pathOf(input);

      if (path.endsWith('/auth/me')) return Promise.resolve(jsonResponse(SESSION));

      switch (mode.kind) {
        case 'pending':
          // Never settles, which is what "still loading" is. The component is unmounted by
          // Testing Library's cleanup before this could matter.
          return new Promise<Response>(() => {});

        case 'failing':
          return Promise.resolve(
            jsonResponse(
              {
                code: mode.code ?? 'INTERNAL_ERROR',
                detail: mode.detail ?? 'The request could not be completed.',
              },
              mode.status ?? 500,
            ),
          );

        case 'body': {
          const match = Object.keys(mode.bodies)
            .filter((prefix) => path.startsWith(prefix))
            .sort((a, b) => b.length - a.length)[0];

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
