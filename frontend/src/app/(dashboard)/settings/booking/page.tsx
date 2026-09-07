'use client';

import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { BusinessProfile } from '@/lib/business';
import { BookingForm } from './booking-form';

export default function BookingSettingsPage() {
  const business = useResource<BusinessProfile>('/business');

  return (
    <ResourceGate resource={business}>
      {(profile) => (
        <BookingForm key={profile.updatedAt} profile={profile} onSaved={business.set} />
      )}
    </ResourceGate>
  );
}
