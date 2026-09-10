'use client';

import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { BusinessProfile, FaqList } from '@/lib/business';
import { FaqsCard } from './faq-list';
import { ReceptionistNotes } from './receptionist-notes';
import { ReceptionistSwitch } from './receptionist-switch';

/**
 * The Receptionist: whether it runs, and everything it is allowed to say.
 *
 * The switches come first because they decide whether the rest of the screen has any effect —
 * editing FAQs for a Receptionist that is switched off is editing a script nobody reads.
 *
 * Two resources rather than one, because they are two endpoints — and each gate is separate, so a
 * failure to load the FAQs does not take the switches or the notes down with it. The two cards
 * driven by `/business` share one read and one `set`, so saving either leaves the other showing
 * the same version of the profile.
 */
export default function FaqsSettingsPage() {
  const faqs = useResource<FaqList>('/business/faqs');
  const business = useResource<BusinessProfile>('/business');

  return (
    <div className="flex flex-col gap-6">
      <ResourceGate resource={business}>
        {(profile) => (
          <ReceptionistSwitch key={profile.updatedAt} profile={profile} onSaved={business.set} />
        )}
      </ResourceGate>
      <ResourceGate resource={faqs}>
        {(list) => <FaqsCard list={list} onChanged={faqs.reload} />}
      </ResourceGate>
      <ResourceGate resource={business}>
        {(profile) => (
          <ReceptionistNotes key={profile.updatedAt} profile={profile} onSaved={business.set} />
        )}
      </ResourceGate>
    </div>
  );
}
