package dev.reception.appointments;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * A business that can take a booking, built over HTTP the way an owner would build it.
 *
 * <p>Seven test classes need the same six steps — register, set a zone, add a service, add an
 * employee, assign one to the other, give them a week — and none of them is what any of the seven
 * is testing. Written once here so a failure in the setup is one failure rather than seven, and so
 * the tests themselves start at the line that matters.
 *
 * <p>Public, and in this package rather than in {@code support}, because phase 07's outbox tests
 * need exactly this business from {@code dev.reception.notifications}. Moving it would rewrite six
 * imports to make one reader's life marginally tidier.
 *
 * <p><strong>Configured through the API, never through repositories.</strong> A fixture that wrote
 * rows directly could build a business the application would refuse to build, and the tests would
 * then be passing against a state no user can reach.
 */
public final class BookingScenario {

    public static final String PASSWORD = "a-long-enough-password";

    /** A real zone with a real offset, so a conversion that was never applied is visible. */
    public static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");

    /** Parseable without a country on the Business, which registration leaves unset. */
    public static final String CUSTOMER_PHONE = "+995555123456";

    /**
     * The owner's login, named rather than inlined because a test asserts it never reaches a public
     * response. It is not the Business's contact email — that one is publishable and is the point of
     * the booking page; this one is an account identity and a credential-stuffing target.
     */
    public static final String OWNER_EMAIL = "nino@aria.test";

    public final AuthTestClient owner;
    public final String serviceId;
    public final String employeeId;

    /** A Monday at least a week out: inside the horizon, clear of the minimum lead time. */
    public final LocalDate monday;

    /**
     * A Monday that has definitely already happened.
     *
     * <p>Derived separately rather than as {@code monday.minusDays(7)}: {@code monday} is "a week
     * from today, rounded forward to Monday", so subtracting a week lands anywhere from yesterday to
     * six days out depending on which weekday the suite runs on — and a test asserting
     * {@code BOOKING_IN_PAST} would then pass on Tuesdays and fail on Wednesdays.
     */
    public final LocalDate pastMonday;

    private BookingScenario(
            AuthTestClient owner, String serviceId, String employeeId, LocalDate monday, LocalDate pastMonday) {
        this.owner = owner;
        this.serviceId = serviceId;
        this.employeeId = employeeId;
        this.monday = monday;
        this.pastMonday = pastMonday;
    }

    /** One employee, one sixty-minute service at 60.00, Monday to Friday 09:00–17:00. */
    public static BookingScenario open(TestRestTemplate rest, int port, Clock clock) {
        AuthTestClient owner = new AuthTestClient(rest, port);
        owner.register(OWNER_EMAIL, PASSWORD, "Salon Aria");
        owner.patch("/business", Map.of("timezone", TBILISI.getId()));

        String service = createService(owner, "Haircut", 60, "60.00", 0, 0);
        String employee = createEmployee(owner, "Nino Beridze");
        owner.put("/employees/" + employee + "/services", Map.of("serviceIds", List.of(service)));
        setSchedule(owner, employee, "09:00", "17:00");

        LocalDate today = LocalDate.now(clock.withZone(TBILISI));
        LocalDate monday = today.plusDays(7).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LocalDate pastMonday = today.minusDays(7).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        return new BookingScenario(owner, service, employee, monday, pastMonday);
    }

    public static String createService(
            AuthTestClient client, String name, int durationMinutes, String price, int bufferBefore, int bufferAfter) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", durationMinutes);
        body.put("price", price);
        body.put("bufferBeforeMinutes", bufferBefore);
        body.put("bufferAfterMinutes", bufferAfter);
        return JsonPath.read(client.post("/services", body).getBody(), "$.id");
    }

    public static String createEmployee(AuthTestClient client, String fullName) {
        return JsonPath.read(client.post("/employees", Map.of("fullName", fullName)).getBody(), "$.id");
    }

    public static void setSchedule(AuthTestClient client, String employeeId, String from, String to) {
        List<Map<String, Object>> week = List.of(1, 2, 3, 4, 5).stream()
                .map(day -> Map.<String, Object>of("dayOfWeek", day, "startsAt", from, "endsAt", to))
                .toList();
        client.put("/employees/" + employeeId + "/schedule", Map.of("schedule", week));
    }

    /** A start time on the Business's clock, which is the only clock a booking is expressed in. */
    public OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(TBILISI).toOffsetDateTime();
    }

    /**
     * The same instant as the API writes it.
     *
     * <p>Spelled out with seconds because {@code OffsetDateTime.toString()} omits them when they are
     * zero and Jackson does not — every start time in this system lands on a minute boundary, so
     * comparing the two forms directly fails on every single case.
     */
    public static String wireTime(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute)
                .atZone(TBILISI)
                .toOffsetDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }

    public ResponseEntity<String> book(OffsetDateTime startsAt) {
        return book(startsAt, employeeId, "Ana Tsereteli", CUSTOMER_PHONE);
    }

    public ResponseEntity<String> book(OffsetDateTime startsAt, String employee, String customerName, String phone) {
        return book(startsAt, employee, customerName, phone, null);
    }

    /** With an email, which is what decides whether the booking enqueues anything at all. */
    public ResponseEntity<String> book(
            OffsetDateTime startsAt, String employee, String customerName, String phone, String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", serviceId);
        body.put("employeeId", employee);
        body.put("startsAt", startsAt.toString());
        body.put("customerName", customerName);
        body.put("customerPhone", phone);
        if (email != null) {
            body.put("customerEmail", email);
        }
        return owner.post("/appointments", body);
    }

    /** The id of an appointment booked at this time, failing loudly if the booking was refused. */
    public String bookedAt(OffsetDateTime startsAt) {
        ResponseEntity<String> response = book(startsAt);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Fixture could not book: " + response.getBody());
        }
        return JsonPath.read(response.getBody(), "$.appointment.id");
    }

    public static String codeOf(ResponseEntity<String> response) {
        return JsonPath.read(response.getBody(), "$.code");
    }
}
