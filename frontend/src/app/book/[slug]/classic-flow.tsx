'use client';

import { useEffect, useState } from 'react';
import { FORTNIGHT_DAYS, FortnightPicker } from '@/components/fortnight-picker';
import { Button, Card, CardHeader, Input, ResourceGate, Textarea, cn } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { useResource } from '@/lib/api/use-resource';
import { formatDuration } from '@/lib/catalog';
import {
  publicApi,
  publicAvailabilityPath,
  publicEmployeesPath,
  type BookedAppointment,
  type PublicBusiness,
  type PublicEmployee,
  type PublicService,
} from '@/lib/public';
import { isStaleSlot, type AvailableSlot } from '@/lib/scheduling';
import {
  addIsoDays,
  formatIsoDate,
  formatMoney,
  formatTime,
  toBusinessDate,
  type IsoDate,
  type Timezone,
} from '@/lib/time';
import { Confirmation } from './confirmation';

/**
 * Which name each customer input is reported under.
 *
 * **Dotted, because the public booking body nests the Customer** — the published contract
 * (docs/04-api-overview.md §6), and `CustomerFieldNames.PUBLIC_BOOKING` is what makes the server
 * report failures under these exact names. A client looks each `errors[].field` up by exact name
 * to decide which input to mark, so indexing by `phone` here would match nothing the server sends
 * and the message would be silently dropped. Collected in one object so the set below and the
 * inputs cannot disagree about the spelling.
 */
const FIELD = {
  fullName: 'customer.fullName',
  phone: 'customer.phone',
  email: 'customer.email',
  note: 'note',
} as const;

/** Who the Customer picked. `null` throughout means "Anyone available" — a choice, not an absence. */
interface ChosenEmployee {
  id: string;
  fullName: string;
}

interface Selection {
  startsAt: string;
  employeeId: string;
  employeeName: string;
  /** The zone the chosen time was rendered in, so the summary cannot drift from the grid. */
  timezone: Timezone;
}

/**
 * The Classic Flow: service → who → day → time → your details → confirm.
 *
 * The order is the order the answers narrow each other. The Service first because it decides how
 * long the appointment is and who can perform it; the person and the fortnight next because they
 * are the two halves of the question the engine answers; the Customer's details last because they
 * change nothing about which times exist — which is also why a validation failure on them never
 * costs the Customer their chosen time.
 *
 * **Sections appear as they become answerable** rather than sitting inert. At 360 px, four cards
 * of disabled controls is a screen of scrolling before anything can be done, and a form offering a
 * choice that cannot yet be made is a form that looks broken.
 *
 * **Nothing here recomputes availability.** The times come from the same `GET …/availability` the
 * dashboard and the employee preview use, through the same engine, and the chosen Slot carries the
 * Employee it will be booked with. A page that resolved a person of its own would be a second
 * opinion about a question already answered — and with "Anyone available" chosen, a genuinely
 * different person.
 */
