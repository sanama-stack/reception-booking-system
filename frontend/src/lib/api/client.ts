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
    throw await normaliseError(response);
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
