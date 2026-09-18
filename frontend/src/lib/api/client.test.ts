import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api } from './client';

/**
 * The client's own failure modes, at the layer that has to produce an `ApiError` or produce
 * nothing anyone can render.
 *
 * Twenty-six modules branch on `cause instanceof ApiError` and show something different when it is
 * false — and two of them show *nothing*. That branch is only as good as this module's promise that
 * it cannot be taken, so the promise is asserted here rather than restated in comments.
 */

afterEach(() => vi.unstubAllGlobals());

function respondWith(body: BodyInit | null, init: ResponseInit): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(body, init))),
  );
}

describe('every rejection is an ApiError', () => {
  /**
   * The one way through that was not closed.
   *
   * A 2xx whose body is not JSON threw a bare `SyntaxError` out of `request` — no `code`, no
   * `message` written for a human, and nothing a screen's error branch recognises. `Content-Length`
   * does not close it: a chunked response does not carry one, and neither does a `Response` built
   * from a string.
   */
  it('turns a success whose body cannot be read into one', async () => {
    respondWith('<html>upstream said something else</html>', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });

    const failure = await api.get('/business').catch((cause: unknown) => cause);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).code).toBe('INTERNAL_ERROR');
    expect((failure as ApiError).status).toBe(200);
  });

  it('turns an empty success body into one too', async () => {
    respondWith('', { status: 200 });

    const failure = await api.post('/business/faqs', {}).catch((cause: unknown) => cause);

    expect(failure).toBeInstanceOf(ApiError);
  });

  it('turns a dead connection into one', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))),
    );

    const failure = await api.get('/business').catch((cause: unknown) => cause);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).code).toBe('NETWORK_ERROR');
  });
});

describe('what must keep working', () => {
  it('reads a body that is JSON', async () => {
    respondWith(JSON.stringify({ name: 'Salon Aria' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });

    await expect(api.get('/business')).resolves.toEqual({ name: 'Salon Aria' });
  });

  /** A `DELETE` answers `204`, and asking that for JSON is what the status is there to prevent. */
  it('returns nothing for a 204 rather than trying to read it', async () => {
    // `null`, not `''` — a 204 may not carry a body at all, and `new Response('', …)`
    // refuses to build one.
    respondWith(null, { status: 204 });

    await expect(api.delete('/business/faqs/faq-1')).resolves.toBeUndefined();
  });

  /** The failure the fix must not swallow: a refusal that named itself. */
  it("keeps the server's own code on a refusal", async () => {
    respondWith(JSON.stringify({ code: 'SLUG_TAKEN', detail: 'That address is taken.' }), {
      status: 409,
      headers: { 'Content-Type': 'application/problem+json' },
    });

    const failure = await api.patch('/business', {}).catch((cause: unknown) => cause);

    expect((failure as ApiError).code).toBe('SLUG_TAKEN');
    expect((failure as ApiError).message).toBe('That address is taken.');
  });
});
