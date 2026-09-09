'use client';

import { useState } from 'react';
import { Button, Card, CardHeader, Input, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { changedFields } from '@/lib/forms/changed-fields';
import { customerApi, type CustomerDetail, type PatchCustomer } from '@/lib/customers';

type Values = { fullName: string; email: string };

function valuesOf(customer: CustomerDetail): Values {
  return { fullName: customer.fullName, email: customer.email ?? '' };
}

/**
 * Correcting a name or an email — the only two things about a customer that can be changed.
 *
 * **The phone number is not editable, and this is the screen where that has to be explained rather
 * than merely enforced.** It is half of `(business, phone)`, so changing it would either collide
 * with another customer or silently move one person's history onto a number belonging to somebody
 * else. Booking under the right number creates the right customer, which is the supported fix and
 * the only one that leaves both histories intact.
 *
 * A name typed differently at booking does not overwrite the stored one either — the appointment
 * records the name it was given. Someone booking for a family member must not rename the account
 * holder, so the correction is deliberate and happens here.
 */
export function CustomerForm({
  customer,
  onSaved,
}: {
  customer: CustomerDetail;
  onSaved: (updated: CustomerDetail) => void;
}) {
  const toast = useToast();
  const [values, setValues] = useState<Values>(valuesOf(customer));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [localErrors, setLocalErrors] = useState<Partial<Record<keyof Values, string>>>({});

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next: Values = { fullName: values.fullName.trim(), email: values.email.trim() };

    if (next.fullName === '') {
      setLocalErrors({ fullName: 'Enter a name.' });
      setError(null);
      return;
    }
    setLocalErrors({});

    const patch: PatchCustomer = changedFields(valuesOf(customer), next);
    if (Object.keys(patch).length === 0) {
      toast('Nothing to save — nothing has changed.', 'info');
      return;
    }

    setSaving(true);
    setError(null);
    try {
      const saved = await customerApi.patch(customer.id, patch);
      onSaved(saved.customer);
      toast('Your changes have been saved.', 'success');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('The customer could not be saved.', 'error');
    } finally {
      setSaving(false);
    }
  }

  const fieldError = (field: keyof Values): string | undefined =>
    localErrors[field] ?? error?.fieldErrors[field];

  const everyMessageShown =
    error !== null &&
    Object.keys(error.fieldErrors).length > 0 &&
    Object.keys(error.fieldErrors).every((field) => field === 'fullName' || field === 'email');
  const unfielded = error && !everyMessageShown ? error.message : null;

  return (
    <Card>
      <CardHeader
        title="Details"
        description="Corrections only — this is the same person however many times they book."
      />
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {unfielded && (
          <p
            role="alert"
            className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
          >
            {unfielded}
          </p>
        )}

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input
            label="Full name"
            value={values.fullName}
            maxLength={120}
            required
            onChange={(event) => setValues((c) => ({ ...c, fullName: event.target.value }))}
            error={fieldError('fullName')}
          />
          <Input
            label="Email"
            type="email"
            value={values.email}
            maxLength={254}
            onChange={(event) => setValues((c) => ({ ...c, email: event.target.value }))}
            hint="Optional."
            error={fieldError('email')}
          />
        </div>

        <Input
          label="Phone"
          value={customer.phone}
          readOnly
          disabled
          hint="This is how the customer is identified, so it cannot be edited. Booking under a different number creates a different customer — which is the right answer when the number really has changed hands."
        />

        <div>
          <Button type="submit" loading={saving}>
            Save changes
          </Button>
        </div>
      </form>
    </Card>
  );
}
