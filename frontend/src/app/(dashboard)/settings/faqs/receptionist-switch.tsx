'use client';

import { useState } from 'react';
import { Button, Card, CardHeader, Input, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { businessApi, type BusinessPatch, type BusinessProfile } from '@/lib/business';
import { changedFields } from '@/lib/forms/changed-fields';

/**
 * Whether the Receptionist runs at all, and what it may cost per day.
 *
 * These are the two switches `ReceptionistNotes` refers to — "operational rather than editorial",
 * and they sit above the notes on this screen for that reason: the FAQs and the notes decide what
 * it may *say*, and these decide whether it says anything.
 *
 * **Switching it off is not a small change and is not styled as one.** `aiEnabled` is read on
 * every turn by `ConversationService.requireReceptionistAvailable`, so an owner who turns it off
 * ends every conversation in progress with `AI_UNAVAILABLE` — and the panel disappears from the
 * booking page entirely, because `BusinessPanel` gates on the same flag. Customers are not stranded
 * by that, which is the whole point of the Classic Flow being a peer rather than a fallback screen,
 * but they are mid-sentence.
 */
export function ReceptionistSwitch({
  profile,
  onSaved,
}: {
  profile: BusinessProfile;
  onSaved: (updated: BusinessProfile) => void;
}) {
  const toast = useToast();
  const [enabled, setEnabled] = useState(profile.aiEnabled);
  const [cap, setCap] = useState(centsToDollars(profile.aiDailyCostCapCents));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [capError, setCapError] = useState<string | undefined>(undefined);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const cents = dollarsToCents(cap);
    if (cents === null) {
      setCapError('Enter an amount, like 5.00.');
      return;
    }
    setCapError(undefined);

    const patch = changedFields(profile, {
      aiEnabled: enabled,
      aiDailyCostCapCents: cents,
    } satisfies BusinessPatch);

    if (Object.keys(patch).length === 0) {
      toast('Nothing to save — no settings have changed.', 'info');
      return;
    }

    setSaving(true);
    setError(null);
    try {
      onSaved(await businessApi.patch(patch));
      toast(
        // The consequence, not the fact. "Saved" leaves an owner who has just switched it off
        // wondering what happened to the panel on their booking page.
        patch.aiEnabled === false
          ? 'The receptionist is off. Your booking page now shows the booking form only.'
          : patch.aiEnabled === true
            ? 'The receptionist is on and will answer on your booking page.'
            : 'Saved.',
        'success',
      );
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('That change could not be saved.', 'error');
    } finally {
      setSaving(false);
    }
  }

  const serverCapError = error?.fieldErrors['aiDailyCostCapCents'];
  const unfielded = error && !serverCapError ? error.message : null;

  return (
    <Card>
      <CardHeader
        title="Receptionist"
        description="Whether customers can talk to it on your booking page, and what it may spend in a day."
      />
      <form onSubmit={onSubmit} className="flex flex-col gap-6" noValidate>
        {unfielded && (
          <p
            role="alert"
            className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
          >
            {unfielded}
          </p>
        )}

        <div className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
          <label className="flex items-start gap-3">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(event) => setEnabled(event.target.checked)}
              // `size-4 mt-0.5` rather than a larger target, and this is the one place the 44 px
              // guideline is met by something other than the control: the whole label is
              // clickable, so the target is the sentence beside it rather than the box.
              className="accent-brand mt-0.5 size-4 shrink-0"
            />
            <span className="text-ink text-sm font-medium">
              Answer customers on my booking page
            </span>
          </label>
          <p className="text-ink-muted text-sm">
            {enabled
              ? 'Customers see a chat panel beside the booking form and can book by talking to it. The form stays, and everything it does still works.'
              : 'Your booking page shows the booking form only. Nothing else changes — the form takes every booking the receptionist would have.'}
          </p>
        </div>

        <div className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
          <Input
            label="Daily limit"
            type="number"
            inputMode="decimal"
            min={0}
            max={10000}
            step="0.01"
            value={cap}
            onChange={(event) => setCap(event.target.value)}
            hint="In US dollars, per day."
            error={capError ?? serverCapError}
          />
          <p className="text-ink-muted text-sm sm:pt-7">
            {/*
              What the cap actually does, which is not obvious from the number: it is checked
              *before* each model call, so exceeding it costs nothing — and it stops the next
              conversation rather than truncating the one that reached it.
            */}
            Once your customers&rsquo; conversations have cost this much in a day, the receptionist
            stops answering until tomorrow and your booking page carries on taking bookings through
            the form. Checked before each reply, so reaching it costs nothing.
          </p>
        </div>

        <div>
          <Button type="submit" loading={saving}>
            Save receptionist settings
          </Button>
        </div>
      </form>
    </Card>
  );
}

/**
 * Cents on the wire, dollars on the screen.
 *
 * The column is cents because a cap has to be compared against `CostTracker`'s integer cents
 * without a floating-point step in between. An owner types money.
 */
function centsToDollars(cents: number): string {
  return (cents / 100).toFixed(2);
}

/**
 * `"5.00"` → `500`, and anything that is not an amount → `null`.
 *
 * Rounded rather than truncated: `5.999` typed into a number input is somebody meaning $6, and
 * `Math.trunc` would quietly make it $5.99. The server's own `@Max` is what refuses a figure that
 * is too large, so nothing is clamped here — a rejected value comes back as a field error under
 * `aiDailyCostCapCents`, in the server's words.
 */
function dollarsToCents(dollars: string): number | null {
  const value = Number(dollars.trim());
  if (dollars.trim() === '' || !Number.isFinite(value) || value < 0) return null;
  return Math.round(value * 100);
}
