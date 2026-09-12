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
  | 'ITS_OWN';

export interface ResourceScreen {
  /** Path under `src/`. */
  file: string;
  states: StateOwner;
  /** Required for `ITS_OWN`: why the shared gate does not fit. */
  reason?: string;
  /** Required for `ITS_OWN`: where those hand-rolled states are asserted. */
  assertedIn?: string;
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
    notYet:
      "The appointment detail page's “No history”. Needs a full `AppointmentWithHistory` fixture, which nothing else here has yet.",
  },
  {
    file: 'app/(dashboard)/appointments/[id]/reschedule-section.tsx',
    notYet:
      'Its “This service no longer exists” is the interesting one and needs an `AppointmentDetail` fixture.',
  },
  {
    file: 'app/(dashboard)/appointments/appointments-screen.tsx',
    assertedIn: ['app/(dashboard)/appointments/appointments-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/appointments/new/page.tsx',
    notYet: 'Reads three resources before it can render either empty state.',
  },
  {
    file: 'app/(dashboard)/calendar/calendar-screen.tsx',
    assertedIn: ['app/(dashboard)/calendar/calendar-screen.test.tsx'],
  },
  {
    file: 'app/(dashboard)/conversations/conversations-screen.tsx',
    assertedIn: ['app/(dashboard)/conversations/conversations-screen.test.tsx'],
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
    notYet: 'Two literal titles plus an `explainEmptyReason` one; needs the availability fixture.',
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
 * How many empty states are carried unasserted — a ratchet, not a target.
 *
 * Pinned exactly, so a new screen shipping an unasserted empty state turns the gate red rather
 * than joining a number nobody reads. Lowering it is the work; raising it is a decision somebody
 * has to make on purpose, in a diff.
 */
export const UNASSERTED_EMPTY_STATES = 4;
