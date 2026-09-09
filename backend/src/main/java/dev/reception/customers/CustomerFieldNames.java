package dev.reception.customers;

/**
 * What the caller's request calls the three Customer fields.
 *
 * <p>{@link CustomerService} is reached by four entry points that spell the same three values
 * differently — the dashboard's flat body, the public booking page's nested one, the Customers
 * screen's correction form, and phase 09's Tool arguments. A validation failure has to come back
 * under the name the caller actually sent, because that is the string the client looks up to decide
 * which input to mark; a name it did not send matches nothing and the message is dropped on the
 * floor.
 *
 * <p>Phase 06 shipped exactly that defect. {@code CustomerService} named the field {@code phone} —
 * the domain's word for it — while the request had called it {@code customerPhone}, and the one
 * sentence telling an owner how to fix the number never reached the screen. The dashboard carried a
 * workaround that tried both names; phase 08 removes the need for it by having the server report the
 * name the request used.
 *
 * <p>Passed as an argument rather than inferred, for the reason {@code Actor} and {@code Clock} are:
 * a service that guessed which of four callers it was serving would eventually guess wrong, and the
 * symptom would be an error message that silently disappears rather than anything that fails.
 */
public record CustomerFieldNames(String fullName, String phone, String email) {

    /** The dashboard's booking body — {@code AppointmentRequests.CreateAppointment}. */
    public static final CustomerFieldNames DASHBOARD_BOOKING =
            new CustomerFieldNames("customerName", "customerPhone", "customerEmail");

    /**
     * The public booking body, whose customer fields are nested (docs/04-api-overview.md §6).
     *
     * <p>Dotted paths, which is what Bean Validation produces for a nested object and therefore what
     * the client is already indexing by for every other failure on this request.
     */
    public static final CustomerFieldNames PUBLIC_BOOKING =
            new CustomerFieldNames("customer.fullName", "customer.phone", "customer.email");

    /** The Customers screen's correction form — {@code CustomerRequests.PatchCustomer}. */
    public static final CustomerFieldNames CORRECTION = new CustomerFieldNames("fullName", "phone", "email");
}
