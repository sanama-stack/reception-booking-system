package dev.reception.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import dev.reception.appointments.BookingScenario;
import dev.reception.notifications.ManageTokenService;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the public surface is allowed to say, enforced against what it actually says.
 *
 * <p>Response minimisation is only worth anything if it is checked. Mapping an entity by accident is
 * the easiest way to leak — one added column and a private field is public — so this test drives
 * every public endpoint, collects every key in every response, and fails on any key that was not
 * typed into the allow-list below deliberately
 * (docs/phases/phase-08-public-booking.md, docs/06-security.md §5).
 *
 * <p><strong>A new field here is a decision, not a merge.</strong> Adding one to
 * {@code PublicResponses} turns this test red, and the fix is to add it to {@link #ALLOWED} while
 * looking at it — which is the whole mechanism.
 *
 * <p>Keys alone are not sufficient, because {@code phone} and {@code email} are legitimate on a
 * Business and unacceptable on an Employee or a Customer. So the second half of this class asserts
 * on <em>values</em>: real contact details are planted in the fixture and must appear in no public
 * response anywhere.
 */
class PublicFieldAllowListTest extends IntegrationTest {

    /** Planted in the fixture; these must never come back from a public endpoint. */
    private static final String EMPLOYEE_EMAIL = "nino.private@salon.internal";

    private static final String EMPLOYEE_PHONE = "+995599000111";
    private static final String CUSTOMER_PHONE = "+995555123456";
    private static final String CUSTOMER_EMAIL = "ana.private@example.test";

    /**
     * Every key any public response may contain, and nothing else.
     *
     * <p>Grouped by the record that produces it, so a reader can tell why each one is here.
     */
    private static final Set<String> ALLOWED = Set.of(
            // BusinessProfile — what a booking page publishes about the business itself. Its own
            // phone, email and address are the point of the page, not a leak.
            "name", "description", "addressLine", "city", "country", "phone", "email", "website",
            "timezone", "currency", "cancellationWindowHours", "cancellationPolicy", "aiEnabled", "hours",
            // DayHours
            "dayOfWeek", "opensAt", "closesAt",
            // ServiceSummary and Money
            "id", "durationMinutes", "price", "amount",
            // EmployeeSummary — a name and a job title, per docs/06-security.md §5
            "fullName", "jobTitle",
            // AvailabilityResponses, shared with the internal endpoint unchanged
            "days", "emptyReason", "date", "slots", "startsAt", "endsAt", "employee",
            // BookedAppointment and ManagedAppointment. confirmationSent is one bit — whether a
            // message was enqueued — and never the address it went to (ADR-0007).
            "confirmationCode", "service", "status", "note", "canCancel", "canReschedule", "business",
            "confirmationSent");

    /**
     * Keys that would each be a specific, named failure.
     *
     * <p>Redundant with {@link #ALLOWED} — anything not allowed already fails — and worth keeping
     * anyway: this list says <em>which</em> leak was being guarded against, so a future reader
     * widening the allow-list meets the reason rather than only the rule.
     */
    private static final Set<String> NEVER = Set.of(
            "businessId", "customerId", "employeeId", "serviceId", "customer",
            "slotIntervalMinutes", "minLeadTimeMinutes", "maxAdvanceDays",
            "aiAdditionalInfo", "aiDailyCostCapCents", "bookingUrl",
            "bufferBeforeMinutes", "bufferAfterMinutes", "active",
            "blockedFrom", "blockedTo", "source", "version", "createdAt", "updatedAt",
            "userId", "cancelledBy", "cancellationReason",
            // The resolved confirmation recipient, considered and rejected for BookedAppointment:
            // a returning Customer's stored address must not be readable by whoever holds their
            // phone number (ADR-0007). confirmationSent carries the one bit the screen needs.
            "recipientEmail", "customerEmail", "sentTo");

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ManageTokenService manageTokens;

    private BookingScenario scenario;
    private PublicTestClient stranger;
    private String slug;

    /** Every body every public endpoint produced, so each assertion sweeps all of them at once. */
    private final List<String> responses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        scenario = BookingScenario.open(rest, port, clock);
        stranger = new PublicTestClient(rest, port);
        slug = JsonPath.read(scenario.owner.get("/business").getBody(), "$.slug");

        // Contact details on the Employee and on the Business. The Business's are publishable; the
        // Employee's are not, and having both present is what makes the value assertions meaningful.
        scenario.owner.patch(
                "/employees/" + scenario.employeeId, Map.of("email", EMPLOYEE_EMAIL, "phone", EMPLOYEE_PHONE));
        scenario.owner.patch("/business", Map.of("phone", "+995322000000", "email", "hello@aria.test"));

        collectEveryPublicResponse();
    }

    @Test
    @DisplayName("no public response carries a key outside the allow-list")
    void every_key_is_allow_listed() {
        Set<String> seen = new LinkedHashSet<>();
        responses.forEach(body -> collectKeys(parse(body), seen));

        assertThat(seen).isNotEmpty();
        assertThat(seen)
                .describedAs("keys returned by a public endpoint that PublicResponses did not "
                        + "deliberately declare — add it to ALLOWED only after deciding it is safe")
                .isSubsetOf(ALLOWED);
        assertThat(seen).doesNotContainAnyElementsOf(NEVER);
    }

    @Test
    @DisplayName("an employee's contact details never reach the public surface")
    void staff_contact_details_are_absent() {
        // Present in the database, so their absence below is minimisation rather than empty columns.
        assertThat(jdbc.queryForObject(
                        "select email from employees where id = ?",
                        String.class,
                        UUID.fromString(scenario.employeeId)))
                .isEqualTo(EMPLOYEE_EMAIL);

        assertThat(responses).allSatisfy(body -> assertThat(body)
                .doesNotContain(EMPLOYEE_EMAIL)
                .doesNotContain(EMPLOYEE_PHONE));
    }

    @Test
    @DisplayName("the customer's own details are not echoed back by anything")
    void customer_details_are_not_echoed() {
        // The booking request carried both. A response that reflected them would turn a Confirmation
        // Code into a way to read the phone number and address of whoever booked.
        assertThat(responses).allSatisfy(body -> assertThat(body)
                .doesNotContain(CUSTOMER_PHONE)
                .doesNotContain(CUSTOMER_EMAIL));
    }

    @Test
    @DisplayName("no public controller returns an entity")
    void public_controllers_return_hand_written_records_only() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.reception");

        // The structural half of the same rule. The assertions above test what today's endpoints
        // return; this one fails the moment somebody writes a handler that returns a mapped row,
        // whether or not a test happens to call it.
        //
        // Handler methods only. Inside this package an entity is an ordinary value —
        // PublicAppointmentAuthority hands an Appointment to the controller, which is what it is
        // for. What must never be an entity is the thing that gets serialised, and that is exactly
        // the set of methods carrying a mapping annotation. @GetMapping and @PostMapping are both
        // meta-annotated with @RequestMapping, so one predicate covers every verb including any
        // added later.
        ArchRule rule = ArchRuleDefinition.noMethods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("dev.reception.publicapi..")
                .and()
                .areMetaAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
                .should()
                .haveRawReturnType(
                        com.tngtech.archunit.base.DescribedPredicate.describe(
                                "an @Entity", type -> type.isAnnotatedWith("jakarta.persistence.Entity")))
                .because("public responses are hand-written DTOs; serialising an entity publishes "
                        + "every column it has and every one it grows");

        rule.check(production);
    }

    // ------------------------------------------------------------------ driving

    /** Calls every endpoint in the package, so the assertions above see the whole surface. */
    private void collectEveryPublicResponse() {
        record Booked(String id, String code) {}

        responses.add(stranger.get("/public/businesses/" + slug).getBody());
        responses.add(stranger.get("/public/businesses/" + slug + "/services").getBody());
        responses.add(stranger.get("/public/businesses/" + slug + "/employees").getBody());
        responses.add(stranger
                .get("/public/businesses/" + slug + "/employees?serviceId=" + scenario.serviceId)
                .getBody());
        responses.add(stranger
                .get("/public/businesses/%s/availability?serviceId=%s&from=%s&to=%s"
                        .formatted(slug, scenario.serviceId, scenario.monday, scenario.monday))
                .getBody());

        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", scenario.serviceId);
        body.put("employeeId", scenario.employeeId);
        body.put("startsAt", scenario.at(scenario.monday, 10, 0).toString());
        body.put("customer", Map.of("fullName", "Ana Tsereteli", "phone", CUSTOMER_PHONE, "email", CUSTOMER_EMAIL));
        body.put("note", "First visit");
        String created = stranger.post("/public/businesses/" + slug + "/appointments", body).getBody();
        responses.add(created);

        Booked booked = new Booked(JsonPath.read(created, "$.id"), JsonPath.read(created, "$.confirmationCode"));
        String token = manageTokens.issue(
                UUID.fromString(booked.id()),
                jdbc.queryForObject(
                        "select ends_at from appointments where id = ?",
                        Instant.class,
                        UUID.fromString(booked.id())));

        responses.add(stranger
                .post(
                        "/public/appointments/lookup",
                        Map.of("confirmationCode", booked.code(), "phone", CUSTOMER_PHONE))
                .getBody());
        responses.add(stranger.get("/public/appointments/manage?token=" + token).getBody());
        responses.add(stranger
                .get("/public/appointments/manage/availability?token=%s&from=%s&to=%s"
                        .formatted(token, scenario.monday, scenario.monday))
                .getBody());
        responses.add(stranger
                .post(
                        "/public/appointments/" + booked.id() + "/reschedule",
                        Map.of(
                                "authority", Map.of("manageToken", token),
                                "startsAt", scenario.at(scenario.monday, 14, 0).toString()))
                .getBody());
        responses.add(stranger
                .post(
                        "/public/appointments/" + booked.id() + "/cancel",
                        Map.of("authority", Map.of("manageToken", token), "reason", "Changed my mind"))
                .getBody());

        // Eleven bodies, one per mapped public endpoint plus the two variants of the employee
        // read. Pinned so that adding an endpoint without adding it here fails loudly rather than
        // leaving the new surface silently unswept by the assertions below.
        assertThat(responses).doesNotContainNull().hasSize(11);
    }

    private static void collectKeys(JsonNode node, Set<String> into) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                into.add(entry.getKey());
                collectKeys(entry.getValue(), into);
            });
        } else if (node.isArray()) {
            node.forEach(element -> collectKeys(element, into));
        }
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception malformed) {
            throw new IllegalStateException("A public endpoint returned something that is not JSON: " + body, malformed);
        }
    }
}
