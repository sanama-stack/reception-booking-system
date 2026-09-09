/**
 * The one API client.
 *
 * Every error response is normalised into `{ code, message, fieldErrors }` so screens render
 * server-provided messages instead of inventing their own (docs/02-product-architecture.md §7).
 *
 * There is no token handling here, and there never will be: everything shares one origin, so the
 * httpOnly `access_token` cookie is sent automatically and is unreachable from JavaScript. That is
 * the payoff of the Caddy decision (docs/06-security.md §2).
 */

export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'EMAIL_TAKEN'
  | 'SLUG_TAKEN'
  | 'INVALID_CREDENTIALS'
  | 'TOKEN_EXPIRED'
  | 'SESSION_REFRESHABLE'
  | 'TOKEN_REUSED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'SLOT_UNAVAILABLE'
  | 'SERVICE_IN_USE'
  | 'VERSION_CONFLICT'
  | 'SERVICE_INACTIVE'
  | 'EMPLOYEE_INACTIVE'
  | 'EMPLOYEE_CANNOT_PERFORM_SERVICE'
  | 'OUTSIDE_BUSINESS_HOURS'
  | 'OUTSIDE_WORKING_HOURS'
  | 'BOOKING_IN_PAST'
  | 'BELOW_MIN_LEAD_TIME'
  | 'BEYOND_MAX_ADVANCE'
  | 'CANCELLATION_WINDOW_CLOSED'
  | 'INVALID_STATUS_TRANSITION'
  | 'INVALID_CONFIRMATION_CODE'
  | 'MANAGE_TOKEN_INVALID'
  | 'RATE_LIMITED'
  | 'AI_UNAVAILABLE'
  | 'AI_LIMIT_REACHED'
  | 'UNAUTHENTICATED'
  | 'INTERNAL_ERROR'
  | 'NETWORK_ERROR';

/** Field-level messages from the `errors` array, keyed by field name. */
export type FieldErrors = Record<string, string>;

export class ApiError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  readonly fieldErrors: FieldErrors;
  readonly requestId: string | undefined;
  readonly retryAfterSeconds: number | undefined;

  constructor(init: {
    code: ErrorCode;
    message: string;
    status: number;
    fieldErrors?: FieldErrors;
    requestId?: string;
    retryAfterSeconds?: number;
  }) {
    super(init.message);
    this.name = 'ApiError';
    this.code = init.code;
    this.status = init.status;
    this.fieldErrors = init.fieldErrors ?? {};
    this.requestId = init.requestId;
    this.retryAfterSeconds = init.retryAfterSeconds;
  }
}

/** RFC 9457 problem+json, extended with our `code` (docs/04-api-overview.md §3). */
interface ProblemDetail {
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  requestId?: string;
  errors?: Array<{ field?: string; message?: string }>;
}

export interface RequestOptions {
  query?: Record<string, string | number | boolean | undefined>;
  signal?: AbortSignal;
  /** Server components fetch through the internal URL; the browser goes through Caddy. */
  baseUrl?: string;
  /** Set by the retry after a refresh, so a second failure is not retried again. */
  isRetry?: boolean;
}

const DEFAULT_BASE_URL =
  typeof window === 'undefined'
    ? (process.env.BACKEND_INTERNAL_URL ?? 'http://localhost:9081/api')
    : '/api';

function buildUrl(path: string, options: RequestOptions | undefined): string {
  const base = options?.baseUrl ?? DEFAULT_BASE_URL;
  const url = `${base}${path.startsWith('/') ? path : `/${path}`}`;
  if (!options?.query) return url;

  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(options.query)) {
    if (value !== undefined) params.set(key, String(value));
  }
  const queryString = params.toString();
  return queryString ? `${url}?${queryString}` : url;
}

async function normaliseError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail = {};
  try {
    problem = (await response.json()) as ProblemDetail;
  } catch {
    // A body that is not JSON is itself a defect; fall through to the generic shape.
  }

  const fieldErrors: FieldErrors = {};
  for (const entry of problem.errors ?? []) {
    if (entry.field && entry.message) fieldErrors[entry.field] = entry.message;
  }

  const retryAfter = response.headers.get('Retry-After');

  return new ApiError({
    code: (problem.code as ErrorCode) ?? 'INTERNAL_ERROR',
    message: problem.detail ?? problem.title ?? 'The request could not be completed.',
    status: response.status,
    fieldErrors,
    requestId: problem.requestId ?? response.headers.get('X-Request-Id') ?? undefined,
    retryAfterSeconds: retryAfter ? Number(retryAfter) : undefined,
  });
}

