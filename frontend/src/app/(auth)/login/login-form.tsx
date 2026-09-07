'use client';

import { useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Button, Card, Input } from '@/components/ui';
import { ApiError, api } from '@/lib/api/client';
import { useSession, type Session } from '@/lib/auth';

export function LoginForm() {
  const router = useRouter();
  const { adopt } = useSession();
  // Set by the dashboard guard when it turned an unauthenticated visitor away. Only same-site
  // paths are honoured: an absolute URL here would make the login page an open redirect.
  const requestedNext = useSearchParams().get('next');
  const next =
    requestedNext?.startsWith('/') && !requestedNext.startsWith('//')
      ? requestedNext
      : '/dashboard';
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);

    const form = new FormData(event.currentTarget);
    try {
      const session = await api.post<Session>('/auth/login', {
        email: String(form.get('email') ?? ''),
        password: String(form.get('password') ?? ''),
      });
      // Adopting the response avoids a second round trip to /auth/me on the next screen.
      adopt(session);
      router.replace(next);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      setSubmitting(false);
    }
  }

  return (
    <Card>
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {/*
          The server answers a wrong password and an unknown address identically, and so does this
          screen. Splitting them here would undo the reason the API does not (docs/06-security.md §2).
        */}
        {error && (
          <p
            role="alert"
            className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
          >
            {error.message}
          </p>
        )}

        <Input
          label="Email"
          name="email"
          type="email"
          autoComplete="email"
          required
          error={error?.fieldErrors.email}
        />
        <Input
          label="Password"
          name="password"
          type="password"
          autoComplete="current-password"
          required
          error={error?.fieldErrors.password}
        />

        <Button type="submit" loading={submitting} className="mt-2">
          Sign in
        </Button>
      </form>
    </Card>
  );
}
