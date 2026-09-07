import { cn } from './cn';

export function Table({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <div className="border-border bg-surface overflow-x-auto rounded-lg border">
      <table className={cn('w-full border-collapse text-sm', className)}>{children}</table>
    </div>
  );
}

export function Th({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <th
      scope="col"
      className={cn(
        'border-border text-ink-muted border-b px-4 py-2.5 text-left text-xs font-medium tracking-wide uppercase',
        className,
      )}
    >
      {children}
    </th>
  );
}

export function Td({ children, className }: { children: React.ReactNode; className?: string }) {
  return <td className={cn('border-border text-ink border-b px-4 py-3', className)}>{children}</td>;
}
