'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { Button, Card, Input } from '@/components/ui';
import { ApiError, api } from '@/lib/api/client';
import { DEFAULT_SIGNED_IN_PATH, useSession, type Session } from '@/lib/auth';

/** Mirrors the server rule, so the common mistake is caught before a round trip. */
const MINIMUM_PASSWORD_LENGTH = 10;

export function RegisterForm() {
  const router = useRouter();
  const { adopt } = useSession();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);

    const form = new FormData(event.currentTarget);
    try {
      const session = await api.post<Session>('/auth/register', {
        email: String(form.get('email') ?? ''),
        password: String(form.get('password') ?? ''),
        fullName: String(form.get('fullName') ?? ''),
        businessName: String(form.get('businessName') ?? ''),
      });
      adopt(session);
      router.replace(DEFAULT_SIGNED_IN_PATH);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      setSubmitting(false);
    }
  }

  // Field-level messages are rendered against their field; anything without a field goes to the
  // summary, so a server message can never be silently dropped.
  const unfieldedMessage =
    error && Object.keys(error.fieldErrors).length === 0 ? error.message : null;

  return (
    <Card>
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {unfieldedMessage && (
          <p
            role="alert"
            className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
          >
            {unfieldedMessage}
          </p>
        )}

        <Input
          label="Business name"
          name="businessName"
          autoComplete="organization"
          required
          maxLength={120}
          hint="This becomes your public booking page address."
          error={error?.fieldErrors.businessName}
        />
        <Input
          label="Your name"
          name="fullName"
          autoComplete="name"
          required
          maxLength={120}
          error={error?.fieldErrors.fullName}
        />
        <Input
          label="Email"
          name="email"
          type="email"
          autoComplete="email"
          required
          maxLength={254}
          error={error?.fieldErrors.email}
        />
        <Input
          label="Password"
          name="password"
          type="password"
          autoComplete="new-password"
          required
          minLength={MINIMUM_PASSWORD_LENGTH}
          // Length is the only rule. Requiring a symbol and a digit pushes people towards
          // "Password1!", which is worse (docs/01-prd.md FR-1).
          hint={`At least ${MINIMUM_PASSWORD_LENGTH} characters.`}
          error={error?.fieldErrors.password}
        />

        <Button type="submit" loading={submitting} className="mt-2">
          Create business
        </Button>
      </form>
    </Card>
  );
}
