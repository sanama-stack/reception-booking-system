package dev.reception.common.phone;

/**
 * Who will read a refusal about a phone number.
 *
 * <p>Not a tone flag. It decides whether the message may name a <em>remedy only one of them has</em>
 * — a Business with no country set cannot have a local number interpreted, and the fix for that is a
 * Settings field an owner can reach and a Customer cannot.
 *
 * <p><strong>Phase 08 shipped that defect and a browser found it.</strong> The public booking page
 * renders server-provided field messages verbatim, deliberately
 * (docs/02-product-architecture.md §7) — so a Customer booking with a country-less Business was told
 * to "set your country in Settings", a screen they have no account for and no route to. The
 * architecture is not at fault: rendering the server's words is what keeps one message in one place.
 * What was at fault is a shared service assuming which client it was speaking to.
 *
 * <p>This is the same defect class {@code CustomerFieldNames} solved for field <em>names</em> in the
 * same phase, one step along: the caller knows its audience and the shared service does not. So it
 * arrives the way {@code Actor} and {@code Clock} do — passed in, never inferred. A service that
 * guessed would eventually guess wrong, and the symptom is not a failure but advice that quietly
 * cannot be followed.
 *
 * <p>Deliberately two values and not one per entry point. What changes the words is whether the
 * reader can reach the dashboard, and the dashboard's booking form and its Customers screen are the
 * same answer to that question. A value per caller would invite copy that differed for no reason.
 */
public enum PhoneAudience {

    /** Someone signed in to the dashboard, who can change the Business's own settings. */
    OWNER,

    /**
     * A Customer on the public booking page, or talking to the Receptionist from phase 09.
     *
     * <p>They have no account and never will (CONTEXT.md), so a message for them may only ask for
     * something they can do to their own input.
     */
    CUSTOMER
}
