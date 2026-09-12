import type { AppointmentWithHistory } from '@/lib/appointments';
import type { ServiceDetail, ServiceList } from '@/lib/catalog';
import type { Availability } from '@/lib/scheduling';
import type { EmployeeDetail, EmployeeList } from '@/lib/staff';

/**
 * The rows the four empty states of phase 11's last open testing row need, in one place.
 *
 * They overlap — the appointment detail page, the move panel, dashboard booking and the
 * availability preview all want the same service and the same person — so they are shared rather
 * than written four times slightly differently, which is how two tests come to disagree about what
 * a `ServiceDetail` looks like.
 *
 * **The envelope is at `UTC` while the suite runs at `Asia/Tbilisi`** (`vitest.config.mts`), which
 * is the same counterfactual every other file here rests on: a screen reading the browser's zone
 * instead of this envelope's would draw these times four hours late.
 *
 * Not a `.tsx`, deliberately. `coverage.test.ts` scans `.tsx` files for `<EmptyState` and
 * `useResource`, and a fixture module that matched either would be asked to classify itself as a
 * screen.
 */

export const SERVICE: ServiceDetail = {
  id: 'service-1',
  name: 'Haircut',
  description: null,
  durationMinutes: 45,
  bufferBeforeMinutes: 0,
  bufferAfterMinutes: 0,
  price: { amount: '40.00', currency: 'GEL' },
  active: true,
  employeeIds: ['employee-1'],
  updatedAt: '2026-09-01T09:00:00Z',
};

export const APPOINTMENT: AppointmentWithHistory = {
  timezone: 'UTC',
  appointment: {
    id: 'appointment-1',
    startsAt: '2026-09-20T09:00:00Z',
    endsAt: '2026-09-20T09:45:00Z',
    status: 'CONFIRMED',
    service: { id: SERVICE.id, name: SERVICE.name },
    employee: { id: 'employee-1', name: 'Nino Beridze' },
    customer: {
      id: 'customer-1',
      fullName: 'Clara Classic',
      phone: '+995555000222',
      email: 'clara@example.com',
    },
    price: { amount: '40.00', currency: 'GEL' },
    confirmationCode: 'AB12CD',
    source: 'CLASSIC',
    customerNote: null,
    cancelledAt: null,
    cancelledBy: null,
    cancellationReason: null,
    createdAt: '2026-09-01T09:00:00Z',
    updatedAt: '2026-09-01T09:00:00Z',
  },
  history: [
    {
      id: 'event-1',
      type: 'CREATED',
      actorType: 'CUSTOMER',
      actorId: null,
      payload: {},
      at: '2026-09-01T09:00:00Z',
    },
  ],
};

export const NO_SERVICES: ServiceList = { services: [] };
export const NO_EMPLOYEES: EmployeeList = { employees: [] };

export const EMPLOYEE: EmployeeDetail = {
  id: 'employee-1',
  fullName: 'Nino Beridze',
  email: null,
  phone: null,
  jobTitle: null,
  active: true,
  serviceIds: [SERVICE.id],
  updatedAt: '2026-09-01T09:00:00Z',
};

export const EMPLOYEES: EmployeeList = { employees: [EMPLOYEE] };

/**
 * A day the engine answered for, with nothing on it.
 *
 * `emptyReason` is never null here on purpose: a client must be able to tell "there is no reason"
 * from "this server did not answer", and a fixture that blurred the two would let a screen render
 * `explainEmptyReason(null)` in a test and something else entirely in a browser.
 */
export const NO_TIMES: Availability = {
  timezone: 'UTC',
  days: [{ date: '2026-09-20', slots: [] }],
  emptyReason: 'FULLY_BOOKED',
};
