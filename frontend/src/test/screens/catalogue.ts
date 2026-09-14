/**
 * Which components own a state, and where each one is asserted.
 *
 * The list of endpoints in the backend's `EndpointCatalogue` is produced by reflection and the
 * *judgement* about each one is written by hand, because a machine can say what exists and cannot
 * say what it owes anyone. This is the same split for the frontend: `coverage.test.ts` finds every
 * component that reads a resource or renders an empty state, this file says what is true of each,
 * and the gate fails when they disagree **in either direction**.
 *
 * So a new screen breaks the build until somebody writes down what its states are. Deleting a line
 * here is not a way to make the suite pass; it is a way to make the gate fail with the file's own
 * name in the message. Classify or fail.
 */

/** Where a component's loading and error states come from. */
export type StateOwner =
  /** `ResourceGate`, like every other screen. Its behaviour is asserted once, in its own test. */
  | 'RESOURCE_GATE'
  /** Hand-rolled, for a reason the entry has to give. */
  | 'ITS_OWN'
  /** It owns the states and something else renders them — that something is named. */
  | 'ELSEWHERE'
  /**
   * It never renders without its data, so there is no loading state to own: a server component
   * that awaits before it returns, or a client one handed its data as a prop.
   */
  | 'NO_LOAD';

export interface ResourceScreen {
  /** Path under `src/`. */
  file: string;
  states: StateOwner;
  /** Required for everything but `RESOURCE_GATE`: why the shared gate does not fit. */
  reason?: string;
  /** Required for `ITS_OWN`: where those hand-rolled states are asserted. */
  assertedIn?: string;
  /** Required for `ELSEWHERE`: the component that renders them. */
  shownBy?: string;
}

/**
 * Every component that reads a resource, and therefore has a loading state and an error state.
 *
 * Almost all of them route both through `ResourceGate`, which is what makes those two states the
 * same everywhere by construction rather than by everyone remembering. The exception is listed
 * with its reason, and is the only one whose states could drift without anything noticing.
 */
export const RESOURCE_SCREENS: ResourceScreen[] = [
  { file: 'app/(dashboard)/analytics/summary-screen.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/appointments/[id]/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/appointments/appointments-screen.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/appointments/new/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/appointments/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/calendar/calendar-screen.tsx', states: 'RESOURCE_GATE' },
  {
    file: 'app/(dashboard)/calendar/detail-drawer.tsx',
    states: 'ITS_OWN',
    reason:
      'Both states have to sit inside the dialog, under a heading and above a Close button. A ' +
      'gate wrapped around the drawer would put a bare spinner in a panel with no title and no ' +
      'way out of it.',
    assertedIn: 'app/(dashboard)/calendar/detail-drawer.test.tsx',
  },
  { file: 'app/(dashboard)/calendar/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/conversations/[id]/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/conversations/conversations-screen.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/customers/[id]/customer-history.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/customers/[id]/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/customers/customers-screen.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/dashboard/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/dashboard/quick-stats.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/dashboard/todays-agenda.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/employees/[id]/availability-section.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/employees/[id]/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/employees/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/services/[id]/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/services/new/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/services/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/settings/booking/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/settings/closures/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/settings/faqs/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/settings/hours/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/(dashboard)/settings/profile/page.tsx', states: 'RESOURCE_GATE' },
  { file: 'app/book/[slug]/classic-flow.tsx', states: 'RESOURCE_GATE' },
  { file: 'components/fortnight-picker.tsx', states: 'RESOURCE_GATE' },
  { file: 'components/slot-picker.tsx', states: 'RESOURCE_GATE' },

  // The four the gate could not see until discovery stopped asking for `useResource` by name.
  {
    file: 'app/book/[slug]/page.tsx',
    states: 'NO_LOAD',
    reason:
      'A server component. It awaits the business and its services before it returns anything at ' +
      'all, so there is no render in which the data is missing; a refusal is returned as a value ' +
      'and rendered through the shared `ErrorState`.',
  },
  {
    file: 'app/manage/[token]/page.tsx',
    states: 'NO_LOAD',
    reason:
      'A server component, like the booking page. An invalid or expired Manage Link is a screen ' +
      'of its own rather than an error state, because it is the expected answer rather than a ' +
      'fault.',
  },
  {
    file: 'app/manage/[token]/manage-flow.tsx',
    states: 'NO_LOAD',
    reason:
      'Its appointment arrives as a prop from the page above and every write returns the next one, ' +
      'so the only read here is the re-resolve after a refusal the screen thought impossible. That ' +
      'failure is swallowed on purpose: the refusal is already on screen and is the message that ' +
      'matters.',
  },
  {
    file: 'lib/auth/session-context.tsx',
    states: 'ELSEWHERE',
    shownBy: 'app/(dashboard)/auth-guard.tsx',
    reason:
      'The `/auth/me` every page waits on. It owns `status` and `error` and renders neither — the ' +
      'guard turns `loading` into `SessionPending` and a non-401 failure into `ErrorState`, ' +
      'because a 401 here is a signed-out visitor rather than a fault and must not be shown as ' +
      'one.',
  },
];

