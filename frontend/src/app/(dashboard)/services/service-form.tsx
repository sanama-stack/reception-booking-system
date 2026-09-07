'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { AssignmentPicker } from '@/components/assignment-picker';
import { Button, ButtonLink, Card, CardHeader, Input, Textarea, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { DAYS, type WeekHours } from '@/lib/business';
import { formatDuration, serviceApi, type ServiceDetail, type ServicePatch } from '@/lib/catalog';
import { changedFields } from '@/lib/forms/changed-fields';
import type { EmployeeDetail } from '@/lib/staff';
import { formatMoney } from '@/lib/time';

/** Every field on this screen, as the form holds them: text, with `''` for "not set". */
type Values = {
  name: string;
  description: string;
  durationMinutes: string;
  bufferBeforeMinutes: string;
  bufferAfterMinutes: string;
  price: string;
};

/** What a new service starts as. Half an hour, no padding, and a price the owner must state. */
const BLANK: Values = {
  name: '',
  description: '',
  durationMinutes: '30',
  bufferBeforeMinutes: '0',
  bufferAfterMinutes: '0',
  price: '',
};

function valuesOf(service: ServiceDetail): Values {
  return {
    name: service.name,
    description: service.description ?? '',
    durationMinutes: String(service.durationMinutes),
    bufferBeforeMinutes: String(service.bufferBeforeMinutes),
    bufferAfterMinutes: String(service.bufferAfterMinutes),
    // The nested `price` is why the diff runs against this projection rather than against the
    // service itself: `{ amount, currency }` on the way in, a bare decimal string on the way out.
    price: service.price.amount,
  };
}

/** `09:00` → `540`, or `null` for anything that is not a wall-clock time. */
function minutesOf(time: string): number | null {
  const [hours, minutes] = time.split(':');
  if (hours === undefined || minutes === undefined) return null;
  const total = Number(hours) * 60 + Number(minutes);
  return Number.isFinite(total) ? total : null;
}

/**
 * The longest single stretch the business is open, and the day it falls on.
 *
 * A stretch, not a day's total: two intervals with a lunch break between them cannot hold an
 * appointment that spans both, so summing them would tell the owner a service fits when it does
 * not.
 */
function longestOpenStretch(week: WeekHours): { minutes: number; day: string } | null {
  let longest: { minutes: number; day: string } | null = null;
  for (const entry of week.hours) {
    const opens = minutesOf(entry.opensAt);
    const closes = minutesOf(entry.closesAt);
    if (opens === null || closes === null) continue;
    const length = closes - opens;
    if (longest === null || length > longest.minutes) {
      longest = {
        minutes: length,
        day: DAYS.find((day) => day.value === entry.dayOfWeek)?.label ?? '',
      };
    }
  }
  return longest;
}

function sameSet(a: string[], b: string[]): boolean {
  return a.length === b.length && [...a].sort().join() === [...b].sort().join();
}

/**
 * Create and edit, in one form.
 *
 * A create and an edit differ in two ways only — where the values start, and whether the write is
 * a `POST` or a `PATCH` — so they are one component. Two would be two places to add the next
 * field, and the day one of them was forgotten the form would quietly disagree with itself.
 */
export function ServiceForm({
  service,
  employees,
  week,
  currency,
  onSaved,
}: {
  /** `null` creates. */
  service: ServiceDetail | null;
  employees: EmployeeDetail[];
  /** Only for the long-duration warning; the hours themselves are edited under Settings. */
  week: WeekHours;
  /** The business's currency, shown beside the price on a create — the server stamps it. */
  currency: string;
  onSaved: (updated: ServiceDetail) => void;
}) {
  const router = useRouter();
  const toast = useToast();
  const baseline = service === null ? BLANK : valuesOf(service);
  const [values, setValues] = useState<Values>(baseline);
  const [selected, setSelected] = useState<string[]>(service?.employeeIds ?? []);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [localErrors, setLocalErrors] = useState<Partial<Record<keyof Values, string>>>({});

  function set<K extends keyof Values>(field: K, value: Values[K]) {
    setValues((current) => ({ ...current, [field]: value }));
  }

  /**
   * The warning the phase document asks for: a sentence at the point of the decision, not a
   * refusal (docs/phases/phase-04-services-and-employees.md).
   *
   * It compares the duration alone. The buffers also occupy the calendar, but whether padding may
   * spill past closing time is phase 05's decision and has not been taken — so warning about the
   * total would be asserting an answer this screen does not have.
   */
  const longest = longestOpenStretch(week);
  const duration = Number(values.durationMinutes);
  const tooLongForTheWeek =
    longest !== null && Number.isFinite(duration) && duration > longest.minutes;

  function normalised(): Values {
    const orZero = (value: string) => (value.trim() === '' ? '0' : value.trim());
    return {
      name: values.name.trim(),
      description: values.description,
      durationMinutes: values.durationMinutes.trim(),
      bufferBeforeMinutes: orZero(values.bufferBeforeMinutes),
      bufferAfterMinutes: orZero(values.bufferAfterMinutes),
      price: values.price.trim(),
    };
  }

  /**
   * Only what cannot be sent for the server to judge.
   *
   * The bounds, the five-minute grid, the price scale and the name collision are all the server's,
   * stated once in `ServiceValidation` and reported per field. What is checked here is emptiness
   * and non-numbers: `''` and `abc` do not survive JSON binding, so the owner would get a message
   * about a malformed body rather than about the field they left blank.
   */
  function localProblems(next: Values): Partial<Record<keyof Values, string>> {
    const problems: Partial<Record<keyof Values, string>> = {};
    if (next.name === '') problems.name = 'Enter a name for this service.';
    if (next.durationMinutes === '' || !Number.isFinite(Number(next.durationMinutes))) {
      problems.durationMinutes = 'Enter how long this takes, in minutes.';
    }
    for (const field of ['bufferBeforeMinutes', 'bufferAfterMinutes'] as const) {
      if (!Number.isFinite(Number(next[field]))) {
        problems[field] = 'Enter a number of minutes, or leave it at 0.';
      }
    }
    if (next.price === '' || !Number.isFinite(Number(next.price))) {
      problems.price = 'Enter a price. Use 0 if this service is free.';
    }
    return problems;
  }

  async function create(next: Values) {
    const created = await serviceApi.create({
      name: next.name,
      ...(next.description.trim() === '' ? {} : { description: next.description }),
      durationMinutes: Number(next.durationMinutes),
      bufferBeforeMinutes: Number(next.bufferBeforeMinutes),
      bufferAfterMinutes: Number(next.bufferAfterMinutes),
      price: next.price,
    });

    // A create cannot carry its assignments: the ids belong to a service that did not exist a
    // moment ago. If this second write fails the service is still there, so the owner is told
    // exactly that and sent to the screen where they can try again — losing the message and
    // leaving them on a form for a service that already exists would be worse.
    if (selected.length > 0) {
      try {
        await serviceApi.replaceEmployees(created.id, selected);
      } catch (cause) {
        toast(
          cause instanceof ApiError
            ? `"${created.name}" was created, but who provides it could not be saved: ${cause.message}`
            : `"${created.name}" was created, but who provides it could not be saved.`,
          'error',
        );
        router.push(`/services/${created.id}`);
        return;
      }
    }

    toast(`"${created.name}" has been added.`, 'success');
    router.push(`/services/${created.id}`);
  }

  async function update(current: ServiceDetail, next: Values) {
    const changed = changedFields(valuesOf(current), next);
    const patch: ServicePatch = {};
    if (changed.name !== undefined) patch.name = changed.name;
    if (changed.description !== undefined) patch.description = changed.description;
    if (changed.durationMinutes !== undefined) {
      patch.durationMinutes = Number(changed.durationMinutes);
    }
    if (changed.bufferBeforeMinutes !== undefined) {
      patch.bufferBeforeMinutes = Number(changed.bufferBeforeMinutes);
    }
    if (changed.bufferAfterMinutes !== undefined) {
      patch.bufferAfterMinutes = Number(changed.bufferAfterMinutes);
    }
    // Sent as the string the owner typed. Turning it into a number here would make it a binary
    // float on the way out, which is the one thing money must never be.
    if (changed.price !== undefined) patch.price = changed.price;

    const assignmentsChanged = !sameSet(selected, current.employeeIds);
    if (Object.keys(patch).length === 0 && !assignmentsChanged) {
      toast('Nothing to save — nothing has changed.', 'info');
      return;
    }

    let updated =
      Object.keys(patch).length === 0 ? current : await serviceApi.patch(current.id, patch);
    if (assignmentsChanged) {
      const assigned = await serviceApi.replaceEmployees(current.id, selected);
      // The set the server answers with, not the one that was submitted — a replace is only
      // idempotent if the client believes the reply.
      updated = { ...updated, employeeIds: assigned.employeeIds };
    }
    onSaved(updated);
    toast('Your changes have been saved.', 'success');
  }

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const next = normalised();
    const problems = localProblems(next);
    setLocalErrors(problems);
    if (Object.keys(problems).length > 0) {
      setError(null);
      return;
    }

    setSaving(true);
    setError(null);
    try {
      if (service === null) await create(next);
      else await update(service, next);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('The service could not be saved.', 'error');
    } finally {
      setSaving(false);
    }
  }

  const fieldError = (field: keyof Values): string | undefined =>
    localErrors[field] ?? error?.fieldErrors[field];

  // A server message must never be silently dropped: it goes in the banner unless every part of it
  // is already showing against a field this form renders.
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
          title="What it is"
          description="The name is what customers see when they choose."
        />
        <div className="flex flex-col gap-4">
          <Input
            label="Name"
            value={values.name}
            maxLength={120}
            required
            onChange={(event) => set('name', event.target.value)}
            error={fieldError('name')}
          />
          <Textarea
            label="Description"
            value={values.description}
            maxLength={5000}
            rows={3}
            onChange={(event) => set('description', event.target.value)}
            hint="Optional. Shown on your booking page."
            error={fieldError('description')}
          />
        </div>
      </Card>

      <Card>
        <CardHeader
          title="How long it takes"
          description="The appointment itself, plus any time you need on either side of it."
        />
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Input
              label="Length (minutes)"
              type="number"
              inputMode="numeric"
              min={5}
              step={5}
              value={values.durationMinutes}
              required
              onChange={(event) => set('durationMinutes', event.target.value)}
              hint="In multiples of 5."
              error={fieldError('durationMinutes')}
            />
            <Input
              label="Extra time before"
              type="number"
              inputMode="numeric"
              min={0}
              step={5}
              value={values.bufferBeforeMinutes}
              onChange={(event) => set('bufferBeforeMinutes', event.target.value)}
              hint="Setting up. 0 for none."
              error={fieldError('bufferBeforeMinutes')}
            />
            <Input
              label="Extra time after"
              type="number"
              inputMode="numeric"
              min={0}
              step={5}
              value={values.bufferAfterMinutes}
              onChange={(event) => set('bufferAfterMinutes', event.target.value)}
              hint="Clearing up. 0 for none."
              error={fieldError('bufferAfterMinutes')}
            />
          </div>

          {tooLongForTheWeek && longest && (
            <p
              role="status"
              className="border-warning/40 bg-warning/10 text-ink rounded-md border px-3 py-2 text-sm"
            >
              {formatDuration(duration)} is longer than any single stretch you are open — the
              longest is {formatDuration(longest.minutes)} on {longest.day}. You can save this, but
              nobody will be able to book it until your{' '}
              <Link href="/settings/hours" className="text-brand underline">
                opening hours
              </Link>{' '}
              leave room for it.
            </p>
          )}
        </div>
      </Card>

      <Card>
        <CardHeader
          title="What it costs"
          description={
            service === null
              ? `Priced in ${currency}, your business's currency.`
              : `Priced in ${service.price.currency}, the currency this service was created in.`
          }
        />
        <Input
          label={`Price (${service === null ? currency : service.price.currency})`}
          type="text"
          inputMode="decimal"
          value={values.price}
          required
          onChange={(event) => set('price', event.target.value)}
          hint="Up to two decimal places. Use 0 for a free service."
          error={fieldError('price')}
        />
      </Card>

      <Card>
        <CardHeader
          title="Who provides it"
          description="Only the people ticked here can be booked for this service."
        />
        <AssignmentPicker
          legend="Employees"
          options={employees.map((employee) => ({
            id: employee.id,
            label: employee.fullName,
            ...(employee.jobTitle ? { hint: employee.jobTitle } : {}),
            inactive: !employee.active,
          }))}
          selected={selected}
          onChange={setSelected}
          emptyTitle="Nobody to assign yet"
          emptyDescription="A service can be saved with nobody assigned, but it cannot be booked until someone can perform it."
          emptyAction={
            <ButtonLink href="/employees/new" variant="secondary" size="sm">
              Add someone
            </ButtonLink>
          }
        />
        {employees.length > 0 && selected.length === 0 && (
          <p className="text-ink-muted mt-3 text-sm">
            Nobody is selected. This service can be saved, but it will not appear on your booking
            page until someone can perform it.
          </p>
        )}
      </Card>

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" loading={saving}>
          {service === null ? 'Add service' : 'Save changes'}
        </Button>
        <ButtonLink href="/services" variant="secondary">
          Cancel
        </ButtonLink>
        {service !== null && (
          <p className="text-ink-muted text-sm">
            Currently {formatMoney(service.price.amount, service.price.currency)} for{' '}
            {formatDuration(service.durationMinutes)}.
          </p>
        )}
      </div>
    </form>
  );
}
