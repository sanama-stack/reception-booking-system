'use client';

import { useState } from 'react';
import {
  Button,
  Card,
  CardHeader,
  ConfirmDialog,
  Input,
  Textarea,
  useToast,
} from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { useSession } from '@/lib/auth';
import {
  availableCurrencies,
  availableTimezones,
  businessApi,
  changedFields,
  timezoneLabel,
  type BusinessPatch,
  type BusinessProfile,
} from '@/lib/business';
import { isValidTimezone } from '@/lib/time';

/** Every field on this screen, as the form holds them: text, with `''` for "not set". */
type Values = {
  name: string;
  slug: string;
  timezone: string;
  currency: string;
  description: string;
  addressLine: string;
  city: string;
  country: string;
  phone: string;
  email: string;
  website: string;
};

/**
 * The fields that also live in the session, hydrated from `/auth/me`.
 *
 * Changing one of these makes the cached session stale — the sidebar would keep showing the old
 * business name, and the booking URL the old slug, until the next full page load. The reload is
 * therefore part of saving, not a nicety.
 */
const SESSION_FIELDS: ReadonlyArray<keyof Values> = ['name', 'slug', 'timezone', 'currency'];

function valuesOf(profile: BusinessProfile): Values {
  return {
    name: profile.name,
    slug: profile.slug,
    timezone: profile.timezone,
    currency: profile.currency,
    description: profile.description ?? '',
    addressLine: profile.addressLine ?? '',
    city: profile.city ?? '',
    country: profile.country ?? '',
    phone: profile.phone ?? '',
    email: profile.email ?? '',
    website: profile.website ?? '',
  };
}

