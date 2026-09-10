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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * <p><strong>Position is part of the rule.</strong> {@code phone} and {@code email} are legitimate
 * on a Business and unacceptable on an Employee or a Customer, so the allow-list holds <em>paths</em>
 * rather than bare key names — {@code businessProfile.phone} is listed and {@code employees[].phone}
 * is not, and neither implies the other. Until issue #10 this was one flat set of names, which meant
 * a key admitted for one record was admitted on every record; that is precisely what let a
 * {@code appointmentCreated} of the wrong shape through, in the wild, the day before the issue was
 * filed.
 *
 * <p>Each path is qualified by the response that produced it, so the two grids, the four endpoints
 * returning a {@link PublicResponses.ManagedAppointment} and the two doors returning a
 * {@link PublicResponses.BookedAppointment} are each checked where they actually appear. The shared
 * shapes are written once as helpers below rather than repeated, because they are genuinely one
 * record and not merely alike — {@code PublicChatTest.one_card_shape_serves_both_doors} is what
 * holds that true.
 *
 * <p>Paths are not the whole story either, so the second half of this class still asserts on
 * <em>values</em>: real contact details are planted in the fixture and must appear in no public
 * response anywhere.
 */
class PublicFieldAllowListTest extends IntegrationTest {

    /** Planted in the fixture; these must never come back from a public endpoint. */
    private static final String EMPLOYEE_EMAIL = "nino.private@salon.internal";

    private static final String EMPLOYEE_PHONE = "+995599000111";
    private static final String CUSTOMER_PHONE = "+995555123456";
    private static final String CUSTOMER_EMAIL = "ana.private@example.test";

    /**
     * Every path any public response may contain, and nothing else.
     *
     * <p>Qualified by the response it comes from, so a key is admitted where it belongs and nowhere
     * else. A root array contributes {@code services[].id}; a nested object contributes
     * {@code manage.business.phone}. Container keys are listed too ({@code businessProfile.hours}),
     * so a new array or object is a decision as much as a new leaf is.
     */
    private static final Set<String> ALLOWED = union(List.of(
            // BusinessProfile — what a booking page publishes about the business itself. Its own
            // phone, email and address are the point of the page, not a leak. They are listed HERE
            // and nowhere else, which is the whole change: the same three keys on an employee, a
            // customer or an appointment are now failures rather than the same allowance reused.
            at(
                    "businessProfile",
                    "name",
                    "description",
                    "addressLine",
                    "city",
                    "country",
                    "phone",
                    "email",
                    "website",
                    "timezone",
                    "currency",
                    "cancellationWindowHours",
                    "cancellationPolicy",
                    "aiEnabled",
                    "hours",
                    "hours[].dayOfWeek",
                    "hours[].opensAt",
                    "hours[].closesAt"),
            // ServiceSummary and Money.
            at("services[]", "id", "name", "description", "durationMinutes", "price", "price.amount", "price.currency"),
            // EmployeeSummary — a name and a job title, per docs/06-security.md §5. Swept twice,
            // with and without the serviceId filter; both bodies are the same record.
            at("employees[]", "id", "fullName", "jobTitle"),
            // AvailabilityResponses, shared with the internal endpoint unchanged and served on two
            // public routes — the booking grid and the reschedule grid behind a Manage Link.
            availability("availability"),
            availability("manageAvailability"),
            // BookedAppointment, from both doors. The Classic Flow returns it; the Receptionist
            // returns the same record nested under appointmentCreated, which is why the second call
            // here reads as a path rather than a copied list.
            bookedAppointment("booked"),
            bookedAppointment("chatReply.appointmentCreated"),
            // ManagedAppointment, from all four endpoints that return one.
            managedAppointment("lookup"),
            managedAppointment("manage"),
            managedAppointment("reschedule"),
            managedAppointment("cancel"),
            // PublicChatResponses (phase 09). sessionToken is a capability and is returned exactly
            // once, by design — the row keeps only its SHA-256.
            at("chatSession", "conversationId", "sessionToken"),
            at("chatReply", "reply", "conversationStatus", "messagesRemaining", "appointmentCreated")));

    /**
     * {@link PublicResponses.BookedAppointment}, wherever it appears.
     *
     * <p>Written once because it <em>is</em> one record in both places. confirmationSent is one bit
     * — whether a message was enqueued — and never the address it went to (ADR-0007).
     */
    private static Set<String> bookedAppointment(String source) {
        return at(
                source,
                "id",
                "confirmationCode",
                "startsAt",
                "endsAt",
                "timezone",
                "service",
                "service.name",
                "service.durationMinutes",
                "employee",
                "employee.fullName",
                "price",
                "price.amount",
                "price.currency",
                "confirmationSent");
    }

