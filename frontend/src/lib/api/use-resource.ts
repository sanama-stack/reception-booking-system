'use client';

import { useCallback, useEffect, useState } from 'react';
import { ApiError, api } from './client';

export interface Resource<T> {
  data: T | null;
  error: ApiError | null;
  /** True only for the first load. A reload keeps the current data on screen. */
  loading: boolean;
  reload: () => Promise<void>;
  /** Adopts a body a write already returned, so a save needs no second round trip. */
  set: (next: T) => void;
}

/**
 * One GET, with the loading and error states every screen owes its user
 * (docs/09-phase-plan.md §5, rule 7).
 *
 * The argument is a path rather than a fetcher function, deliberately: a fetcher would be a new
 * identity on every render, and the effect that depends on it would re-fetch forever unless every
 * caller remembered `useCallback`. A string cannot be got wrong that way.
 */
export function useResource<T>(path: string): Resource<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const body = await api.get<T>(path, signal ? { signal } : undefined);
        if (signal?.aborted) return;
        setData(body);
        setError(null);
      } catch (cause) {
        // An abort is this component going away, not a failure to report to anyone.
        if (signal?.aborted) return;
        setError(
          cause instanceof ApiError
            ? cause
            : new ApiError({ code: 'INTERNAL_ERROR', message: 'Something went wrong.', status: 0 }),
        );
      } finally {
        if (!signal?.aborted) setLoading(false);
      }
    },
    [path],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  return {
    data,
    error,
    loading,
    reload: useCallback(() => load(), [load]),
    set: useCallback((next: T) => {
      setData(next);
      setError(null);
    }, []),
  };
}
