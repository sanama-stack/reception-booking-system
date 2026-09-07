'use client';

import Link from 'next/link';
import { EmployeeForm } from '../employee-form';

export default function NewEmployeePage() {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <Link href="/employees" className="text-ink-muted text-sm hover:underline">
          ← Employees
        </Link>
        <h1 className="text-ink mt-2 text-2xl font-semibold tracking-tight">New employee</h1>
        <p className="text-ink-muted mt-1 text-sm">
          Just who they are for now. What they provide and when they work come next, on their own
          page.
        </p>
      </div>

      <EmployeeForm employee={null} onSaved={() => undefined} />
    </div>
  );
}
