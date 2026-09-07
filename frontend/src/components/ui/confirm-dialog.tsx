'use client';

import { useEffect, useId, useRef } from 'react';
import { Button } from './button';

/**
 * A modal that asks before something with a consequence the owner cannot see from the form.
 *
 * Built on the native `<dialog>` rather than a div with a backdrop, because `showModal()` supplies
 * the focus trap, the inert background, the Escape key and the top-layer stacking — four things a
 * hand-rolled modal gets wrong quietly. The only thing added here is that Escape and a backdrop
 * click both route to `onCancel`, so dismissing never counts as confirming.
 */
export function ConfirmDialog({
  open,
  title,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  tone = 'primary',
  busy = false,
  onConfirm,
  onCancel,
  children,
}: {
  open: boolean;
  title: string;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: 'primary' | 'danger';
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  children: React.ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      // Escape closes a native dialog without telling React, which would leave `open` true and the
      // dialog unopenable a second time. The close event is the one place both routes meet.
      onClose={onCancel}
      onCancel={(event) => {
        // A confirmation already in flight should not be dismissable half-way.
        if (busy) event.preventDefault();
      }}
      onClick={(event) => {
        if (!busy && event.target === ref.current) onCancel();
      }}
      className="bg-surface text-ink border-border m-auto w-[min(28rem,calc(100vw-2rem))] rounded-lg border p-6 shadow-lg backdrop:bg-black/40"
    >
      <h2 id={titleId} className="text-ink text-base font-semibold">
        {title}
      </h2>
      <div className="text-ink-muted mt-2 flex flex-col gap-2 text-sm">{children}</div>
      <div className="mt-6 flex justify-end gap-2">
        <Button variant="secondary" onClick={onCancel} disabled={busy}>
          {cancelLabel}
        </Button>
        <Button variant={tone} onClick={onConfirm} loading={busy}>
          {confirmLabel}
        </Button>
      </div>
    </dialog>
  );
}
