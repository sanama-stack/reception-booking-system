import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'Reception',
  description: 'Appointment booking with an AI receptionist.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-dvh antialiased">{children}</body>
    </html>
  );
}
