'use client';

import { useState } from 'react';
import { Button, Card, CardHeader, Input, Select, Textarea, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { changedFields } from '@/lib/forms/changed-fields';
import { businessApi, type BusinessPatch, type BusinessProfile } from '@/lib/business';

/** The grid the availability engine offers start times on. A choice, not a range. */
const SLOT_INTERVALS = [5, 10, 15, 20, 30, 60];

type Values = {
  slotIntervalMinutes: string;
  minLeadTimeMinutes: string;
  maxAdvanceDays: string;
  cancellationWindowHours: string;
  cancellationPolicy: string;
};

const NUMERIC_FIELDS = [
  'slotIntervalMinutes',
  'minLeadTimeMinutes',
  'maxAdvanceDays',
  'cancellationWindowHours',
] as const;

function valuesOf(profile: BusinessProfile): Values {
  return {
    slotIntervalMinutes: String(profile.slotIntervalMinutes),
    minLeadTimeMinutes: String(profile.minLeadTimeMinutes),
    maxAdvanceDays: String(profile.maxAdvanceDays),
    cancellationWindowHours: String(profile.cancellationWindowHours),
    cancellationPolicy: profile.cancellationPolicy ?? '',
  };
}

export function BookingForm({
  profile,
  onSaved,
}: {
  profile: BusinessProfile;
  onSaved: (updated: BusinessProfile) => void;
}) {
  const toast = useToast();
  const [values, setValues] = useState<Values>(() => valuesOf(profile));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [localErrors, setLocalErrors] = useState<Partial<Record<keyof Values, string>>>({});

  function set<K extends keyof Values>(field: K, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
  }

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();

    // A number field left empty cannot be sent — the server has no "unset" for these, and `NaN`
    // would serialise as `null`, which means "leave alone" and would silently do nothing.
    const blanks: Partial<Record<keyof Values, string>> = {};
    for (const field of NUMERIC_FIELDS) {
      if (values[field].trim() === '' || !Number.isFinite(Number(values[field]))) {
        blanks[field] = 'Enter a number.';
      }
    }
    setLocalErrors(blanks);
    if (Object.keys(blanks).length > 0) return;

    const patch = changedFields(profile, {
      slotIntervalMinutes: Number(values.slotIntervalMinutes),
      minLeadTimeMinutes: Number(values.minLeadTimeMinutes),
      maxAdvanceDays: Number(values.maxAdvanceDays),
      cancellationWindowHours: Number(values.cancellationWindowHours),
      cancellationPolicy: values.cancellationPolicy,
    } satisfies BusinessPatch);

    if (Object.keys(patch).length === 0) {
      toast('Nothing to save — no settings have changed.', 'info');
      return;
    }

    setSaving(true);
    setError(null);
    try {
      onSaved(await businessApi.patch(patch));
      toast('Your booking settings have been saved.', 'success');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('The settings could not be saved.', 'error');
    } finally {
      setSaving(false);
    }
  }

  const fieldError = (field: keyof Values): string | undefined =>
    localErrors[field] ?? error?.fieldErrors[field];

  const rendered = new Set<string>(Object.keys(values));
  const unfielded =
    error &&
    !(
      Object.keys(error.fieldErrors).length > 0 &&
      Object.keys(error.fieldErrors).every((field) => rendered.has(field))
    )
      ? error.message
      : null;

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
          title="When customers can book"
          description="These decide which times a customer is offered, and how far ahead."
        />
        <div className="flex flex-col gap-6">
          <Setting effect={slotEffect(values.slotIntervalMinutes)}>
            <Select
              label="Slot interval"
              value={values.slotIntervalMinutes}
              onChange={(event) => set('slotIntervalMinutes', event.target.value)}
              error={fieldError('slotIntervalMinutes')}
            >
              {SLOT_INTERVALS.map((minutes) => (
                <option key={minutes} value={minutes}>
                  {minutes} minutes
                </option>
              ))}
            </Select>
          </Setting>

          <Setting effect={leadTimeEffect(values.minLeadTimeMinutes)}>
            <Input
              label="Minimum notice"
              type="number"
              inputMode="numeric"
              min={0}
              max={10080}
              value={values.minLeadTimeMinutes}
              onChange={(event) => set('minLeadTimeMinutes', event.target.value)}
              hint="In minutes, up to one week."
              error={fieldError('minLeadTimeMinutes')}
            />
          </Setting>

          <Setting effect={horizonEffect(values.maxAdvanceDays)}>
            <Input
              label="How far ahead"
              type="number"
              inputMode="numeric"
              min={1}
              max={365}
              value={values.maxAdvanceDays}
              onChange={(event) => set('maxAdvanceDays', event.target.value)}
              hint="In days, up to a year."
              error={fieldError('maxAdvanceDays')}
            />
          </Setting>
        </div>
      </Card>

      <Card>
        <CardHeader
          title="Cancellations"
          description="What a customer may do themselves, and what they are told about it."
        />
        <div className="flex flex-col gap-6">
          <Setting effect={cancellationEffect(values.cancellationWindowHours)}>
            <Input
              label="Cancellation window"
              type="number"
              inputMode="numeric"
              min={0}
              max={168}
              value={values.cancellationWindowHours}
              onChange={(event) => set('cancellationWindowHours', event.target.value)}
              hint="In hours before the appointment, up to one week."
              error={fieldError('cancellationWindowHours')}
            />
          </Setting>

          <Setting effect="Shown on your booking page and in confirmation emails. Leave it empty if you would rather not state one.">
            <Textarea
              label="Cancellation policy"
              value={values.cancellationPolicy}
              onChange={(event) => set('cancellationPolicy', event.target.value)}
              maxLength={5000}
              rows={4}
              error={fieldError('cancellationPolicy')}
            />
          </Setting>
        </div>
      </Card>

      <div>
        <Button type="submit" loading={saving}>
          Save booking settings
        </Button>
      </div>
    </form>
  );
}

