import { Suspense } from 'react';
import type { Metadata } from 'next';
import Link from 'next/link';
import { Spinner } from '@/components/ui';
import { LoginForm } from './login-form';

export const metadata: Metadata = { title: 'Sign in · Reception' };

export default function LoginPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-ink text-2xl font-semibold tracking-tight">Sign in</h1>
        <p className="text-ink-muted mt-1 text-sm">Manage your bookings, staff and hours.</p>
      </div>

      {/* The form reads ?next= from the URL, which Next requires a boundary for. */}
      <Suspense fallback={<Spinner className="text-ink-muted size-5" />}>
        <LoginForm />
      </Suspense>

      <p className="text-ink-muted text-sm">
        No account yet?{' '}
        <Link href="/register" className="text-brand font-medium hover:underline">
          Create one
        </Link>
      </p>
    </div>
  );
}
