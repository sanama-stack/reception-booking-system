'use client';

import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { BusinessProfile, FaqList } from '@/lib/business';
import { FaqsCard } from './faq-list';
import { ReceptionistNotes } from './receptionist-notes';

/**
 * Everything the Receptionist is allowed to say: the questions and answers, and the free-text
 * notes that go with them.
 *
 * Two resources rather than one, because they are two endpoints — and each gate is separate, so a
 * failure to load the FAQs does not take the notes down with it.
 */
export default function FaqsSettingsPage() {
  const faqs = useResource<FaqList>('/business/faqs');
  const business = useResource<BusinessProfile>('/business');

  return (
    <div className="flex flex-col gap-6">
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
