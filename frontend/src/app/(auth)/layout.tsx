import Link from 'next/link';
import { GuestGuard } from './guest-guard';

/** The signed-out surface: one centred card, nothing to navigate away into. */
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <GuestGuard>
      <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col justify-center gap-8 px-6 py-12">
        <Link href="/" className="text-brand text-sm font-semibold tracking-wide uppercase">
          Reception
        </Link>
        {children}
      </main>
    </GuestGuard>
  );
}
