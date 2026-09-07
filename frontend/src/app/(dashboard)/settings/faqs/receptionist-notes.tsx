'use client';

import { useState } from 'react';
import { Button, Card, CardHeader, Textarea, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { businessApi, type BusinessProfile } from '@/lib/business';

/**
 * `aiAdditionalInfo` — everything the Receptionist should know that is not a question and answer.
 *
 * It sits with the FAQs rather than on a screen of its own because it is the same thing from the
 * owner's side: what the Receptionist is allowed to say. The switches that decide whether the
 * Receptionist runs at all, and what it may cost per day, are operational rather than editorial
 * and belong with the Receptionist screens in phase 09.
 */
export function ReceptionistNotes({
  profile,
  onSaved,
}: {
  profile: BusinessProfile;
  onSaved: (updated: BusinessProfile) => void;
}) {
  const toast = useToast();
  const [value, setValue] = useState(profile.aiAdditionalInfo ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const current = profile.aiAdditionalInfo ?? '';
  const changed = value !== current;

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      // Blank clears, which is what an emptied textarea already sends — no separate gesture.
      onSaved(await businessApi.patch({ aiAdditionalInfo: value }));
      toast('Saved.', 'success');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('That could not be saved.', 'error');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card>
      <CardHeader
        title="Anything else the Receptionist should know"
        description="Free text, used alongside your questions and answers. Parking, access, what to bring, who to ask for."
      />
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {error && Object.keys(error.fieldErrors).length === 0 && (
          <p
            role="alert"
            className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
          >
            {error.message}
          </p>
        )}
        <Textarea
          label="Notes for the Receptionist"
          value={value}
          maxLength={2000}
          rows={5}
          onChange={(event) => setValue(event.target.value)}
          hint={`${value.length} of 2000 characters.`}
          error={error?.fieldErrors.aiAdditionalInfo}
        />
        <div>
          <Button type="submit" loading={saving} disabled={!changed}>
            Save notes
          </Button>
        </div>
      </form>
    </Card>
  );
}