export function ProfileForm({
  profile,
  onSaved,
}: {
  profile: BusinessProfile;
  onSaved: (updated: BusinessProfile) => void;
}) {
  const toast = useToast();
  const { reload } = useSession();
  const [values, setValues] = useState<Values>(() => valuesOf(profile));
  const [error, setError] = useState<ApiError | null>(null);
  const [localErrors, setLocalErrors] = useState<Partial<Record<keyof Values, string>>>({});
  const [saving, setSaving] = useState(false);
  const [pendingTimezone, setPendingTimezone] = useState<BusinessPatch | null>(null);

  const zones = availableTimezones();

  function set<K extends keyof Values>(field: K, value: Values[K]) {
    setValues((current) => ({ ...current, [field]: value }));
  }

  async function save(patch: BusinessPatch) {
    setSaving(true);
    setError(null);
    try {
      const updated = await businessApi.patch(patch);
      // Any of the four session-carried fields having changed means the cached session is now
      // describing a business that no longer exists in that shape.
      if (SESSION_FIELDS.some((field) => field in patch)) await reload();
      onSaved(updated);
      toast('Your business profile has been saved.', 'success');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('The profile could not be saved.', 'error');
    } finally {
      setSaving(false);
      setPendingTimezone(null);
    }
  }

  function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLocalErrors({});

    // Checked here rather than left to the server so a rejected zone never gets as far as the
    // confirmation dialog — being asked to confirm a change that then fails is a worse order.
    if (values.timezone !== '' && !isValidTimezone(values.timezone)) {
      setLocalErrors({ timezone: 'That is not a timezone we recognise. Pick one from the list.' });
      return;
    }

    const patch = changedFields(profile, values);
    if (Object.keys(patch).length === 0) {
      toast('Nothing to save — no fields have changed.', 'info');
      return;
    }

    // ADR-0003: the stored instants do not move, but every time on every screen does. The owner is
    // told that before it happens, not after they notice their appointments look wrong.
    if (patch.timezone !== undefined) {
      setPendingTimezone(patch);
      return;
    }

    void save(patch);
  }

  const fieldError = (field: keyof Values): string | undefined =>
    localErrors[field] ??
    error?.fieldErrors[field] ??
    // SLUG_TAKEN carries no `errors` entry — it is a conflict, not a shape violation — but the
    // owner needs it on the field they just typed rather than in a banner above the form.
    (field === 'slug' && error?.code === 'SLUG_TAKEN' ? error.message : undefined);

  // A server message must never be silently dropped. It goes in the banner unless every part of it
  // is already showing against a field on this form — which covers both the error that named no
  // field at all, and the one that named a field this form does not render.
  const rendered = new Set<string>(Object.keys(values));
  const everyMessageShown =
    error !== null &&
    (error.code === 'SLUG_TAKEN' ||
      (Object.keys(error.fieldErrors).length > 0 &&
        Object.keys(error.fieldErrors).every((field) => rendered.has(field))));
  const unfielded = error && !everyMessageShown ? error.message : null;

  return (
    <>
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
            title="Identity"
            description="What customers see, and the address your booking page lives at."
          />
          <div className="flex flex-col gap-4">
            <Input
              label="Business name"
              value={values.name}
              onChange={(event) => set('name', event.target.value)}
              maxLength={120}
              required
              error={fieldError('name')}
            />
            <Input
              label="Booking page address"
              value={values.slug}
              onChange={(event) => set('slug', event.target.value)}
              maxLength={140}
              required
              hint={`Your page will be at /book/${values.slug || '…'}. Changing it stops the old address working.`}
              error={fieldError('slug')}
            />
            <Textarea
              label="Description"
              value={values.description}
              onChange={(event) => set('description', event.target.value)}
              maxLength={5000}
              rows={4}
              hint="Shown on your booking page. The Receptionist can use it to answer questions."
              error={fieldError('description')}
            />
          </div>
        </Card>

        <Card>
          <CardHeader
            title="Timezone and currency"
            description="Every time in Reception is shown in your business's timezone."
          />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input
              label="Timezone"
              value={values.timezone}
              onChange={(event) => set('timezone', event.target.value)}
              list="timezone-options"
              maxLength={64}
              required
              hint="An IANA zone, such as Asia/Tbilisi."
              error={fieldError('timezone')}
            />
            <Input
              label="Currency"
              value={values.currency}
              onChange={(event) => set('currency', event.target.value.toUpperCase())}
              list="currency-options"
              maxLength={3}
              required
              hint="A three-letter code, such as GEL or USD."
              error={fieldError('currency')}
            />
          </div>
          {/* Offered from the runtime's own tzdata. A browser without `Intl.supportedValuesOf`
              gets an empty list and a plain text field, which still works — the server is what
              decides whether a zone is real. */}
          <datalist id="timezone-options">
            {zones.map((zone) => (
              <option key={zone} value={zone}>
                {timezoneLabel(zone)}
              </option>
            ))}
          </datalist>
          <datalist id="currency-options">
            {availableCurrencies().map((code) => (
              <option key={code} value={code} />
            ))}
          </datalist>
        </Card>

        <Card>
          <CardHeader
            title="Where you are"
            description="Shown to customers on your booking page."
          />
          <div className="flex flex-col gap-4">
            <Input
              label="Address"
              value={values.addressLine}
              onChange={(event) => set('addressLine', event.target.value)}
              maxLength={200}
              autoComplete="street-address"
              error={fieldError('addressLine')}
            />
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Input
                label="City"
                value={values.city}
                onChange={(event) => set('city', event.target.value)}
                maxLength={120}
                autoComplete="address-level2"
                error={fieldError('city')}
              />
              <Input
                label="Country"
                value={values.country}
                onChange={(event) => set('country', event.target.value.toUpperCase())}
                maxLength={2}
                autoComplete="country"
                hint="Two-letter code, such as GE or US."
                error={fieldError('country')}
              />
            </div>
          </div>
        </Card>

        <Card>
          <CardHeader
            title="How to reach you"
            description="For customers who would rather call, and for your confirmation emails."
          />
          <div className="flex flex-col gap-4">
            <Input
              label="Phone"
              value={values.phone}
              onChange={(event) => set('phone', event.target.value)}
              maxLength={20}
              type="tel"
              autoComplete="tel"
              error={fieldError('phone')}
            />
            <Input
              label="Email"
              value={values.email}
              onChange={(event) => set('email', event.target.value)}
              maxLength={254}
              type="email"
              error={fieldError('email')}
            />
            <Input
              label="Website"
              value={values.website}
              onChange={(event) => set('website', event.target.value)}
              maxLength={300}
              type="url"
              error={fieldError('website')}
            />
          </div>
        </Card>

        <div className="flex items-center gap-3">
          <Button type="submit" loading={saving}>
            Save changes
          </Button>
          <p className="text-ink-muted text-sm">Leave a field empty to clear it.</p>
        </div>
      </form>

      <ConfirmDialog
        open={pendingTimezone !== null}
        title="Change your timezone?"
        confirmLabel="Change timezone"
        busy={saving}
        onConfirm={() => pendingTimezone && void save(pendingTimezone)}
        onCancel={() => !saving && setPendingTimezone(null)}
      >
        <p>
          You are moving from <strong>{profile.timezone}</strong> to{' '}
          <strong>{values.timezone}</strong>.
        </p>
        <p>
          No appointment moves. Every time you see in Reception does — an appointment now shown at
          09:00 will be shown at a different hour, because it is the same moment described from a
          different place.
        </p>
        <p>Change this when the business has moved, not to correct how a time reads.</p>
      </ConfirmDialog>
    </>
  );
}
