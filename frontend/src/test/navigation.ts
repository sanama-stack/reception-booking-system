import { vi } from 'vitest';

/**
 * The route parameters `useParams()` answers with.
 *
 * A page under `app/.../[id]/` reads its own id, and the suite's `next/navigation` stub answered
 * `{}` for everything — so such a page asked its server for `/appointments/undefined`. That works
 * against a harness matching on prefixes, which is exactly the problem: the test would pass while
 * resting on the id being *absent*, and would go on passing if the page stopped reading the id at
 * all.
 *
 * Held here rather than in `setup.ts` so the mock factory has a module to close over that imports
 * nothing of its own. Reset after every test by the same file that installs the stub, so a test
 * cannot inherit the route a previous one was on.
 */
export const routeParams: Record<string, string> = {};

/** Put the suite on a route. Cleared between tests. */
export function setRouteParams(params: Record<string, string>): void {
  clearRouteParams();
  Object.assign(routeParams, params);
}

export function clearRouteParams(): void {
  for (const key of Object.keys(routeParams)) delete routeParams[key];
}

/**
 * The router `useRouter()` answers with — one object for the whole suite, not a new one per call.
 *
 * It was `() => ({ push: vi.fn(), … })`, which builds a fresh set of spies every render. That is
 * fine for a screen that only needs the router to exist, and useless for the one write whose
 * entire visible behaviour IS a navigation: signing out shows no message by design, so the
 * redirect is the only thing a test can hold it to, and there was nothing to assert against.
 *
 * Stable so it can be inspected, and cleared between tests by the same file that installs the
 * stub — otherwise a navigation from one test would be visible to the next, which is the failure
 * mode `routeParams` above already documents.
 */
export const router = {
  push: vi.fn(),
  replace: vi.fn(),
  refresh: vi.fn(),
  back: vi.fn(),
  forward: vi.fn(),
  prefetch: vi.fn(),
};

export function clearRouter(): void {
  for (const spy of Object.values(router)) spy.mockClear();
}