/**
 * A setting and what it does, side by side.
 *
 * The effect is written out because none of these numbers explains itself: "minimum notice: 60"
 * is a fact about a database column, and "someone booking now would see nothing before 10:00" is
 * a fact about the business. The second is what the owner is actually deciding.
 */
function Setting({ effect, children }: { effect: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
      {children}
      <p className="text-ink-muted text-sm sm:pt-7">{effect}</p>
    </div>
  );
}

/** `90` → `1 hour 30 minutes`. Plural-aware, because "1 hours" reads as a bug. */
function humaniseMinutes(total: number): string {
  if (total === 0) return 'no notice';
  const hours = Math.floor(total / 60);
  const minutes = total % 60;
  const parts: string[] = [];
  if (hours > 0) parts.push(`${hours} ${hours === 1 ? 'hour' : 'hours'}`);
  if (minutes > 0) parts.push(`${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`);
  return parts.join(' ');
}

function slotEffect(value: string): string {
  const minutes = Number(value);
  if (!Number.isFinite(minutes) || minutes <= 0) return 'Start times are offered on a fixed grid.';
  const examples = [0, minutes, minutes * 2].map(
    (offset) => `09:${String(offset).padStart(2, '0')}`,
  );
  return minutes >= 60
    ? 'Start times are offered on the hour: 09:00, 10:00, 11:00 and so on.'
    : `Start times are offered every ${minutes} minutes: ${examples.join(', ')} and so on.`;
}

function leadTimeEffect(value: string): string {
  const minutes = Number(value);
  if (!Number.isFinite(minutes)) return '';
  return minutes === 0
    ? 'Customers can book a slot that is about to start.'
    : `A customer booking now would be offered nothing sooner than ${humaniseMinutes(minutes)} from now.`;
}

function horizonEffect(value: string): string {
  const days = Number(value);
  if (!Number.isFinite(days)) return '';
  return `Nothing more than ${days} ${days === 1 ? 'day' : 'days'} away appears on your booking page, however free you are.`;
}

function cancellationEffect(value: string): string {
  const hours = Number(value);
  if (!Number.isFinite(hours)) return '';
  // The business is exempt from its own window (docs/04-api-overview.md §5), which is worth saying
  // here — an owner reading "24 hours" could reasonably think it binds them too.
  return hours === 0
    ? 'Customers can cancel at any time, right up to the start. You can always cancel.'
    : `Customers can cancel until ${humaniseMinutes(hours * 60)} before their appointment; after that they have to contact you. You can always cancel.`;
}
