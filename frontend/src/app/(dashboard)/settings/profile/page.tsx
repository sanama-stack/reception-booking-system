'use client';

import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { BusinessProfile } from '@/lib/business';
import { ProfileForm } from './profile-form';

export default function ProfileSettingsPage() {
  const business = useResource<BusinessProfile>('/business');

  return (
    <ResourceGate resource={business}>
      {(profile) => (
        // Keyed on the save timestamp: a successful save returns the stored profile, and remounting
        // re-baselines the form against it. Without that, "what has changed?" would keep being
        // asked against the values the screen was first loaded with, and a second save would
        // re-send fields the first one already stored — including the slug, which would be
        // re-checked for uniqueness, and the timezone, which would ask for confirmation again.
        <ProfileForm key={profile.updatedAt} profile={profile} onSaved={business.set} />
      )}
    </ResourceGate>
  );
}
