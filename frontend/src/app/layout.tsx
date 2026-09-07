import type { Metadata } from 'next';
import { DevOriginGuard } from '@/components/dev/origin-guard';
import { ToastProvider } from '@/components/ui';
import { SessionProvider } from '@/lib/auth';
import './globals.css';

export const metadata: Metadata = {
  title: 'Reception',
  description: 'Appointment booking with an AI receptionist.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-dvh antialiased">
        {/*
          Development only: the bundler replaces `process.env.NODE_ENV`, so this folds to
          `false && …` and never renders in production. The component itself also guards on it,
          which is what actually strips its logic and copy from the bundle — see origin-guard.tsx.
        */}
        {process.env.NODE_ENV === 'development' && <DevOriginGuard />}
        <SessionProvider>
          <ToastProvider>{children}</ToastProvider>
        </SessionProvider>
      </body>
    </html>
  );
}