/**
 * Transparent refresh.
 *
 * A `401` carrying `TOKEN_EXPIRED` or `SESSION_REFRESHABLE` means the access cookie aged out
 * mid-session, which is expected every fifteen minutes and is not something a user should ever
 * see. The client refreshes once and retries. Any other `401` means the session is genuinely
 * gone, and retrying would be a loop.
 *
 * The two codes are one case seen from two sides. The access cookie's max age matches the token's
 * lifetime, so in a real browser the cookie is *deleted* at expiry rather than presented and
 * rejected — the server sees no token, not an expired one, and says `SESSION_REFRESHABLE`.
 * `TOKEN_EXPIRED` is what a client that keeps the dead token past its max age gets. Both are
 * refreshable; treating only the second one as such is what made a 15-minute error state look
 * like a server bug (docs/04-api-overview.md §3).
 *
 * The in-flight promise is shared. A screen that fires five requests on mount would otherwise
 * send five refreshes — and because every refresh rotates, four of them would present a token
 * the server had just revoked, which is indistinguishable from a stolen token and would end the
 * session the refresh was meant to save. Single-flight is a correctness requirement here, not an
 * optimisation.
 */
let refreshInFlight: Promise<boolean> | null = null;

async function refreshSession(baseUrl: string): Promise<boolean> {
  refreshInFlight ??= (async () => {
    try {
      const response = await fetch(`${baseUrl}/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: '{}',
        cache: 'no-store',
      });
      return response.ok;
    } catch {
      return false;
    } finally {
      // Cleared in a microtask so every caller awaiting this attempt sees the same result before
      // a new one can start.
      queueMicrotask(() => {
        refreshInFlight = null;
      });
    }
  })();
  return refreshInFlight;
}

/**
 * The codes that say the session a caller had is over, with nothing left to recover it from.
 *
 * Deliberately an explicit list rather than `status === 401`. Two other 401s are emphatically not
 * this: a failed sign-in (`INVALID_CREDENTIALS`), which happens to someone who has no session and
 * is already looking at the form, and an expired Manage Link (`MANAGE_TOKEN_INVALID`), which
 * belongs to a public visitor who never had one. Notifying on either would sign out a bystander,
 * and on the login screen it would redirect that screen to itself.
 */
const SESSION_IS_OVER: ReadonlySet<ErrorCode> = new Set(['UNAUTHENTICATED', 'TOKEN_REUSED']);

/**
 * Notified when the session is over and this client cannot recover it — either a refresh that
 * failed, or a `401` saying there was nothing to refresh with in the first place. Registered by
 * the session context, so a burst of failing requests produces one sign-out rather than one per
 * request.
 *
 * It fires for a caller who never had a session too. `UNAUTHENTICATED` is the same answer whether
 * a session fully lapsed or never existed, and this module cannot tell the two apart: the cookies
 * are httpOnly, so it cannot see what it is sending. Only the session context knows whether it
 * believed it had a session, so that is where the distinction is drawn — and it matters, because
 * the provider is in the root layout and every public page's `/auth/me` produces this code on
 * every load (lib/auth/session-context.tsx).
 */
type SessionExpiredListener = () => void;
let onSessionExpired: SessionExpiredListener | null = null;

export function setSessionExpiredHandler(listener: SessionExpiredListener | null): void {
  onSessionExpired = listener;
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  options?: RequestOptions,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(buildUrl(path, options), {
      method,
      // Same origin, so the auth cookies ride along without any token handling here.
      credentials: 'include',
      // A required JSON content type is what blocks the form-post CSRF shape
      // (docs/06-security.md §13).
      headers:
        body === undefined
          ? { Accept: 'application/json' }
          : { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: options?.signal,
      cache: 'no-store',
    });
  } catch (cause) {
    throw new ApiError({
      code: 'NETWORK_ERROR',
      message: 'Could not reach the server. Check your connection and try again.',
      status: 0,
      ...(cause instanceof Error ? {} : {}),
    });
  }

  if (!response.ok) {
    const error = await normaliseError(response);

    // Only the refresh endpoint is excluded, and only because refreshing it would recurse.
    // `/auth/me` is deliberately *not* excluded: it is the call the auth guard makes on every page
    // load, so it is the one most likely to meet an expired token.
    const refreshable = error.code === 'TOKEN_EXPIRED' || error.code === 'SESSION_REFRESHABLE';
    const retryable = refreshable && path !== '/auth/refresh' && options?.isRetry !== true;

    if (retryable) {
      const baseUrl = options?.baseUrl ?? DEFAULT_BASE_URL;
      if (await refreshSession(baseUrl)) {
        return request<T>(method, path, body, { ...options, isRetry: true });
      }
      onSessionExpired?.();
    } else if (SESSION_IS_OVER.has(error.code)) {
      // The other way a session ends: no access token *and* no refresh cookie, so there is nothing
      // to attempt and the only recovery is signing in. This used to fall straight to the throw,
      // which left the screen showing an error state for a session that was already gone — the
      // same stuck screen the refresh path exists to prevent, reached from the other side.
      //
      // Safe to treat as a sign-out because a signed-in caller cannot provoke it by accident: an
      // unknown path is a `401` only for callers who are already unauthenticated, and a `404` for
      // everyone else (`SessionLifecycleTest` pins that, since this rule depends on it).
      onSessionExpired?.();
    }

    throw error;
  }

  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return undefined as T;
  }

  return (await response.json()) as T;
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => request<T>('GET', path, undefined, options),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>('POST', path, body ?? {}, options),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>('PUT', path, body ?? {}, options),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>('PATCH', path, body ?? {}, options),
  delete: <T>(path: string, options?: RequestOptions) =>
    request<T>('DELETE', path, undefined, options),
};
