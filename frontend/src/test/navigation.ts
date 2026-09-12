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
