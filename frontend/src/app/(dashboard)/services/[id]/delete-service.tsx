'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Button, Card, CardHeader, ConfirmDialog, ErrorState, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { serviceApi, type ServiceDetail } from '@/lib/catalog';

/**
 * Deleting a service, and the refusal that is the normal answer.
 *
 * A service that has ever been booked cannot be removed: an appointment from last year still names
 * the service it was for. The server answers `409 SERVICE_IN_USE` with a message that names
 * deactivation as the way to retire something, and that message is rendered here rather than
 * replaced — a refusal whose text does not say what to do instead just leaves the owner stuck.
 */
export function DeleteService({ service }: { service: ServiceDetail }) {
  const router = useRouter();
  const toast = useToast();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<ApiError | null>(null);

  async function remove() {
    setBusy(true);
    try {
      await serviceApi.delete(service.id);
      toast(`"${service.name}" has been deleted.`, 'success');
      router.push('/services');
    } catch (cause) {
      setConfirming(false);
      if (cause instanceof ApiError) setRefusal(cause);
      else toast('The service could not be deleted.', 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader
        title="Delete this service"
        description="Only possible while nothing has ever been booked for it. Otherwise deactivate it — it stops being offered and its history stays intact."
      />
      <div className="flex flex-col gap-4">
        {refusal && <ErrorState error={refusal} />}
        <div>
          <Button variant="danger" onClick={() => setConfirming(true)}>
            Delete service
          </Button>
        </div>
      </div>

      <ConfirmDialog
        open={confirming}
        title="Delete this service?"
        confirmLabel="Delete service"
        tone="danger"
        busy={busy}
        onConfirm={() => void remove()}
        onCancel={() => !busy && setConfirming(false)}
      >
        <p className="text-ink font-medium">{service.name}</p>
        <p>This cannot be undone. If it has ever been booked, it will be refused.</p>
      </ConfirmDialog>
    </Card>
  );
}
