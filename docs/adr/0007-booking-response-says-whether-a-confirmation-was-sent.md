# The booking response says *whether* a confirmation was sent, never *where*

**Status:** accepted
**Date:** 2026-09-09

A Customer is identified by phone number and nothing else, and `CustomerService.findOrCreate` returns a
matched `(business_id, phone)` row untouched — so the email in a booking request is not necessarily the
address the confirmation goes to, and when the stored record has none, no message is sent at all
(`NotificationEnqueuer` gates on the *Customer's* address). The confirmation screen was written against the
address the Customer had typed, and was therefore wrong in three ways: it named the typed address when the
mail went to the stored one; it promised a message when none was enqueued; and its "you gave no email, so
there is nothing to send" branch was false whenever a returning number already had an address on file.

We add a single boolean, `confirmationSent`, to `PublicResponses.BookedAppointment`. We deliberately do
**not** return the resolved recipient. `ManagedAppointment` already refuses to echo a stored name, phone or
email because doing so would turn a Confirmation Code into a way to read them; booking is authenticated more
weakly still — a phone number alone — so returning the address would let anyone holding a number learn the
address filed against it. A three-state field distinguishing "sent to the address you gave" from "sent to
the address on file" was considered and rejected for the same reason at one remove: it is an oracle for
confirming a guessed address against a phone number, and the screen does not need that to be honest.

## Consequences

- The screen never names an address in the success case. It says the confirmation is on its way "to the
  email address on file for this number" — vaguer than before for the majority of bookings, where the two
  addresses agree, and the price of not guessing in the minority where they do not. The Customer typed that
  address seconds earlier, so reading it back was reassurance rather than information.
- `Customer.hasEmail()` is now the single reachability predicate, asked by `NotificationEnqueuer` to decide
  whether a row is written and by `PublicBookingController` to decide what the screen may promise. The two
  answers cannot drift because there is only one.
- The public booking response carries one extra bit about a returning Customer: that the number has an
  address on file. Accepted as the smallest disclosure that makes the screen truthful; `recipientEmail`,
  `customerEmail` and `sentTo` are named in `PublicFieldAllowListTest.NEVER` so the rejected shape fails the
  build rather than being re-argued.
- The "no confirmation is being sent" branch tells the Customer their number is on file without an address
  and to ask the Business to add it. That is the only route: **this ADR does not change stored data.** A
  public form overwriting a Customer's contact details is the failure `findOrCreate` exists to prevent for
  the name, and no ruling had ever extended it to the email — the option was genuinely open and was
  declined, not assumed closed.
- The dashboard's own `AppointmentResponses.BookedAppointment` is untouched. An owner booking at the front
  desk can see the Customer record, so the screen has no promise to get wrong.
