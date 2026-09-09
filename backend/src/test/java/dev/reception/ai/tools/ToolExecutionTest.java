package dev.reception.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import dev.reception.tenancy.TenantAdoption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 1 of docs/08-testing-strategy.md §7: the tools, with no model anywhere.
 *
 * <p>Each tool is called through {@link ToolRegistry} exactly as the orchestration loop calls it,
 * with a {@link ToolContext} built by hand. That is deliberate rather than a shortcut around HTTP:
 * what is under test here is what a tool does with arguments and a context, and putting a scripted
 * model in front of it would mean every assertion also depended on the loop.
 *
 * <p>The business is still built over HTTP by {@link BookingScenario}, so the data these tools read
 * is data the application would actually let an owner create.
 */
class ToolExecutionTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private TenantAdoption tenants;

    @Autowired
    private ObjectMapper json;

    private BookingScenario aria;
    private UUID businessId;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
        businessId = UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class));

        // The tenant a filter would have resolved from the slug, adopted by hand because there is no
        // request here. This is the one thing the loop does for a tool that a test must do itself.
        tenants.adopt(businessId);
        context = new ToolContext(businessId, UUID.randomUUID(), AuthorizedAppointments.none(), clock);
    }

    // ---------------------------------------------------------------- reads

    @Test
    @DisplayName("get_business_info returns the configured business, with today's date in its own timezone")
    void business_info_is_returned_for_a_valid_context() {
        ObjectNode result = call("get_business_info", "{}");

        assertThat(result.path("name").asText()).isEqualTo("Salon Aria");
        assertThat(result.path("timezone").asText()).isEqualTo(BookingScenario.TBILISI.getId());
        // The model has no clock; this field is what every "tomorrow" resolves against.
        assertThat(result.path("today").asText())
                .isEqualTo(java.time.LocalDate.now(clock.withZone(BookingScenario.TBILISI)).toString());
        assertThat(result.path("opening_hours")).isNotEmpty();
    }

    @Test
    @DisplayName("get_services returns active services with their price as a string, never a float")
    void services_are_returned_with_exact_prices() {
        ObjectNode result = call("get_services", "{}");

        JsonNode services = result.path("services");
        assertThat(services).hasSize(1);
        assertThat(services.get(0).path("name").asText()).isEqualTo("Haircut");
        assertThat(services.get(0).path("duration_minutes").asInt()).isEqualTo(60);
        // A string, because a price that crosses JSON as a double comes back as 59.999999 and the
        // Receptionist reads it aloud.
        assertThat(services.get(0).path("price").asText()).isEqualTo("60.00");
    }

    @Test
    @DisplayName("get_services omits a deactivated service, so the model cannot offer one")
    void an_inactive_service_is_not_offered() {
        aria.owner.post("/services/" + aria.serviceId + "/deactivate", Map.of());

        assertThat(call("get_services", "{}").path("services")).isEmpty();
    }

    @Test
    @DisplayName("get_service_details names who can perform it")
    void service_details_resolve_the_employees_that_can_perform_it() {
        ObjectNode result = call("get_service_details", args("service_id", aria.serviceId));

        assertThat(result.path("name").asText()).isEqualTo("Haircut");
        assertThat(result.path("performed_by")).hasSize(1);
        assertThat(result.path("performed_by").get(0).path("name").asText()).isEqualTo("Nino Beridze");
    }

    /**
     * <strong>The cross-tenant probe.</strong> A service id that is perfectly real, in a business
     * this conversation is not in.
     */
    @Test
    @DisplayName("a service_id from another business is not found, not forbidden")
    void a_service_from_another_business_is_not_found() {
        String foreignServiceId = serviceInAnotherBusiness();

        ObjectNode result = call("get_service_details", args("service_id", foreignServiceId));

        // NOT_FOUND rather than FORBIDDEN: "does not exist" and "is not yours" are indistinguishable
        // everywhere in this application, and a distinct code would confirm the row exists.
        assertThat(result.path("error").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("find_available_slots returns real slots, each with the employee who would perform it")
    void availability_matches_the_engine() {
        ObjectNode result = call(
                "find_available_slots",
                args("service_id", aria.serviceId, "date_from", aria.monday.toString()));

        JsonNode slots = result.path("slots");
        assertThat(slots).isNotEmpty();
        assertThat(slots.get(0).path("employee_id").asText()).isEqualTo(aria.employeeId);
        assertThat(slots.get(0).path("employee_name").asText()).isEqualTo("Nino Beridze");
        // With an offset, because create_appointment takes this value straight back.
        assertThat(slots.get(0).path("starts_at").asText()).contains("+");
    }

    @Test
    @DisplayName("find_available_slots honours a time-of-day constraint the engine has no parameter for")
    void availability_filters_by_earliest_time() {
        ObjectNode result = call(
                "find_available_slots",
                args(
                        "service_id", aria.serviceId,
                        "date_from", aria.monday.toString(),
                        "earliest_time", "15:00"));

        assertThat(result.path("slots")).isNotEmpty();
        result.path("slots").forEach(slot -> {
            // Parsed rather than string-matched. The business closes at 17:00 and the service runs
            // an hour, so the slots that survive this filter are 15:00 and 16:00 — an assertion
            // written against the text of one of them passes for the wrong reason.
            java.time.LocalTime startsAt = java.time.OffsetDateTime.parse(slot.path("starts_at").asText())
                    .atZoneSameInstant(BookingScenario.TBILISI)
                    .toLocalTime();
            assertThat(startsAt)
                    .as("slot at %s should not have survived earliest_time=15:00", startsAt)
                    .isAfterOrEqualTo(java.time.LocalTime.of(15, 0));
        });
    }

    /**
     * A range wider than the engine's own ceiling. Narrowed rather than refused, and the response
     * says so — silently answering a smaller question would make {@code truncated} a lie.
     */
    @Test
    @DisplayName("a range longer than fourteen days is narrowed, and the reply says it was")
    void a_long_range_is_capped_and_reported() {
        ObjectNode result = call(
                "find_available_slots",
                args(
                        "service_id", aria.serviceId,
                        "date_from", aria.monday.toString(),
                        "date_to", aria.monday.plusMonths(3).toString()));

        assertThat(result.path("range_narrowed_to_days").asInt())
                .isEqualTo(FindAvailableSlotsTool.MAX_DAYS);
        assertThat(result.path("searched_to").asText())
                .isEqualTo(aria.monday.plusDays(FindAvailableSlotsTool.MAX_DAYS - 1L).toString());
    }

    // ---------------------------------------------------------------- writes

    @Test
    @DisplayName("create_appointment books, and authorises the appointment it created")
    void booking_succeeds_and_grows_the_authority_set() {
        ObjectNode result = call("create_appointment", bookingArgs(aria.at(aria.monday, 10, 0)));

        assertThat(result.has("error")).isFalse();
        assertThat(result.path("confirmation_code").asText()).isNotBlank();
        assertThat(result.path("service_name").asText()).isEqualTo("Haircut");
        assertThat(result.path("price").asText()).isEqualTo("60.00");
        // No address was given, so no email was enqueued — and the model is told, rather than left
        // to promise one (ADR-0007).
        assertThat(result.path("confirmation_email_sent").asBoolean()).isFalse();

        // The authorisation, which is what lets the next turn move this appointment.
        UUID booked = UUID.fromString(result.path("appointment_id").asText());
        assertThat(context.authorized().contains(booked)).isTrue();
    }

    @Test
    @DisplayName("the appointment create_appointment writes is recorded as coming from the AI")
    void a_booking_made_by_a_tool_records_its_source() {
        ObjectNode result = call("create_appointment", bookingArgs(aria.at(aria.monday, 10, 0)));

        String source = jdbc.queryForObject(
                "select source from appointments where id = ?::uuid",
                String.class,
                result.path("appointment_id").asText());
        assertThat(source).isEqualTo("AI");
    }

    @Test
    @DisplayName("create_appointment on a taken slot returns SLOT_UNAVAILABLE rather than throwing")
    void booking_a_taken_slot_is_a_structured_refusal() {
        OffsetDateTime contested = aria.at(aria.monday, 11, 0);
        aria.bookedAt(contested);

        ObjectNode result = call("create_appointment", bookingArgs(contested));

        assertThat(result.path("error").asText()).isEqualTo("SLOT_UNAVAILABLE");
        // A sentence the model can read out, not a stack frame.
        assertThat(result.path("message").asText()).isNotBlank();
    }

    /**
     * <strong>A time no slot was ever offered for.</strong> The model is not stopped from asking;
     * it is refused when it does, by the same re-validation every other caller meets.
     */
    @Test
    @DisplayName("a fabricated time is refused by the availability re-check")
    void an_invented_time_does_not_book() {
        // 03:00, hours before the business opens at 09:00.
        ObjectNode result = call("create_appointment", bookingArgs(aria.at(aria.monday, 3, 0)));

        assertThat(result.path("error").asText()).isEqualTo("OUTSIDE_BUSINESS_HOURS");
        assertThat(jdbc.queryForObject("select count(*) from appointments", Long.class)).isZero();
    }

    @Test
    @DisplayName("lookup_appointment with a matching code and phone authorises it")
    void a_correct_lookup_grows_the_authority_set() {
        String appointmentId = aria.bookedAt(aria.at(aria.monday, 12, 0));
        String code = confirmationCodeOf(appointmentId);

        ObjectNode result = call(
                "lookup_appointment",
                args("confirmation_code", code, "phone", BookingScenario.CUSTOMER_PHONE));

        assertThat(result.path("appointment_id").asText()).isEqualTo(appointmentId);
        assertThat(context.authorized().contains(UUID.fromString(appointmentId))).isTrue();
    }

    @Test
    @DisplayName("lookup_appointment with the right code and the wrong phone finds nothing")
    void a_wrong_phone_proves_nothing() {
        String appointmentId = aria.bookedAt(aria.at(aria.monday, 12, 0));

        ObjectNode result = call(
                "lookup_appointment",
                args("confirmation_code", confirmationCodeOf(appointmentId), "phone", "+995555000111"));

        assertThat(result.path("error").asText()).isEqualTo("NOT_FOUND");
        assertThat(context.authorized().contains(UUID.fromString(appointmentId))).isFalse();
    }

    /**
     * <strong>The hole this tool would open if it reused the public lookup.</strong>
     *
     * <p>{@code PublicAppointmentAuthority.byLookup} searches every tenant and adopts whichever one
     * the code names — correct there, because a customer following a link from an email has not said
     * which business they mean. Here they have. A code that is perfectly valid at another business
     * must not resolve, or business A's Receptionist becomes a way to cancel business B's
     * appointments.
     */
    @Test
    @DisplayName("a real confirmation code from another business does not resolve in this conversation")
    void a_code_from_another_business_is_not_found() {
        ForeignBooking foreign = bookingInAnotherBusiness();

        tenants.adopt(businessId);
        ObjectNode result = call(
                "lookup_appointment", args("confirmation_code", foreign.code(), "phone", foreign.phone()));

        assertThat(result.path("error").asText()).isEqualTo("NOT_FOUND");
        assertThat(context.authorized().contains(foreign.appointmentId())).isFalse();
    }

    @Test
    @DisplayName("cancel_appointment refuses an id this conversation never proved")
    void cancelling_an_unauthorised_appointment_is_refused() {
        String appointmentId = aria.bookedAt(aria.at(aria.monday, 13, 0));

        ObjectNode result = call("cancel_appointment", args("appointment_id", appointmentId));

        assertThat(result.path("error").asText()).isEqualTo("NOT_AUTHORIZED");
        // Refused above the application service, so the row was never even read.
        assertThat(statusOf(appointmentId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("reschedule_appointment refuses an id this conversation never proved")
    void rescheduling_an_unauthorised_appointment_is_refused() {
        String appointmentId = aria.bookedAt(aria.at(aria.monday, 13, 0));

        ObjectNode result = call(
                "reschedule_appointment",
                args(
                        "appointment_id", appointmentId,
                        "new_starts_at", aria.at(aria.monday, 14, 0).toString()));

        assertThat(result.path("error").asText()).isEqualTo("NOT_AUTHORIZED");
        assertThat(jdbc.queryForObject(
                        "select to_char(starts_at at time zone 'Asia/Tbilisi', 'HH24:MI') from appointments where id = ?::uuid",
                        String.class,
                        appointmentId))
                .isEqualTo("13:00");
    }

    /** A hallucinated uuid gets the same answer as a real one — the set is the whole of the check. */
    @Test
    @DisplayName("a hallucinated appointment id achieves nothing")
    void an_invented_appointment_id_is_refused_identically() {
        ObjectNode result = call("cancel_appointment", args("appointment_id", UUID.randomUUID().toString()));

        assertThat(result.path("error").asText()).isEqualTo("NOT_AUTHORIZED");
    }

    @Test
    @DisplayName("an appointment proved by lookup can then be cancelled")
    void a_proved_appointment_can_be_cancelled() {
        String appointmentId = aria.bookedAt(aria.at(aria.monday, 15, 0));
        call("lookup_appointment", args(
                "confirmation_code", confirmationCodeOf(appointmentId),
                "phone", BookingScenario.CUSTOMER_PHONE));

        ObjectNode result = call("cancel_appointment", args("appointment_id", appointmentId));

        assertThat(result.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(statusOf(appointmentId)).isEqualTo("CANCELLED");
    }

    /**
     * <strong>The Receptionist is the customer's side.</strong>
     *
     * <p>{@code Actor.ai()} maps to {@code CancelledBy.CUSTOMER}, so a cancellation through the chat
     * panel is recorded as a customer's and — the half that matters — is bound by the Cancellation
     * Window. The alternative would have made the Receptionist a way around a deadline the owner
     * set.
     */
    @Test
    @DisplayName("a cancellation by the Receptionist is recorded as the customer's, not the business's")
    void the_receptionist_cancels_as_a_customer() {
        String appointmentId = aria.bookedAt(aria.at(aria.monday, 15, 0));
        call("lookup_appointment", args(
                "confirmation_code", confirmationCodeOf(appointmentId),
                "phone", BookingScenario.CUSTOMER_PHONE));

        call("cancel_appointment", args("appointment_id", appointmentId));

        assertThat(jdbc.queryForObject(
                        "select cancelled_by from appointments where id = ?::uuid", String.class, appointmentId))
                .isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("an unknown tool name is an answer the model can recover from, not an exception")
    void an_unknown_tool_returns_a_structured_error() {
        ObjectNode result = registry.execute("delete_all_appointments", json.createObjectNode(), context);

        assertThat(result.path("error").asText()).isEqualTo("UNKNOWN_TOOL");
    }

    /**
     * Strict mode validates types, never the inside of a string. A uuid that is not a uuid is the
     * mistake a model actually makes, and it comes back as something it can retry from.
     */
    @Test
    @DisplayName("an argument that is well-typed but meaningless is a validation refusal")
    void a_malformed_uuid_is_reported_to_the_model() {
        ObjectNode result = call("get_service_details", args("service_id", "the-haircut-one"));

        assertThat(result.path("error").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(result.path("message").asText()).contains("service_id");
    }

    // ---------------------------------------------------------------- helpers

    private ObjectNode call(String tool, String argumentsJson) {
        try {
            return registry.execute(tool, json.readTree(argumentsJson), context);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Test wrote arguments that are not JSON: " + argumentsJson, e);
        }
    }

    private String args(String... keyValues) {
        ObjectNode node = json.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.put(keyValues[i], keyValues[i + 1]);
        }
        return node.toString();
    }

    private String bookingArgs(OffsetDateTime startsAt) {
        return args(
                "service_id", aria.serviceId,
                "employee_id", aria.employeeId,
                "starts_at", startsAt.toString(),
                "customer_name", "Ana Tsereteli",
                "customer_phone", BookingScenario.CUSTOMER_PHONE);
    }

    private String confirmationCodeOf(String appointmentId) {
        return jdbc.queryForObject(
                "select confirmation_code from appointments where id = ?::uuid", String.class, appointmentId);
    }

    private String statusOf(String appointmentId) {
        return jdbc.queryForObject("select status from appointments where id = ?::uuid", String.class, appointmentId);
    }

    /** A second, entirely separate tenant — the only honest way to test isolation. */
    private String serviceInAnotherBusiness() {
        AuthTestClient other = new AuthTestClient(rest, port);
        other.register("owner@other.test", BookingScenario.PASSWORD, "Other Salon");
        return BookingScenario.createService(other, "Massage", 30, "40.00", 0, 0);
    }

    private record ForeignBooking(UUID appointmentId, String code, String phone) {}

    private ForeignBooking bookingInAnotherBusiness() {
        AuthTestClient other = new AuthTestClient(rest, port);
        other.register("owner2@other.test", BookingScenario.PASSWORD, "Third Salon");
        other.patch("/business", Map.of("timezone", BookingScenario.TBILISI.getId()));
        String service = BookingScenario.createService(other, "Shave", 30, "20.00", 0, 0);
        String employee = BookingScenario.createEmployee(other, "Someone Else");
        other.put("/employees/" + employee + "/services", Map.of("serviceIds", List.of(service)));
        BookingScenario.setSchedule(other, employee, "09:00", "17:00");

        String phone = "+995555222333";
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("serviceId", service);
        body.put("employeeId", employee);
        body.put("startsAt", aria.at(aria.monday, 10, 0).toString());
        body.put("customerName", "Foreign Customer");
        body.put("customerPhone", phone);
        String id = JsonPath.read(other.post("/appointments", body).getBody(), "$.appointment.id");

        return new ForeignBooking(
                UUID.fromString(id),
                jdbc.queryForObject(
                        "select confirmation_code from appointments where id = ?::uuid", String.class, id),
                phone);
    }
}
