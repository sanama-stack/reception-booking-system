import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PROFILE } from '@/test/fixtures';
import { renderScreen, serve } from '@/test/harness';
import { ProfileForm } from './profile-form';

/**
 * The refusals this form routes by hand, and the invariant underneath them.
 *
 * Most screens split a refusal two ways — a field message, or a banner. This one has a third
 * case its own comment names: **a server message must never be silently dropped.** A refusal that
 * names a field this form does not render would vanish under a rule that only asks "does it have
 * a field?", so the banner is gated on whether every part of the message is actually *showing*,
 * not on whether it was fielded.
 *
 * That distinction is invisible to a test that only ever sends a field this form renders, which
 * is why the third case below sends one it does not.
 */

/**
 * Only changed fields are sent — `changedFields` — so an untouched form answers "nothing to save"
 * and never reaches the server at all. Every case here has to edit something first.
 */
async function renameAndSave(): Promise<void> {
  const name = screen.getByLabelText('Business name');
  await userEvent.clear(name);
  await userEvent.type(name, 'Aria Salon');
  await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
}

/** The zone the fixture is in, and one it is demonstrably not. */
const FROM = 'UTC';
const TO = 'Asia/Tbilisi';

async function retimezoneAndSave(zone = TO): Promise<void> {
  const timezone = screen.getByLabelText('Timezone');
  await userEvent.clear(timezone);
  await userEvent.type(timezone, zone);
  await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
}

/** Every request the stubbed fetch received that was not a GET. */
function writes(): string[] {
  return vi
    .mocked(fetch)
    .mock.calls.filter(([, init]) => (init?.method ?? 'GET').toUpperCase() !== 'GET')
    .map(([input]) => String(input));
}

/**
 * FR-3... FR-2's last acceptance criterion in docs/01-prd.md: "changing timezone shows a
 * confirmation warning describing the effect on existing appointments".
 *
 * <p>Written 2026-09-22. It was the only criterion in that document open for a reason a ruling
 * could not touch: the dialog existed and its copy was right, and <em>nothing rendered it</em>.
 * The five cases above are all about refusals and none of them ever changes the timezone, so the
 * whole confirmation branch of `onSubmit` was unexercised.
 *
 * <p><strong>A warning and a confirmation are different things</strong>, and only the second is
 * what ADR-0003 asked for. The distinguishing case is `cancelling`: if the patch went out anyway
 * the dialog would be a notice, and an owner who realised mid-sentence that they were about to
 * reinterpret every time on every screen would have no way to stop.
 *
 * <p>The control is `an ordinary change saves without asking`. Without it every assertion here
 * would still pass against a form that confirmed *everything*, which is a different screen and a
 * worse one.
 */
