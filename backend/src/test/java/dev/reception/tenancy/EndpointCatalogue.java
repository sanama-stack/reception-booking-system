package dev.reception.tenancy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every endpoint this application maps, and what tenant isolation means for each one.
 *
 * <p>Phase 11 asks for an isolation suite "parameterised over <strong>every</strong> tenant-scoped
 * endpoint, discovered by reflection over controller mappings so a new endpoint cannot be silently
 * omitted". Reflection alone cannot do that: it can list the endpoints, but it cannot say which of
 * them owns tenant data, and a suite that guessed would either skip the endpoint that leaks or
 * drown in false alarms over {@code /auth/login}.
 *
 * <p>So discovery and judgement are separated. Reflection produces the list; this file records the
 * judgement, one line per endpoint; and {@link EndpointCoverageTest} fails if the two disagree in
 * <em>either</em> direction — an endpoint nobody classified, or a classification for an endpoint
 * that no longer exists. That is the property worth having: adding a controller method breaks the
 * build until someone writes down what isolation means for it. <strong>Classify or fail.</strong>
 *
 * <p>Deleting a line here is not a way to make the suite pass. It is a way to make
 * {@code EndpointCoverageTest} fail with the endpoint's own name in the message.
 */
final class EndpointCatalogue {

    private EndpointCatalogue() {}

    /** What isolation means for one endpoint, and therefore which probe proves it. */
    enum Isolation {
        /**
         * Takes a path id naming a tenant-owned row. Probed: authenticate as A, pass B's real id,
         * expect {@code 404} — never {@code 403}, which would confirm the row exists, and never
         * {@code 200}.
         */
        OWNER_RESOURCE_ID,

        /**
         * Takes no id and answers with a collection of the caller's own rows. Probed: A's response
         * must contain nothing of B's.
         */
        OWNER_COLLECTION,

        /**
         * The Business itself, or a part of it. There is no id to borrow — the tenant comes from
         * the Membership — so the probe is that A's response describes A.
         */
        OWNER_SINGLETON,

        /**
         * The tenant is the slug in the path, and the caller is a stranger. Probed: slug A never
         * answers with B's rows.
         */
        PUBLIC_SLUG,

        /**
         * Reached with a Manage Link token rather than a session. Probed: a token for A's
         * Appointment cannot act on B's.
         */
        PUBLIC_MANAGE_TOKEN,

        /**
         * Owns no tenant data. Every entry carries a written reason, because "this one is fine" is
         * exactly the sentence that precedes a leak.
         */
        NO_TENANT
    }

    /** Which of Business B's rows lends its id to the probe. */
    enum Borrowed {
        NONE,
        SERVICE,
        EMPLOYEE,
        EMPLOYEE_AND_TIME_OFF,
        APPOINTMENT,
        CUSTOMER,
        CONVERSATION,
        CLOSURE,
        FAQ
    }

    /**
     * One classification.
     *
     * @param isolation what isolation means here
     * @param borrowed which of B's rows the probe borrows, for {@link Isolation#OWNER_RESOURCE_ID}
     * @param why prose, required wherever the answer is not "probe it with a stolen id"
     */
    record Classification(Isolation isolation, Borrowed borrowed, String why) {

        static Classification resource(Borrowed borrowed) {
            return new Classification(Isolation.OWNER_RESOURCE_ID, borrowed, "");
        }

        static Classification collection() {
            return new Classification(Isolation.OWNER_COLLECTION, Borrowed.NONE, "");
        }

        static Classification singleton() {
            return new Classification(Isolation.OWNER_SINGLETON, Borrowed.NONE, "");
        }

        static Classification publicSlug() {
            return new Classification(Isolation.PUBLIC_SLUG, Borrowed.NONE, "");
        }

        static Classification manageToken() {
            return new Classification(Isolation.PUBLIC_MANAGE_TOKEN, Borrowed.NONE, "");
        }

        static Classification exempt(String why) {
            return new Classification(Isolation.NO_TENANT, Borrowed.NONE, why);
        }
    }