export function ClassicFlow({
  slug,
  business,
  services,
  today,
}: {
  slug: string;
  business: PublicBusiness;
  services: PublicService[];
  /** Today in the *business's* timezone, resolved on the server so no browser clock is consulted. */
  today: IsoDate;
}) {
  /**
   * Pre-selected only when there is one Service, because then there is no choice to make.
   *
   * With several, `''` means "not chosen yet" and the sections below stay closed. That differs from
   * the dashboard, which falls back to `services[0]`: an owner booking on somebody's behalf already
   * knows what they are booking, and a Customer reading a price list does not.
   */
  const soleService = services.length === 1 ? services[0] : undefined;

  const [serviceId, setServiceId] = useState(soleService?.id ?? '');
  const [employee, setEmployee] = useState<ChosenEmployee | null>(null);
  const [windowStart, setWindowStart] = useState<IsoDate>(today);
  const [chosenDate, setChosenDate] = useState<IsoDate | null>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [refreshes, setRefreshes] = useState(0);

  const [fullName, setFullName] = useState('');
  const [phone, setPhone] = useState('');
  const [email, setEmail] = useState('');
  const [note, setNote] = useState('');

  const [booking, setBooking] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [booked, setBooked] = useState<BookedAppointment | null>(null);
  const [bookedEmail, setBookedEmail] = useState<string | null>(null);

  // The confirmation replaces a form the Customer had scrolled to the bottom of, so without this
  // the code they are told to keep renders above the fold they are looking at.
  useEffect(() => {
    if (booked) window.scrollTo({ top: 0, behavior: 'smooth' });
  }, [booked]);

  if (booked) {
    return (
      <Confirmation
        appointment={booked}
        business={business}
        email={bookedEmail}
        onBookAnother={() => {
          setBooked(null);
          setBookedEmail(null);
          setSelection(null);
          setChosenDate(null);
          setWindowStart(today);
          setServiceId(soleService?.id ?? '');
          setEmployee(null);
          // The name and number are deliberately kept. A second booking is most often the same
          // person booking again, and clearing what they just typed would be tidiness at their
          // expense.
          setNote('');
          setError(null);
        }}
      />
    );
  }

  // Resolved on every render rather than stored, so a service withdrawn between the page being
  // served and a click cannot leave a picker naming a choice the server would refuse.
  const service = services.find((candidate) => candidate.id === serviceId) ?? null;

  /** Any change to the question invalidates an answer taken from the old one. */
  function ask(change: () => void) {
    change();
    setSelection(null);
    setError(null);
  }

  /** Paging moves the whole window, so the chosen day goes with it — it is no longer on screen. */
  function page(nextStart: IsoDate) {
    setWindowStart(nextStart);
    setChosenDate(null);
    setSelection(null);
    setError(null);
  }

  const path = service
    ? publicAvailabilityPath(slug, {
        serviceId: service.id,
        from: windowStart,
        to: addIsoDays(windowStart, FORTNIGHT_DAYS - 1),
        ...(employee ? { employeeId: employee.id } : {}),
      })
    : null;

  async function onBook(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!service || !selection) return;

    const trimmedEmail = email.trim();
    setBooking(true);
    setError(null);
    try {
      const appointment = await publicApi.book(slug, {
        serviceId: service.id,
        // Off the Slot, never re-resolved. With "Anyone available" chosen this is the person the
        // Customer was actually shown, which is the only one they can be said to have agreed to.
        employeeId: selection.employeeId,
        startsAt: selection.startsAt,
        customer: {
          fullName: fullName.trim(),
          // Left exactly as typed. The server normalises it to E.164 against the business's
          // country, and trimming here would be the first step of a second normaliser — which is
          // how one person becomes two Customers with two histories.
          phone,
          ...(trimmedEmail ? { email: trimmedEmail } : {}),
        },
        ...(note.trim() ? { note: note.trim() } : {}),
      });
      setBookedEmail(trimmedEmail || null);
      setBooked(appointment);
    } catch (cause) {
      if (!(cause instanceof ApiError)) {
        // Nothing reaches here through the client, which wraps even a dead connection as a
        // `NETWORK_ERROR`. Handled anyway, because the one thing a booking must never do is fail
        // without saying so.
        setError(
          new ApiError({
            code: 'INTERNAL_ERROR',
            message: 'The appointment could not be booked. Please try again.',
            status: 0,
          }),
        );
        return;
      }
      setError(cause);
      if (isStaleSlot(cause.code)) {
        // The phase document calls a silent failure or a raw error here a defect. The times are
        // recomputed and the choice dropped — and everything the Customer typed stays, which is
        // the half that would be easiest to lose and hardest to forgive.
        setSelection(null);
        setRefreshes((count) => count + 1);
      }
    } finally {
      setBooking(false);
    }
  }

  /**
   * Whether to show the details card.
   *
   * Not simply `selection !== null`, which is what it was until a raced `409` was watched in a
   * browser. A stale-slot refusal drops the chosen time deliberately — the times on screen were
   * computed against a world that has moved — and gating the card on the selection therefore
   * unmounted the name, phone and email at the precise moment the banner said "everything you
   * typed has been kept". The values were in fact kept, in this component's state, and came back
   * when a new time was picked; but a page has to be *visibly* honest, not merely technically
   * honest, and a claim the reader cannot check reads as a lie.
   *
   * So once anything has been typed the card stays. The submit button below remains gated on the
   * selection, because a time really is required — the Customer is missing a choice, not a field.
   */
  const showDetails =
    selection !== null || fullName !== '' || phone !== '' || email !== '' || note !== '';

  const fieldErrors = error?.fieldErrors ?? {};
  const rendered: ReadonlySet<string> = new Set(Object.values(FIELD));
  const everyMessageShown =
    error !== null &&
    Object.keys(fieldErrors).length > 0 &&
    Object.keys(fieldErrors).every((field) => rendered.has(field));
  const unfielded = error && !everyMessageShown ? error : null;

  return (
    <form onSubmit={onBook} className="flex flex-col gap-4" noValidate>
      <Card>
        <CardHeader
          title="What would you like booked?"
          description={
            services.length === 1
              ? 'One service is offered here.'
              : 'Each price and duration is what you will be charged and how long it takes.'
          }
        />
        <ul className="flex flex-col gap-2">
          {services.map((candidate) => {
            const selected = candidate.id === service?.id;
            return (
              <li key={candidate.id}>
                <button
                  type="button"
                  aria-pressed={selected}
                  onClick={() =>
                    ask(() => {
                      setServiceId(candidate.id);
                      // Somebody eligible for the previous Service need not be eligible for this
                      // one, and the server would refuse the booking at the very end of the flow.
                      setEmployee(null);
                    })
                  }
                  className={cn(
                    'w-full rounded-md border p-3 text-left transition',
                    selected
                      ? 'border-brand bg-brand/5'
                      : 'border-border bg-surface hover:bg-surface-muted',
                  )}
                >
                  <span className="flex items-baseline justify-between gap-3">
                    <span className="text-ink text-sm font-medium">{candidate.name}</span>
                    <span className="text-ink text-sm tabular-nums">
                      {formatMoney(candidate.price.amount, candidate.price.currency)}
                    </span>
                  </span>
                  <span className="text-ink-muted mt-0.5 block text-sm">
                    {formatDuration(candidate.durationMinutes)}
                  </span>
                  {candidate.description && (
                    <span className="text-ink-muted mt-1.5 block text-sm leading-relaxed">
                      {candidate.description}
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      </Card>

      {service && (
        <EmployeeChoice
          // Remounted per service, so the list can never be the previous service's while the new
          // one loads — the window in which somebody would be offered who cannot perform it.
          key={service.id}
          path={publicEmployeesPath(slug, service.id)}
          serviceName={service.name}
          chosen={employee}
          onChoose={(next) => ask(() => setEmployee(next))}
        />
      )}

      {service && path && (
        <Card>
          <CardHeader
            title="When suits you?"
            description={`${formatDuration(service.durationMinutes)}. Days with no times are either fully booked or closed.`}
          />

          <FortnightPicker
            key={`${path}#${refreshes}`}
            today={today}
            windowStart={windowStart}
            onPage={page}
            path={path}
            service={service}
            employeeName={employee?.fullName ?? null}
            chosenDate={chosenDate}
            onChooseDate={(date) => {
              setChosenDate(date);
              setSelection(null);
              setError(null);
            }}
            selectedStartsAt={selection?.startsAt ?? null}
            onSelectSlot={(slot: AvailableSlot, timezone) => {
              setSelection({
                startsAt: slot.startsAt,
                employeeId: slot.employee.id,
                employeeName: slot.employee.fullName,
                timezone,
              });
              setError(null);
            }}
          />
        </Card>
      )}

      {showDetails && (
        <Card>
          <CardHeader
            title="And who is it for?"
            description="Your phone number is how the business recognises you. No account, and no password."
          />
          <div className="flex flex-col gap-4">
            <Input
              label="Full name"
              value={fullName}
              maxLength={120}
              required
              autoComplete="name"
              onChange={(event) => setFullName(event.target.value)}
              error={fieldErrors[FIELD.fullName]}
            />
            <Input
              label="Phone"
              type="tel"
              value={phone}
              maxLength={30}
              required
              autoComplete="tel"
              onChange={(event) => setPhone(event.target.value)}
              hint={
                business.country
                  ? 'A local number is fine — otherwise start with +.'
                  : 'Start with + and your country code.'
              }
              error={fieldErrors[FIELD.phone]}
            />
            <Input
              label="Email"
              type="email"
              value={email}
              maxLength={254}
              autoComplete="email"
              onChange={(event) => setEmail(event.target.value)}
              hint="Optional, but it is the only way to get a confirmation and a link to change this booking."
              error={fieldErrors[FIELD.email]}
            />
            <Textarea
              label="Anything they should know?"
              value={note}
              maxLength={1000}
              rows={3}
              onChange={(event) => setNote(event.target.value)}
              hint="Optional."
              error={fieldErrors[FIELD.note]}
            />
          </div>
        </Card>
      )}

      {unfielded && (
        <p
          role="alert"
          className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
        >
          {unfielded.message}
          {unfielded.retryAfterSeconds !== undefined && (
            <span className="mt-1 block">
              Try again in {unfielded.retryAfterSeconds}{' '}
              {unfielded.retryAfterSeconds === 1 ? 'second' : 'seconds'}.
            </span>
          )}
          {isStaleSlot(unfielded.code) && (
            <span className="mt-1 block">
              The times above have been recalculated — choose one of those. Everything you typed has
              been kept.
            </span>
          )}
        </p>
      )}

      {selection && service && (
        <div className="flex flex-col gap-3">
          <p className="text-ink-muted text-sm">
            <span className="text-ink font-medium">{service.name}</span> with{' '}
            {selection.employeeName},{' '}
            {/* Off the chosen Slot rather than off the day strip: the strip's active day can be one
                this component never chose, because an unanswered `chosenDate` falls back to the
                soonest day with times inside the grid. The Slot is the only date that is certainly
                the one being booked. */}
            {formatIsoDate(toBusinessDate(selection.startsAt, selection.timezone))} at{' '}
            <span className="tabular-nums">
              {formatTime(selection.startsAt, selection.timezone)}
            </span>
          </p>
          <Button
            type="submit"
            loading={booking}
            disabled={fullName.trim() === '' || phone.trim() === ''}
            className="w-full sm:w-auto"
          >
            Confirm booking
          </Button>
        </div>
      )}
    </form>
  );
}

/**
 * Who can perform this Service, plus "Anyone available".
 *
 * The list comes from the server filtered by `serviceId` rather than being filtered here: the
 * public Employee list carries no assignments to filter *by*, and naming somebody who cannot
 * perform the Service produces `EMPLOYEE_CANNOT_PERFORM_SERVICE` at the end of the flow, after the
 * Customer has entered their details, rather than at the start.
 *
 * **Hidden entirely when one person can do it**, because then there is no choice to make. The grid
 * still names them on every Slot, so the Customer is never left wondering who they are booking.
 */
function EmployeeChoice({
  path,
  serviceName,
  chosen,
  onChoose,
}: {
  path: string;
  serviceName: string;
  chosen: ChosenEmployee | null;
  onChoose: (employee: ChosenEmployee | null) => void;
}) {
  const employees = useResource<PublicEmployee[]>(path);

  return (
    <ResourceGate resource={employees}>
      {(list) => {
        // Nobody assigned is not an empty picker either: the availability grid below says so
        // properly, with `NO_ELIGIBLE_EMPLOYEE` and copy written for a Customer. A second, vaguer
        // version of that message here would be the one they read first.
        if (list.length <= 1) return null;

        return (
          <Card>
            <CardHeader
              title="Anyone in particular?"
              description={`${list.length} people provide ${serviceName}.`}
            />
            <ul className="flex flex-wrap gap-2">
              <li>
                <Choice
                  selected={chosen === null}
                  onClick={() => onChoose(null)}
                  label="Anyone available"
                  detail="the most times"
                />
              </li>
              {list.map((employee) => (
                <li key={employee.id}>
                  <Choice
                    selected={chosen?.id === employee.id}
                    onClick={() => onChoose({ id: employee.id, fullName: employee.fullName })}
                    label={employee.fullName}
                    detail={employee.jobTitle}
                  />
                </li>
              ))}
            </ul>
          </Card>
        );
      }}
    </ResourceGate>
  );
}

function Choice({
  selected,
  onClick,
  label,
  detail,
}: {
  selected: boolean;
  onClick: () => void;
  label: string;
  detail: string | null;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        // 44 px, the smallest target a thumb hits reliably.
        'min-h-11 rounded-md border px-3 py-2 text-left text-sm transition',
        selected
          ? 'border-brand bg-brand text-brand-contrast font-medium'
          : 'border-border bg-surface text-ink hover:bg-surface-muted',
      )}
    >
      {label}
      {detail && (
        <span
          className={cn(
            'mt-0.5 block text-xs font-normal',
            selected ? 'text-brand-contrast/80' : 'text-ink-muted',
          )}
        >
          {detail}
        </span>
      )}
    </button>
  );
}