export interface EmptyStateScreen {
  /** Path under `src/`. */
  file: string;
  /**
   * Test files that must assert every **literal** `EmptyState` title this file renders. The gate
   * reads the titles out of the source, so adding one without asserting it fails the build.
   */
  assertedIn?: string[];
  /** Required when the file renders no literal title: where its copy actually comes from. */
  dynamic?: string;
  /** Required when nothing asserts it yet. Counted, and the count is pinned below. */
  notYet?: string;
}

/**
 * Every component that renders an empty state.
 *
 * Empty is the one state of the three that cannot be shared: an empty closure list and an empty
 * FAQ list say different things and offer different next steps, which is exactly why rule 7 asks
 * for it per screen (docs/09-phase-plan.md §5).
 */
export const EMPTY_STATES: EmptyStateScreen[] = [
  {
    file: 'app/(dashboard)/analytics/summary-screen.tsx',
    assertedIn: ['app/(dashboard)/analytics/summary-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/appointments/[id]/page.tsx',
    assertedIn: ['app/(dashboard)/appointments/[id]/page.test.tsx'],
  },
  {
    file: 'app/(dashboard)/appointments/[id]/reschedule-section.tsx',
    assertedIn: ['app/(dashboard)/appointments/[id]/reschedule-section.test.tsx'],
  },
  {
    file: 'app/(dashboard)/appointments/appointments-screen.tsx',
    assertedIn: ['app/(dashboard)/appointments/appointments-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/appointments/new/page.tsx',
    assertedIn: ['app/(dashboard)/appointments/new/page.test.tsx'],
  },
  {
    file: 'app/(dashboard)/calendar/calendar-screen.tsx',
    assertedIn: ['app/(dashboard)/calendar/calendar-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/conversations/conversations-screen.tsx',
    assertedIn: ['app/(dashboard)/conversations/conversations-screen.test.tsx'],
    dynamic:
      'Two titles, chosen by the `unofferedOnly` filter: "No conversations yet" and "Nothing unoffered" are different answers and the screen must not give the first when it means the second (ADR-0012). Both are asserted in its own test.',
  },
  {
    file: 'app/(dashboard)/customers/[id]/customer-history.tsx',
    assertedIn: ['app/(dashboard)/customers/[id]/customer-history.test.tsx'],
  },
  {
    file: 'app/(dashboard)/customers/customers-screen.tsx',
    assertedIn: ['app/(dashboard)/customers/customers-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/dashboard/todays-agenda.tsx',
    assertedIn: ['app/(dashboard)/dashboard/todays-agenda.test.tsx'],
  },
  {
    file: 'app/(dashboard)/employees/[id]/availability-section.tsx',
    assertedIn: ['app/(dashboard)/employees/[id]/availability-section.test.tsx'],
    dynamic:
      'Its third empty state is `explainEmptyReason`’s, asserted once in components/empty-reason.test.tsx — the preview, the booking flow and the public page all render that copy and none of them chooses it.',
  },
  {
    file: 'app/(dashboard)/employees/[id]/time-off-section.tsx',
    assertedIn: ['app/(dashboard)/employees/[id]/time-off-section.test.tsx'],
  },
  {
    file: 'app/(dashboard)/employees/employees-screen.tsx',
    assertedIn: ['app/(dashboard)/employees/employees-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/services/services-screen.tsx',
    assertedIn: ['app/(dashboard)/services/services-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/settings/closures/closures-screen.tsx',
    assertedIn: ['app/(dashboard)/settings/closures/closures-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/settings/faqs/faq-list.tsx',
    assertedIn: ['app/(dashboard)/settings/faqs/faq-list.test.tsx'],
  },
  {
    file: 'components/assignment-picker.tsx',
    dynamic:
      'The title is the `emptyTitle` prop — the copy belongs to whichever screen renders the picker.',
  },
  {
    file: 'components/fortnight-picker.tsx',
    assertedIn: ['components/empty-reason.test.tsx'],
    dynamic: 'Every title comes from `explainEmptyReason`; this component chooses none of them.',
  },
  {
    file: 'components/slot-picker.tsx',
    assertedIn: ['components/empty-reason.test.tsx'],
    dynamic: 'Every title comes from `explainEmptyReason`; this component chooses none of them.',
  },
];

/**
 * How many empty states are carried unasserted — a ratchet, not a target. **Zero since 2026-09-12.**
 *
 * Pinned exactly, so a new screen shipping an unasserted empty state turns the gate red rather
 * than joining a number nobody reads. Lowering it is the work; raising it is a decision somebody
 * has to make on purpose, in a diff.
 *
 * It stays here at zero rather than being deleted along with the last entry it counted. The four
 * it described were closed by writing the fixtures they were waiting for — `src/test/fixtures.ts`
 * — and the next screen to ship an unasserted empty state should meet the same gate rather than a
 * constant somebody removed because it had briefly stopped mattering.
 */
export const UNASSERTED_EMPTY_STATES = 0;

export interface WriteScreen {
  /** Path under `src/`. */
  file: string;
  /**
   * What the user is shown when this write fails, and where that showing lives.
   *
   * Required, and prose rather than an enum on purpose. The read side can be checked mechanically
   * because `ResourceGate` is one thing a file either uses or does not; a failed write is told in
   * at least four ways here — a banner, a toast, a notice inside a transcript, a callback handed
   * up to the page — and several screens use two of them for two different failures. An enum would
   * have to be either wrong or longer than the list it describes.
   */
  failure: string;
  /**
   * Test files that render this component and watch the write fail. Each must actually import it,
   * which is checked — a pointer to a test that no longer touches the file is worse than none,
   * because it reads as coverage.
   */
  assertedIn?: string[];
  /** Required when nothing asserts it yet. Counted, and the count is pinned below. */
  notYet?: string;
}

/**
 * Every component that writes, and what each one does when the write is refused.
 *
 * This list exists because the gate beside it could not see any of them. It discovered screens by
 * the name `useResource`, which is the read path and only the read path, so rule 7 — every screen
 * ships with empty, loading and error states — was enforced for the half of the application that
 * reads and not at all for the half that saves. Discovery now comes from the api surface itself
 * (`api-surface.ts`), so a component joins this list by calling something that posts rather than by
 * being remembered.
 *
 * **When it was first written, the suite had watched a read fail eight times and a write fail
 * never.** That is what the count below started at, and it is a debt rather than a finding: the
 * states are there and were built deliberately, but nothing has ever checked that pressing save
 * against a refusing server puts the server message in front of anybody.
 */
export const WRITE_SCREENS: WriteScreen[] = [
  {
    file: 'app/(auth)/login/login-form.tsx',
    failure:
      'A banner above the fields, plus the field messages on email and password. A wrong password and an unknown address are answered identically here because the server answers them identically.',
    assertedIn: ['app/(auth)/login/login-form.test.tsx'],
  },
  {
    file: 'app/(auth)/register/register-form.tsx',
    failure: 'A banner for anything unfielded, and the field messages on the four inputs.',
    assertedIn: ['app/(auth)/register/register-form.test.tsx'],
  },
  {
    file: 'app/(dashboard)/appointments/[id]/appointment-actions.tsx',
    failure:
      'A toast carrying the server message. A VERSION_CONFLICT is the exception: it says somebody else changed the appointment and reloads it, because a second attempt from a stale screen would be a guess.',
    notYet: 'The conflict branch is the one worth watching, and it needs two writers racing.',
  },
  {
    file: 'app/(dashboard)/appointments/[id]/reschedule-section.tsx',
    failure:
      'A banner under the picker. A stale-slot refusal also drops the chosen time and re-asks for the times, since the grid was computed against a world that has moved.',
    notYet:
      'Its test renders the section and asserts an empty state; no press reaches a refusing server.',
  },
  {
    file: 'app/(dashboard)/appointments/new/page.tsx',
    failure:
      'A banner, with the server field messages placed on the customer fields, and a stale-slot refusal that re-asks for times while keeping what was typed.',
    notYet:
      'Its test asserts the empty and the cleared-date states, neither of which is a failed write.',
  },
  {
    file: 'app/(dashboard)/customers/[id]/customer-form.tsx',
    failure: 'A banner for anything unfielded, and the field messages on name and email.',
    notYet: 'The component has no test at all.',
  },
  {
    file: 'app/(dashboard)/employees/[id]/page.tsx',
    failure:
      'Delegated to components/active-toggle.tsx, which toasts the server message and leaves the toggle where it was.',
    notYet: 'No test presses the toggle, here or in the shared control.',
  },
  {
    file: 'app/(dashboard)/employees/[id]/schedule-section.tsx',
    failure:
      'Delegated to components/week-editor.tsx: a banner above the grid, and the field messages placed on the times they name.',
    assertedIn: ['app/(dashboard)/employees/[id]/schedule-section.test.tsx'],
  },
  {
    file: 'app/(dashboard)/employees/[id]/services-section.tsx',
    failure:
      'A toast carrying the server message. The selection stays as the owner left it rather than snapping back.',
    notYet: 'The component has no test at all.',
  },
  {
    file: 'app/(dashboard)/employees/[id]/time-off-section.tsx',
    failure:
      'A banner for the add form with the field messages on the dates; a removal that fails toasts instead, because the row it was about is gone from the dialog.',
    notYet: 'Its test asserts the empty state only.',
  },
  {
    file: 'app/(dashboard)/employees/employee-form.tsx',
    failure:
      'A banner for anything unfielded, and the field messages on the inputs that render them.',
    notYet: 'The component has no test at all.',
  },
  {
    file: 'app/(dashboard)/employees/employees-screen.tsx',
    failure: 'Delegated to components/active-toggle.tsx, which toasts the server message.',
    assertedIn: ['app/(dashboard)/employees/employees-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/services/[id]/delete-service.tsx',
    failure:
      'The shared ErrorState, uniquely on this side. A refusal is the normal answer here rather than a fault, and the message names what to do instead, so it belongs on the page rather than in a toast that leaves.',
    assertedIn: ['app/(dashboard)/services/[id]/delete-service.test.tsx'],
  },
  {
    file: 'app/(dashboard)/services/[id]/page.tsx',
    failure: 'Delegated to components/active-toggle.tsx, which toasts the server message.',
    notYet: 'No test renders this page.',
  },
  {
    file: 'app/(dashboard)/services/service-form.tsx',
    failure:
      'A banner plus the field messages. A create that succeeds and whose assignment then fails toasts that halfway state on its own, because the service does exist.',
    assertedIn: ['app/(dashboard)/services/service-form.test.tsx'],
  },
  {
    file: 'app/(dashboard)/services/services-screen.tsx',
    failure: 'Delegated to components/active-toggle.tsx, which toasts the server message.',
    assertedIn: ['app/(dashboard)/services/services-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/settings/booking/booking-form.tsx',
    failure: 'A banner for anything unfielded, and the field messages on the policy inputs.',
    notYet: 'The component has no test at all.',
  },
  {
    file: 'app/(dashboard)/settings/closures/closures-screen.tsx',
    failure:
      'A banner for the add form with the field messages on the dates; a removal that fails toasts.',
    assertedIn: ['app/(dashboard)/settings/closures/closures-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/settings/faqs/faq-list.tsx',
    failure:
      'A banner in the add card and another in the row editor, each with its own field messages; removing and reordering toast.',
    notYet: 'Its test asserts the empty state only.',
  },
  {
    file: 'app/(dashboard)/settings/faqs/receptionist-notes.tsx',
    failure: 'A banner, and the field message on the notes box.',
    notYet: 'The component has no test at all.',
  },
  {
    file: 'app/(dashboard)/settings/faqs/receptionist-switch.tsx',
    failure:
      'A banner for anything unfielded, and the server message for the daily cap placed on the cap field.',
    notYet: 'The component has no test at all.',
  },
  {
    file: 'app/(dashboard)/settings/hours/hours-editor.tsx',
    failure:
      'Delegated to components/week-editor.tsx: a banner above the grid, and the field messages on the times.',
    assertedIn: ['app/(dashboard)/settings/hours/hours-editor.test.tsx'],
  },
  {
    file: 'app/(dashboard)/settings/profile/profile-form.tsx',
    failure:
      'A banner for anything unfielded, and the field messages on the inputs. SLUG_TAKEN carries no field entry and is placed on the slug field anyway, because that is where the owner just typed.',
    assertedIn: ['app/(dashboard)/settings/profile/profile-form.test.tsx'],
  },
  {
    file: 'app/book/[slug]/classic-flow.tsx',
    failure:
      'A banner carrying the server message and any Retry-After, with the field messages on the customer fields. A stale-slot refusal re-asks for times and keeps every detail already typed.',
    assertedIn: ['app/book/[slug]/classic-flow.test.tsx'],
  },
  {
    file: 'app/book/[slug]/receptionist-panel.tsx',
    failure:
      'The degradation notice in the transcript, carrying the server sentence verbatim, with a restart offered when the server says the conversation can be restarted.',
    notYet: 'Its test asserts the appointment cards; no turn is made to fail.',
  },
  {
    file: 'app/manage/[token]/cancel-dialog.tsx',
    failure:
      'Delegated to the page through onFailed, which renders the refusal above the summary and re-resolves the appointment when the refusal means this screen is stale.',
    assertedIn: ['app/manage/[token]/cancel-dialog.test.tsx'],
  },
  {
    file: 'app/manage/[token]/reschedule-card.tsx',
    failure:
      'Delegated to the page through onFailed. A stale-slot refusal also re-asks for times and says, in its own banner, that the appointment has not moved.',
    assertedIn: ['app/manage/[token]/reschedule-card.test.tsx'],
  },
  {
    file: 'lib/auth/session-context.tsx',
    failure:
      'Nothing, deliberately. The one write here is the sign-out, and the local session is over whether or not the server heard about it; the finally clause is what makes that true.',
    notYet:
      'A sign-out that fails is indistinguishable from one that worked, which is the intent, so a test would assert the redirect rather than a message.',
  },
];

/**
 * How many write failures are carried unasserted — a ratchet, the same shape as
 * {@link UNASSERTED_EMPTY_STATES} and for the same reason.
 *
 * **Twenty-eight when this list was written; twenty-seven now.** The login form was taken first
 * because it is the write a stranger meets before anything else, and because its failure branch
 * was the one that used to show nothing at all when the client handed it something that was not an
 * `ApiError` — which `lib/api/client.ts` no longer can.
 *
 * Lowering it is the work. Raising it is a decision somebody has to make on purpose, in a diff.
 */
export const UNASSERTED_WRITE_FAILURES = 15;
