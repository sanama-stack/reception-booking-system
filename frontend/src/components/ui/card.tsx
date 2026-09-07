import { cn } from './cn';

export function Card({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <section className={cn('border-border bg-surface rounded-lg border p-6 shadow-sm', className)}>
      {children}
    </section>
  );
}

export function CardHeader({ title, description }: { title: string; description?: string }) {
  return (
    <header className="mb-4">
      <h2 className="text-ink text-base font-semibold">{title}</h2>
      {description && <p className="text-ink-muted mt-1 text-sm">{description}</p>}
    </header>
  );
}