    /**
     * {@link PublicResponses.ManagedAppointment}, returned by four endpoints.
     *
     * <p>emailOnFile is the same one bit on the manage surface, named for the fact rather than an
     * outcome because two of the four send nothing (ADR-0008). {@code business.phone} and
     * {@code business.email} are the business's own, published so a refusal past the Cancellation
     * Window can say who to call.
     */
    private static Set<String> managedAppointment(String source) {
        return at(
                source,
                "id",
                "confirmationCode",
                "status",
                "startsAt",
                "endsAt",
                "timezone",
                "service",
                "service.name",
                "service.durationMinutes",
                "employee",
                "employee.fullName",
                "price",
                "price.amount",
                "price.currency",
                "note",
                "canCancel",
                "canReschedule",
                "emailOnFile",
                "business",
                "business.name",
                "business.phone",
                "business.email",
                "business.timezone",
                "business.cancellationPolicy");
    }

    /** {@link AvailabilityResponses.Availability}, served on two public routes. */
    private static Set<String> availability(String source) {
        return at(
                source,
                "timezone",
                "emptyReason",
                "days",
                "days[].date",
                "days[].slots",
                "days[].slots[].startsAt",
                "days[].slots[].endsAt",
                "days[].slots[].employee",
                "days[].slots[].employee.id",
                "days[].slots[].employee.fullName");
    }

