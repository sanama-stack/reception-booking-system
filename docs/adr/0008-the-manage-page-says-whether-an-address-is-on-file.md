# The manage page is told whether an address is on file, not that a message was sent

**Status:** accepted
**Date:** 2026-09-09

The `/manage/{token}` page promised *"A confirmation email should reach you within a minute or two"*
unconditionally after a cancel or a reschedule, and `NotificationEnqueuer.appointmentCancelled` and
`appointmentRescheduled` both return early without writing a row when the Customer has no address. This is
the defect [ADR-0007](./0007-booking-response-says-whether-a-confirmation-was-sent.md) fixed on
`/book/{slug}`, on the surface that ADR deliberately did not extend to.

It is reachable, and every link was checked at the file rather than reasoned about. A Manage Link is
delivered by email, so an address existed when the token was issued — but `PatchCustomer.email` is `@Email`
without `@NotBlank`, Jakarta's `@Email` accepts the empty string, and `CustomerService.patch` passes it to
`Customer.applyCorrection`, which reads blank as a clear. The dashboard's own form submits `""` when the
field is emptied (`customer-form.tsx`), so it is two clicks, not a curl. The token stays valid until
appointment end plus twenty-four hours (`ManageTokenService.issue`). Owner clears the address, Customer opens
a link that still works, and the screen promises a message no row exists for.

We add a single boolean, `emailOnFile`, to `PublicResponses.ManagedAppointment`, sourced in
`PublicAppointmentController.render` from the same `Customer.hasEmail()` the outbox asks.

## Why the field is named for the fact rather than the outcome

ADR-0007 named its bit `confirmationSent`, which is what the booking screen needs to hear. Reusing that name
here was considered and rejected: `ManagedAppointment` is returned by **four** endpoints — `GET /manage`,
`POST /lookup`, cancel and reschedule — and on the first two nothing has been sent. A field called
`confirmationSent` would have been inaccurate on half the surface carrying it, and its javadoc would have had
to explain a name that is wrong. `emailOnFile` is true on all four.

This costs one step of client reasoning: after a write the page reads the fact as "a message is coming".
That is the step ADR-0007 removed from the booking screen — but what that screen was reasoning *from* was
**the address the Customer had typed**, which the server never confirmed and which was wrong in both
directions. Reasoning from a server-sourced fact about the resolved Customer is the thing ADR-0007 wanted;
reasoning from an unverified client value is what it forbade. They are not the same move.

A separate write-only response carrying `confirmationSent` beside the appointment was the alternative that
avoids the step entirely and discloses nothing on the two reads. It was declined on cost: `manage-flow`
adopts each write's body *as* the appointment, so a wrapper changes the api client, both dialogs and the
flow, to buy a distinction the disclosure argument below says is not worth buying.

## Why one more bit is acceptable here

It is a smaller disclosure than the one already accepted. ADR-0007 accepted this bit behind a **phone number
alone**. This surface is reached by a signed token authorising exactly one appointment, or by a Confirmation
Code **and** the phone number that booked. Both are strictly stronger proofs, so a bit that was acceptable
there cannot be less acceptable here.

The address itself is still not returned, and that has not been reopened. `ManagedAppointment` already
refuses to echo a stored name, phone or email because doing so would turn a Confirmation Code into a way to
read them; `recipientEmail`, `customerEmail` and `sentTo` remain in `PublicFieldAllowListTest.NEVER`.

## Consequences

- `Customer.hasEmail()` now has three callers and is still the only definition of "reachable by email".
  `NotificationEnqueuer` decides whether a row is written, `PublicBookingController` what the confirmation
  screen may promise, and `PublicAppointmentController` what the manage page may promise. They cannot drift.
- The banner's "no email" branch tells the Customer their number is on file without an address and to ask the
  Business to add one. As in ADR-0007, that is the only route: **this ADR does not change stored data.**
- It makes **no claim that the Manage Link still works.** A reschedule to a later date can outlive the token,
  which was minted against the old end time, and with no address on file no reschedule email is sent to carry
  a fresh one. Saying "you can come back to this page" would have been the same kind of guess this ADR exists
  to remove.
- `emailOnFile` was added to `PublicFieldAllowListTest.ALLOWED` deliberately. Reusing `confirmationSent`
  would have needed no entry at all, because `ALLOWED` is a **flat set of key names** rather than per-record
  and ADR-0007 already put that name in it — the gate would have stayed green while the public contract
  changed. Anyone widening that set should know it does not partition by response type.
- `GET /manage` and `POST /lookup` now carry a bit no screen reads yet. The lookup page that
  `POST /public/appointments/lookup` was built for does not exist (phase 10 or 11), and when it does it will
  need exactly this: a Customer who booked without an address reaches the manage flow through a Confirmation
  Code having never had an email at all, which is not a narrow window but the ordinary case. A client-side
  softening of the sentence would have had to be undone at that point.