    /**
     * Keyed by {@code "METHOD /pattern"} exactly as Spring reports it, with no {@code /api} prefix
     * — the context path is configuration and belongs to the client, not to the mapping.
     */
    static final Map<String, Classification> ENDPOINTS = new LinkedHashMap<>();

    static {
        // -------------------------------------------------------------------
        // Services
        // -------------------------------------------------------------------
        ENDPOINTS.put("GET /services", Classification.collection());
        ENDPOINTS.put("POST /services", Classification.exempt(
                "creates a row for the caller's own Business; there is no id to borrow. The body's "
                        + "cross-tenant references are the subject of CrossTenantAssignmentTest"));
        ENDPOINTS.put("GET /services/{id}", Classification.resource(Borrowed.SERVICE));
        ENDPOINTS.put("PATCH /services/{id}", Classification.resource(Borrowed.SERVICE));
        ENDPOINTS.put("DELETE /services/{id}", Classification.resource(Borrowed.SERVICE));
        ENDPOINTS.put("POST /services/{id}/activate", Classification.resource(Borrowed.SERVICE));
        ENDPOINTS.put("POST /services/{id}/deactivate", Classification.resource(Borrowed.SERVICE));
        ENDPOINTS.put("PUT /services/{id}/employees", Classification.resource(Borrowed.SERVICE));

        // -------------------------------------------------------------------
        // Employees
        // -------------------------------------------------------------------
        ENDPOINTS.put("GET /employees", Classification.collection());
        ENDPOINTS.put("POST /employees", Classification.exempt(
                "creates a row for the caller's own Business; there is no id to borrow"));
        ENDPOINTS.put("GET /employees/{id}", Classification.resource(Borrowed.EMPLOYEE));
        ENDPOINTS.put("PATCH /employees/{id}", Classification.resource(Borrowed.EMPLOYEE));
        ENDPOINTS.put("POST /employees/{id}/activate", Classification.resource(Borrowed.EMPLOYEE));
        ENDPOINTS.put("POST /employees/{id}/deactivate", Classification.resource(Borrowed.EMPLOYEE));
        ENDPOINTS.put("PUT /employees/{id}/services", Classification.resource(Borrowed.EMPLOYEE));
        ENDPOINTS.put("GET /employees/{id}/schedule", Classification.resource(Borrowed.EMPLOYEE));
        ENDPOINTS.put("PUT /employees/{id}/schedule", Classification.resource(Borrowed.EMPLOYEE));
        ENDPOINTS.put("GET /employees/{id}/time-off", Classification.resource(Borrowed.EMPLOYEE));
        ENDPOINTS.put("POST /employees/{id}/time-off", Classification.resource(Borrowed.EMPLOYEE));
        ENDPOINTS.put(
                "DELETE /employees/{id}/time-off/{offId}", Classification.resource(Borrowed.EMPLOYEE_AND_TIME_OFF));

        // -------------------------------------------------------------------
        // Appointments
        // -------------------------------------------------------------------
        ENDPOINTS.put("GET /appointments", Classification.collection());
        ENDPOINTS.put("POST /appointments", Classification.exempt(
                "books into the caller's own Business; a body naming B's Service or Employee is "
                        + "CrossTenantAssignmentTest's subject, not an id in the path"));
        ENDPOINTS.put("GET /appointments/{id}", Classification.resource(Borrowed.APPOINTMENT));
        ENDPOINTS.put("POST /appointments/{id}/cancel", Classification.resource(Borrowed.APPOINTMENT));
        ENDPOINTS.put("POST /appointments/{id}/reschedule", Classification.resource(Borrowed.APPOINTMENT));
        ENDPOINTS.put("POST /appointments/{id}/status", Classification.resource(Borrowed.APPOINTMENT));

        // -------------------------------------------------------------------
        // Customers
        // -------------------------------------------------------------------
        ENDPOINTS.put("GET /customers", Classification.collection());
        ENDPOINTS.put("GET /customers/{id}", Classification.resource(Borrowed.CUSTOMER));
        ENDPOINTS.put("PATCH /customers/{id}", Classification.resource(Borrowed.CUSTOMER));
        ENDPOINTS.put("GET /customers/{id}/appointments", Classification.resource(Borrowed.CUSTOMER));

        // -------------------------------------------------------------------
        // Conversations — the Receptionist's transcripts
        // -------------------------------------------------------------------
        ENDPOINTS.put("GET /conversations", Classification.collection());
        ENDPOINTS.put("GET /conversations/{id}", Classification.resource(Borrowed.CONVERSATION));

        // -------------------------------------------------------------------
        // The Business itself
        // -------------------------------------------------------------------
        ENDPOINTS.put("GET /business", Classification.singleton());
        ENDPOINTS.put("PATCH /business", Classification.singleton());
        ENDPOINTS.put("GET /business/hours", Classification.singleton());
        ENDPOINTS.put("PUT /business/hours", Classification.singleton());
        ENDPOINTS.put("GET /business/onboarding", Classification.singleton());
        ENDPOINTS.put("GET /business/closures", Classification.collection());
        ENDPOINTS.put("POST /business/closures", Classification.exempt(
                "creates a row for the caller's own Business; there is no id to borrow"));
        ENDPOINTS.put("DELETE /business/closures/{id}", Classification.resource(Borrowed.CLOSURE));
        ENDPOINTS.put("GET /business/faqs", Classification.collection());
        ENDPOINTS.put("POST /business/faqs", Classification.exempt(
                "creates a row for the caller's own Business; there is no id to borrow"));
        ENDPOINTS.put("PATCH /business/faqs/{id}", Classification.resource(Borrowed.FAQ));
        ENDPOINTS.put("DELETE /business/faqs/{id}", Classification.resource(Borrowed.FAQ));

        // -------------------------------------------------------------------
        // Reads that answer over a range rather than an id
        // -------------------------------------------------------------------
        ENDPOINTS.put("GET /calendar", Classification.collection());
        ENDPOINTS.put("GET /analytics/summary", Classification.singleton());
        ENDPOINTS.put("GET /availability", Classification.collection());

        // -------------------------------------------------------------------
        // Public — the tenant is the slug
        // -------------------------------------------------------------------
        ENDPOINTS.put("GET /public/businesses/{slug}", Classification.publicSlug());
        ENDPOINTS.put("GET /public/businesses/{slug}/services", Classification.publicSlug());
        ENDPOINTS.put("GET /public/businesses/{slug}/employees", Classification.publicSlug());
        ENDPOINTS.put("GET /public/businesses/{slug}/availability", Classification.publicSlug());
        ENDPOINTS.put("POST /public/businesses/{slug}/appointments", Classification.publicSlug());
        ENDPOINTS.put("POST /public/businesses/{slug}/chat/session", Classification.publicSlug());
        ENDPOINTS.put("POST /public/businesses/{slug}/chat", Classification.publicSlug());

        // -------------------------------------------------------------------
        // Public — the Manage Link
        // -------------------------------------------------------------------
        ENDPOINTS.put("POST /public/appointments/lookup", Classification.manageToken());
        ENDPOINTS.put("GET /public/appointments/manage", Classification.manageToken());
        ENDPOINTS.put("GET /public/appointments/manage/availability", Classification.manageToken());
        ENDPOINTS.put("POST /public/appointments/{id}/cancel", Classification.manageToken());
        ENDPOINTS.put("POST /public/appointments/{id}/reschedule", Classification.manageToken());

        // -------------------------------------------------------------------
        // No tenant data
        // -------------------------------------------------------------------
        ENDPOINTS.put("POST /auth/register", Classification.exempt(
                "creates the Business and its first Membership; there is no tenant yet to isolate"));
        ENDPOINTS.put("POST /auth/login", Classification.exempt(
                "authenticates a User; the Membership it resolves is what every other endpoint trusts"));
        ENDPOINTS.put("POST /auth/refresh", Classification.exempt("rotates the caller's own session"));
        ENDPOINTS.put("POST /auth/logout", Classification.exempt("ends the caller's own session"));
        ENDPOINTS.put("GET /auth/me", Classification.exempt(
                "answers about the caller's own User and Membership, by definition"));
        ENDPOINTS.put("GET /health", Classification.exempt(
                "reports the database and the mail transport; it names no Business"));
    }
}