    /** Qualifies each path with the response it may appear in. */
    private static Set<String> at(String source, String... paths) {
        return Arrays.stream(paths).map(path -> source + "." + path).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> union(List<Set<String>> sets) {
        return sets.stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Key names that would each be a specific, named failure.
     *
     * <p>Redundant with {@link #ALLOWED} — anything not allowed already fails — and worth keeping
     * anyway: this list says <em>which</em> leak was being guarded against, so a future reader
     * widening the allow-list meets the reason rather than only the rule.
     *
     * <p>Matched against a path's <em>last segment</em> rather than the whole path, deliberately.
     * {@link #ALLOWED} is about where a key is acceptable; this is about keys that are acceptable
     * nowhere, and {@code businessId} nested three levels down is the same leak as
     * {@code businessId} at the top.
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
            // phone number (ADR-0007). confirmationSent carries the one bit the screen needs, and
            // emailOnFile the same bit for ManagedAppointment (ADR-0008) — a boolean either way,
            // never a value.
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

    @Autowired
    private dev.reception.ai.support.ScriptedChatModel chatModel;

    // Qualified, because Actuator contributes a second RequestMappingHandlerMapping and the
    // application's own is the one that knows about controllers.
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMapping;

    private BookingScenario scenario;
    private PublicTestClient stranger;
    private String slug;

    /**
     * One public response, and the name this test knows it by.
     *
     * @param source the {@link #ALLOWED} qualifier — the label every path from this body carries
     */
    private record Body(String source, String json) {}

    /** Every body every public endpoint produced, so each assertion sweeps all of them at once. */
    private final List<Body> responses = new ArrayList<>();

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
    @DisplayName("no public response carries a path outside the allow-list")
    void every_path_is_allow_listed() {
        Set<String> seen = new LinkedHashSet<>();
        responses.forEach(body -> collectPaths(parse(body.json()), body.source(), seen));

        assertThat(seen).isNotEmpty();
        assertThat(seen)
                .describedAs("paths returned by a public endpoint that PublicResponses did not "
                        + "deliberately declare — add one to ALLOWED only after deciding it is safe "
                        + "*on that response*, because a key allowed elsewhere is not allowed here")
                .isSubsetOf(ALLOWED);

        assertThat(seen.stream().map(PublicFieldAllowListTest::leaf).collect(Collectors.toSet()))
                .describedAs("key names that are a leak at any depth, on any response")
                .doesNotContainAnyElementsOf(NEVER);
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

        assertThat(responses).allSatisfy(body -> assertThat(body.json())
                .doesNotContain(EMPLOYEE_EMAIL)
                .doesNotContain(EMPLOYEE_PHONE));
    }

    @Test
    @DisplayName("the customer's own details are not echoed back by anything")
    void customer_details_are_not_echoed() {
        // The booking request carried both. A response that reflected them would turn a Confirmation
        // Code into a way to read the phone number and address of whoever booked.
        assertThat(responses).allSatisfy(body -> assertThat(body.json())
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
    /**
     * <strong>That the sweep is complete.</strong>
     *
     * <p>{@link #collectEveryPublicResponse()} drives a hand-written list of endpoints, and until
     * phase 09 the only thing guarding that list was a pinned count — which catches somebody adding
     * a response without updating the number, and cannot catch the failure that actually matters: a
     * new controller nobody swept. Phase 09 added two endpoints and the count noticed neither.
     *
     * <p>So the mapped patterns are read out of Spring rather than trusted. A new public endpoint now
     * fails here, naming itself, and the fix is to add it above and to look at what it returns.
     */
    @Test
    @DisplayName("every mapped public endpoint is one this test actually drives")
    void every_mapped_public_endpoint_is_swept() {
        Set<String> mapped = new java.util.TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> info.getPathPatternsCondition()
                .getPatternValues()
                .stream()
                .filter(pattern -> pattern.startsWith("/public/"))
                .forEach(mapped::add));

        assertThat(mapped)
                .as("A public endpoint that this test does not drive is a public response nobody has "
                        + "checked. Add it to collectEveryPublicResponse(), then add it here.")
                .containsExactlyInAnyOrder(
                        "/public/businesses/{slug}",
                        "/public/businesses/{slug}/services",
                        "/public/businesses/{slug}/employees",
                        "/public/businesses/{slug}/availability",
                        "/public/businesses/{slug}/appointments",
                        "/public/businesses/{slug}/chat/session",
                        "/public/businesses/{slug}/chat",
                        "/public/appointments/lookup",
                        "/public/appointments/manage",
                        "/public/appointments/manage/availability",
                        "/public/appointments/{id}/reschedule",
                        "/public/appointments/{id}/cancel");
    }

    private void collectEveryPublicResponse() {
        record Booked(String id, String code) {}

        add("businessProfile", stranger.get("/public/businesses/" + slug).getBody());
        add("services", stranger.get("/public/businesses/" + slug + "/services").getBody());
        add("employees", stranger.get("/public/businesses/" + slug + "/employees").getBody());
        add(
                "employees",
                stranger
                        .get("/public/businesses/" + slug + "/employees?serviceId=" + scenario.serviceId)
                        .getBody());
        add(
                "availability",
                stranger
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
        add("booked", created);

        Booked booked = new Booked(JsonPath.read(created, "$.id"), JsonPath.read(created, "$.confirmationCode"));
        String token = manageTokens.issue(
                UUID.fromString(booked.id()),
                jdbc.queryForObject(
                        "select ends_at from appointments where id = ?",
                        Instant.class,
                        UUID.fromString(booked.id())));

        add(
                "lookup",
                stranger
                        .post(
                                "/public/appointments/lookup",
                                Map.of("confirmationCode", booked.code(), "phone", CUSTOMER_PHONE))
                        .getBody());
        add("manage", stranger.get("/public/appointments/manage?token=" + token).getBody());
        add(
                "manageAvailability",
                stranger
                        .get("/public/appointments/manage/availability?token=%s&from=%s&to=%s"
                                .formatted(token, scenario.monday, scenario.monday))
                        .getBody());
        add(
                "reschedule",
                stranger
                        .post(
                                "/public/appointments/" + booked.id() + "/reschedule",
                                Map.of(
                                        "authority", Map.of("manageToken", token),
                                        "startsAt", scenario.at(scenario.monday, 14, 0).toString()))
                        .getBody());
        add(
                "cancel",
                stranger
                        .post(
                                "/public/appointments/" + booked.id() + "/cancel",
                                Map.of("authority", Map.of("manageToken", token), "reason", "Changed my mind"))
                        .getBody());

        // The Receptionist (phase 09). Scripted to book, so the confirmation card is in the swept
        // set — it is the one public response assembled from a tool result rather than from an
        // entity, and therefore the one most able to carry a key nobody chose.
        chatModel.willCall(
                        "create_appointment",
                        """
                        {"service_id":"%s","employee_id":"%s","starts_at":"%s",\
                        "customer_name":"Ana Tsereteli","customer_phone":"%s",\
                        "customer_email":null,"note":null}"""
                                .formatted(
                                        scenario.serviceId,
                                        scenario.employeeId,
                                        scenario.at(scenario.monday, 15, 0),
                                        CUSTOMER_PHONE))
                .willSay("You're booked in.");

        String session = stranger
                .post("/public/businesses/" + slug + "/chat/session", Map.of())
                .getBody();
        add("chatSession", session);
        add(
                "chatReply",
                stranger
                        .post(
                                "/public/businesses/" + slug + "/chat",
                                Map.of(
                                        "sessionToken", (String) JsonPath.read(session, "$.sessionToken"),
                                        "message", "book me monday at three"))
                        .getBody());

        // Thirteen bodies: one per mapped public endpoint, plus the two variants of the employee
        // read. The count alone cannot notice a new controller — see
        // every_mapped_public_endpoint_is_swept, which can.
        assertThat(responses).doesNotContainNull().hasSize(13);
        assertThat(responses).allSatisfy(swept -> assertThat(swept.json()).isNotNull());
    }

    /** Records one body under the {@link #ALLOWED} qualifier its paths will carry. */
    private void add(String source, String json) {
        responses.add(new Body(source, json));
    }

    /**
     * Every path in one response, qualified by where it came from.
     *
     * <p>Array position is erased to {@code []} rather than kept as an index: the check is about
     * shape, and a second element must not be able to introduce a field the first did not.
     */
    private static void collectPaths(JsonNode node, String prefix, Set<String> into) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String path = prefix + "." + entry.getKey();
                into.add(path);
                collectPaths(entry.getValue(), path, into);
            });
        } else if (node.isArray()) {
            node.forEach(element -> collectPaths(element, prefix + "[]", into));
        }
    }

    /** The key name at the end of a path, which is what {@link #NEVER} is about. */
    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception malformed) {
            throw new IllegalStateException("A public endpoint returned something that is not JSON: " + body, malformed);
        }
    }
}
