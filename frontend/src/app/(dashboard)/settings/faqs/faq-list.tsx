'use client';

import { useState } from 'react';
import {
  Button,
  Card,
  CardHeader,
  ConfirmDialog,
  EmptyState,
  Input,
  Textarea,
  useToast,
} from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { businessApi, type Faq, type FaqList } from '@/lib/business';

/**
 * Mirrors `BusinessFaq.MAX_PER_BUSINESS`. The server is what enforces it — this only lets the
 * screen say how much room is left before the owner types a question it will refuse.
 */
const MAX_FAQS = 50;

export function FaqsCard({ list, onChanged }: { list: FaqList; onChanged: () => Promise<void> }) {
  const toast = useToast();
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<ApiError | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<Faq | null>(null);
  const [deletingBusy, setDeletingBusy] = useState(false);
  const [reordering, setReordering] = useState(false);

  const full = list.faqs.length >= MAX_FAQS;

  async function onCreate(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCreating(true);
    setCreateError(null);
    try {
      // No `sortOrder`: absent appends, and appending is what "add a question" means. Counting the
      // list here to say the same thing would be a second definition of the end of it.
      await businessApi.createFaq({ question: question.trim(), answer: answer.trim() });
      await onChanged();
      setQuestion('');
      setAnswer('');
      toast('Question added.', 'success');
    } catch (cause) {
      setCreateError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('The question could not be added.', 'error');
    } finally {
      setCreating(false);
    }
  }

  async function onDelete(faq: Faq) {
    setDeletingBusy(true);
    try {
      await businessApi.deleteFaq(faq.id);
      await onChanged();
      toast('Question removed.', 'success');
      setDeleting(null);
    } catch (cause) {
      toast(
        cause instanceof ApiError ? cause.message : 'The question could not be removed.',
        'error',
      );
    } finally {
      setDeletingBusy(false);
    }
  }

  /**
   * Moves one question and renumbers the list.
   *
   * Swapping two `sortOrder` values would be fewer requests and is what the operation looks like —
   * but the values are only guaranteed to be *ordered*, not contiguous or distinct: a create with
   * an explicit position, or a delete, leaves gaps and ties, and swapping equal values does
   * nothing at all. Renumbering the visible order and sending only the rows that actually move is
   * correct from any starting state, and repairs the numbering as a side effect.
   */
  async function move(index: number, direction: -1 | 1) {
    const target = index + direction;
    const reordered = [...list.faqs];
    const moved = reordered[index];
    const displaced = reordered[target];
    if (!moved || !displaced) return;
    reordered[index] = displaced;
    reordered[target] = moved;

    const changes = reordered
      .map((faq, position) => ({ faq, position }))
      .filter(({ faq, position }) => faq.sortOrder !== position);

    setReordering(true);
    try {
      for (const { faq, position } of changes) {
        await businessApi.patchFaq(faq.id, { sortOrder: position });
      }
      await onChanged();
    } catch (cause) {
      toast(cause instanceof ApiError ? cause.message : 'The order could not be changed.', 'error');
      // Whatever landed before the failure is real, so the list is re-read rather than assumed.
      await onChanged();
    } finally {
      setReordering(false);
    }
  }

  return (
    <>
      <Card>
        <CardHeader
          title="Add a question"
          description="Questions customers actually ask. The Receptionist answers from these, word for word where it can."
        />
        <form onSubmit={onCreate} className="flex flex-col gap-4" noValidate>
          {createError && Object.keys(createError.fieldErrors).length === 0 && (
            <p
              role="alert"
              className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
            >
              {createError.message}
            </p>
          )}
          <Input
            label="Question"
            value={question}
            maxLength={300}
            required
            onChange={(event) => setQuestion(event.target.value)}
            error={createError?.fieldErrors.question}
          />
          <Textarea
            label="Answer"
            value={answer}
            maxLength={1000}
            required
            rows={3}
            onChange={(event) => setAnswer(event.target.value)}
            error={createError?.fieldErrors.answer}
          />
          <div className="flex flex-wrap items-center gap-3">
            <Button
              type="submit"
              loading={creating}
              disabled={full || !question.trim() || !answer.trim()}
            >
              Add question
            </Button>
            <p className="text-ink-muted text-sm">
              {list.faqs.length} of {MAX_FAQS} used
              {full ? '. Remove one before adding another.' : '.'}
            </p>
          </div>
        </form>
      </Card>

      <Card>
        <CardHeader
          title="Questions and answers"
          description="The order here is the order the Receptionist reads them in. Put the most-asked first."
        />
        {list.faqs.length === 0 ? (
          <EmptyState
            title="No questions yet"
            description="Add the ones you answer on the phone every week — parking, payment, what to bring."
          />
        ) : (
          <ul className="flex flex-col">
            {list.faqs.map((faq, index) => (
              <li key={faq.id} className="border-border border-b py-4 first:pt-0 last:border-b-0">
                {editing === faq.id ? (
                  <FaqEditor
                    faq={faq}
                    onDone={async () => {
                      setEditing(null);
                      await onChanged();
                    }}
                    onCancel={() => setEditing(null)}
                  />
                ) : (
                  <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:gap-4">
                    <div className="min-w-0 flex-1">
                      <p className="text-ink text-sm font-medium">{faq.question}</p>
                      <p className="text-ink-muted mt-1 text-sm whitespace-pre-wrap">
                        {faq.answer}
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        aria-label={`Move "${faq.question}" up`}
                        disabled={index === 0 || reordering}
                        onClick={() => void move(index, -1)}
                      >
                        ↑
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        aria-label={`Move "${faq.question}" down`}
                        disabled={index === list.faqs.length - 1 || reordering}
                        onClick={() => void move(index, 1)}
                      >
                        ↓
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => setEditing(faq.id)}>
                        Edit
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => setDeleting(faq)}>
                        Remove
                      </Button>
                    </div>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <ConfirmDialog
        open={deleting !== null}
        title="Remove this question?"
        confirmLabel="Remove question"
        tone="danger"
        busy={deletingBusy}
        onConfirm={() => deleting && void onDelete(deleting)}
        onCancel={() => !deletingBusy && setDeleting(null)}
      >
        <p>{deleting?.question}</p>
        <p>The Receptionist will stop answering with it.</p>
      </ConfirmDialog>
    </>
  );
}

function FaqEditor({
  faq,
  onDone,
  onCancel,
}: {
  faq: Faq;
  onDone: () => Promise<void>;
  onCancel: () => void;
}) {
  const toast = useToast();
  const [question, setQuestion] = useState(faq.question);
  const [answer, setAnswer] = useState(faq.answer);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      // Only what changed. `PatchFaq` takes absent-or-value, so an untouched field is not sent.
      await businessApi.patchFaq(faq.id, {
        ...(question.trim() !== faq.question ? { question: question.trim() } : {}),
        ...(answer.trim() !== faq.answer ? { answer: answer.trim() } : {}),
      });
      toast('Question saved.', 'success');
      await onDone();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('The question could not be saved.', 'error');
      setSaving(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-3" noValidate>
      {error && Object.keys(error.fieldErrors).length === 0 && (
        <p
          role="alert"
          className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
        >
          {error.message}
        </p>
      )}
      <Input
        label="Question"
        value={question}
        maxLength={300}
        onChange={(event) => setQuestion(event.target.value)}
        error={error?.fieldErrors.question}
      />
      <Textarea
        label="Answer"
        value={answer}
        maxLength={1000}
        rows={3}
        onChange={(event) => setAnswer(event.target.value)}
        error={error?.fieldErrors.answer}
      />
      <div className="flex gap-2">
        <Button type="submit" size="sm" loading={saving}>
          Save
        </Button>
        <Button type="button" size="sm" variant="secondary" onClick={onCancel} disabled={saving}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
