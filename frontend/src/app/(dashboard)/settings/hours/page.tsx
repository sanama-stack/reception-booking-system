'use client';

import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { WeekHours } from '@/lib/business';
import { HoursEditor } from './hours-editor';

export default function HoursSettingsPage() {
  const hours = useResource<WeekHours>('/business/hours');

  return (
    <ResourceGate resource={hours}>
      {(week) => <HoursEditor week={week} onSaved={hours.set} />}
    </ResourceGate>
  );
}
