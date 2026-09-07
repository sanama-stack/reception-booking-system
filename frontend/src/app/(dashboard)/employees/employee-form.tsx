'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Button, ButtonLink, Card, CardHeader, Input, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { changedFields } from '@/lib/forms/changed-fields';
import { employeeApi, type EmployeeDetail, type EmployeePatch } from '@/lib/staff';

type Values = {
  fullName: string;
  email: string;
  phone: string;
  jobTitle: string;
};

const BLANK: Values = { fullName: '', email: '', phone: '', jobTitle: '' };

function valuesOf(employee: EmployeeDetail): Values {
  return {
    fullName: employee.fullName,
    email: employee.email ?? '',
    phone: employee.phone ?? '',
    jobTitle: employee.jobTitle ?? '',
  };
}

/**
 * The person themselves. Create and edit are one form, for the same reason the service form is.
 *
 * Their service assignments, their Working Schedule and their Time Off are not here: they need an
 * employee to exist before they can refer to one, so they live on the detail screen a create sends
 * the owner to.
 */
export function EmployeeForm({
  employee,
  onSaved,
}: {
  /** `null` creates. */
  employee: EmployeeDetail | null;
  onSaved: (updated: EmployeeDetail) => void;
}) {
  const router = useRouter();
  const toast = useToast();
  const [values, setValues] = useState<Values>(employee === null ? BLANK : valuesOf(employee));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [localErrors, setLocalErrors] = useState<Partial<Record<keyof Values, string>>>({});

  function set<K extends keyof Values>(field: K, value: Values[K]) {
    setValues((current) => ({ ...current, [field]: value }));
  }

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const next: Values = {
      fullName: values.fullName.trim(),
      email: values.email.trim(),
      // Left exactly as typed. The server normalises it to E.164 against the business's country,
      // and trimming spacing here would be the first step of a second normaliser.
      phone: values.phone,
      jobTitle: values.jobTitle.trim(),
    };

    if (next.fullName === '') {
      setLocalErrors({ fullName: 'Enter a name.' });
      setError(null);
      return;
    }
    setLocalErrors({});

    setSaving(true);
    setError(null);
    try {
      if (employee === null) {
        const created = await employeeApi.create({
          fullName: next.fullName,
          ...(next.email === '' ? {} : { email: next.email }),
          ...(next.phone.trim() === '' ? {} : { phone: next.phone }),
          ...(next.jobTitle === '' ? {} : { jobTitle: next.jobTitle }),
        });
        toast(
          `${created.fullName} has been added. Now say what they do and when they work.`,
          'success',
        );
        router.push(`/employees/${created.id}`);
        return;
      }

      const patch: EmployeePatch = changedFields(valuesOf(employee), next);
      if (Object.keys(patch).length === 0) {
        toast('Nothing to save — nothing has changed.', 'info');
        return;
      }
      onSaved(await employeeApi.patch(employee.id, patch));
      toast('Your changes have been saved.', 'success');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('The employee could not be saved.', 'error');
    } finally {
      setSaving(false);
    }
  }

  const fieldError = (field: keyof Values): string | undefined =>
    localErrors[field] ?? error?.fieldErrors[field];

  const rendered = new Set<string>(Object.keys(values));
  const everyMessageShown =
    error !== null &&
    Object.keys(error.fieldErrors).length > 0 &&
    Object.keys(error.fieldErrors).every((field) => rendered.has(field));
  const unfielded = error && !everyMessageShown ? error.message : null;

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-6" noValidate>
      {unfielded && (
        <p
          role="alert"
          className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
        >
          {unfielded}
        </p>
      )}

      <Card>
        <CardHeader
          title="Who they are"
          description="The name is what customers see when they choose who to book with."
        />
        <div className="flex flex-col gap-4">
          <Input
            label="Full name"
            value={values.fullName}
            maxLength={120}
            required
            onChange={(event) => set('fullName', event.target.value)}
            error={fieldError('fullName')}
          />
          <Input
            label="Job title"
            value={values.jobTitle}
            maxLength={120}
            onChange={(event) => set('jobTitle', event.target.value)}
            hint="Optional — Stylist, Senior Consultant."
            error={fieldError('jobTitle')}
          />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input
              label="Email"
              type="email"
              value={values.email}
              maxLength={254}
              onChange={(event) => set('email', event.target.value)}
              hint="Optional. Not a login — staff sign-in comes later."
              error={fieldError('email')}
            />
            <Input
              label="Phone"
              type="tel"
              value={values.phone}
              maxLength={40}
              onChange={(event) => set('phone', event.target.value)}
              hint="Optional. A local number works once your country is set under Settings; otherwise start with +."
              error={fieldError('phone')}
            />
          </div>
        </div>
      </Card>

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" loading={saving}>
          {employee === null ? 'Add employee' : 'Save changes'}
        </Button>
        <ButtonLink href="/employees" variant="secondary">
          Cancel
        </ButtonLink>
      </div>
    </form>
  );
}