describe('ProfileForm, changing the timezone', () => {
  it('asks before saving, and names both zones', async () => {
    serve({ kind: 'body', bodies: { '/business': PROFILE } });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await retimezoneAndSave();

    const dialog = (await screen.findByText('Change your timezone?')).closest('dialog');
    expect(dialog).not.toBeNull();
    expect(dialog!.open).toBe(true);
    expect(dialog!).toHaveTextContent(FROM);
    expect(dialog!).toHaveTextContent(TO);
  });

  /**
   * The row says "describing the effect on existing appointments", so this asserts the substance
   * and not merely that some prose is present. The effect is the counter-intuitive one: nothing
   * moves, and every displayed time changes anyway.
   */
  it('says the appointments do not move but the times shown do', async () => {
    serve({ kind: 'body', bodies: { '/business': PROFILE } });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await retimezoneAndSave();

    const dialog = (await screen.findByText('Change your timezone?')).closest('dialog')!;
    // `open` first, and not as ceremony. The confirmation is rendered on every pass, so reading
    // its copy off a closed dialog asserts the component's markup rather than the screen's
    // behaviour — this case passed with the whole confirmation branch deleted until this line.
    expect(dialog.open).toBe(true);
    expect(dialog).toHaveTextContent('No appointment moves.');
    expect(dialog).toHaveTextContent('will be shown at a different hour');
  });

  /** Asking is only a confirmation if nothing has happened yet. */
  it('has sent nothing while the question is still on screen', async () => {
    serve({ kind: 'body', bodies: { '/business': PROFILE } });
    const onSaved = vi.fn();
    renderScreen(<ProfileForm profile={PROFILE} onSaved={onSaved} />);

    await retimezoneAndSave();

    await screen.findByText('Change your timezone?');
    expect(writes()).toEqual([]);
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('saves once the owner confirms', async () => {
    serve({ kind: 'body', bodies: { '/business': PROFILE } });
    const onSaved = vi.fn();
    renderScreen(<ProfileForm profile={PROFILE} onSaved={onSaved} />);

    await retimezoneAndSave();
    await userEvent.click(await screen.findByRole('button', { name: 'Change timezone' }));

    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(writes()).toHaveLength(1);
  });

  /** The case that makes this a confirmation rather than a notice. */
  it('sends nothing at all when the owner backs out', async () => {
    serve({ kind: 'body', bodies: { '/business': PROFILE } });
    const onSaved = vi.fn();
    renderScreen(<ProfileForm profile={PROFILE} onSaved={onSaved} />);

    await retimezoneAndSave();
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));

    // The dialog's own `open`, not the absence of its text: jsdom implements no modal behaviour
    // and `setup.ts`'s shim says so plainly, so a closed dialog's children are still here.
    const dialog = screen.getByText('Change your timezone?').closest('dialog')!;
    expect(dialog.open).toBe(false);
    expect(writes()).toEqual([]);
    expect(onSaved).not.toHaveBeenCalled();
  });

  /**
   * The control. Without it, a form that confirmed every save would pass everything above.
   *
   * <p>Asserted on the dialog's own `open`, for the reason `delete-service` writes out: the
   * confirmation is rendered on every pass with `open={pendingTimezone !== null}`, so its heading
   * is in the document whether or not it is showing. The first draft of this case asked whether
   * the text was absent and failed — correctly. In a browser it would have passed, which is worse:
   * it would have been asserting a browser behaviour this environment does not have.
   */
  it('does not ask for an ordinary change', async () => {
    serve({ kind: 'body', bodies: { '/business': PROFILE } });
    const onSaved = vi.fn();
    renderScreen(<ProfileForm profile={PROFILE} onSaved={onSaved} />);

    await renameAndSave();

    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    const dialog = screen.getByText('Change your timezone?').closest('dialog')!;
    expect(dialog.open).toBe(false);
  });
});

describe('ProfileForm, refused', () => {
  it('shows the message the server sent when the refusal names no field', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 500,
        code: 'INTERNAL_ERROR',
        detail: 'The profile could not be saved.',
      },
    });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await renameAndSave();

    expect(await screen.findByRole('alert')).toHaveTextContent('The profile could not be saved.');
  });

  /**
   * `SLUG_TAKEN` is a conflict rather than a shape violation, so it arrives with no `errors`
   * entry at all — and the form puts it on the slug field anyway, because that is where the owner
   * just typed. A rule keyed purely on `fieldErrors` would banner it instead.
   */
  it('places SLUG_TAKEN on the address field although it names none', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'SLUG_TAKEN',
        detail: 'That booking page address is already in use.',
      },
    });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await renameAndSave();

    expect(await screen.findByText('That booking page address is already in use.')).toBeVisible();
    expect(screen.getByLabelText('Booking page address')).toHaveAccessibleDescription(
      'That booking page address is already in use.',
    );
    // And not twice: the banner is suppressed precisely because the message is already showing.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  /**
   * The case the invariant exists for. `aiDailyCostCapCents` is a real field of the profile and
   * is **not** rendered by this form, so its message has nowhere to land — and must therefore
   * reach the banner rather than disappear.
   */
  it('banners a fielded message for a field this form does not render', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'aiDailyCostCapCents', message: 'The daily cap is too low.' }],
      },
    });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await renameAndSave();

    expect(await screen.findByRole('alert')).toHaveTextContent('Some of that could not be saved.');
  });

  it('keeps the banner away when every message has a field on this form', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'name', message: 'That name is too short.' }],
      },
    });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await renameAndSave();

    expect(await screen.findByText('That name is too short.')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('lets the owner try again rather than leaving the button spinning', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await renameAndSave();

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();
  });
});
