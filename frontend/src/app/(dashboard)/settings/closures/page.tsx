'use client';

import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { ClosureList } from '@/lib/business';
import { ClosuresScreen } from './closures-screen';

export default function ClosuresSettingsPage() {
  const closures = useResource<ClosureList>('/business/closures');

  return (
    <div className="flex flex-col gap-6">
      <ResourceGate resource={closures}>
        {(list) => <ClosuresScreen list={list} onChanged={closures.reload} />}
      </ResourceGate>
    </div>
  );
}
