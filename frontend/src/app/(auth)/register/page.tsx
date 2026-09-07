import type { Metadata } from 'next';
import Link from 'next/link';
import { RegisterForm } from './register-form';

export const metadata: Metadata = { title: 'Create your business · Reception' };

export default function RegisterPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-ink text-2xl font-semibold tracking-tight">Create your business</h1>
        <p className="text-ink-muted mt-1 text-sm">
          One step. You can change everything afterwards.
        </p>
      </div>

      <RegisterForm />

      <p className="text-ink-muted text-sm">
        Already have an account?{' '}
        <Link href="/login" className="text-brand font-medium hover:underline">
          Sign in
        </Link>
      </p>
    </div>
  );
}
