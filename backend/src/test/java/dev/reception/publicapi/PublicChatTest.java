package dev.reception.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.ai.support.ScriptedChatModel;
import dev.reception.appointments.BookingScenario;
import dev.reception.notifications.ManageTokenService;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Receptionist as a stranger reaches it, over HTTP, with no session anywhere.
 *
 * <p>{@link ConversationLoopTest} covers the loop; this covers the door. The two questions here are
 * the ones a controller can get wrong on its own: does the slug settle the tenant before anything
 * runs, and does the response say what a chat panel needs without saying anything else.
 *
 * <p>Also the isolation probes rule 5 of docs/09-phase-plan.md requires of every new tenant-scoped
 * endpoint. Both chat endpoints are probed, with real ids from a real second business.
 */
class PublicChatTest extends IntegrationTest {

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
    private ScriptedChatModel model;

    @Autowired
    private ManageTokenService manageTokens;

    private PublicTestClient stranger;
    private BookingScenario aria;
    private String slug;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        model.reset();
        stranger = new PublicTestClient(rest, port);
        aria = BookingScenario.open(rest, port, clock);
        slug = JsonPath.read(aria.owner.get("/business").getBody(), "$.slug");
    }

    @Test
    @DisplayName("a stranger opens a conversation and gets a session token back")
    void a_conversation_can_be_opened_without_signing_in() {
        ResponseEntity<String> response = startSession();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((String) JsonPath.read(response.getBody(), "$.sessionToken")).isNotBlank();
        assertThat((String) JsonPath.read(response.getBody(), "$.conversationId")).isNotBlank();
    }

    /**
     * The token is a capability. What the database keeps is a hash of it, so a read of
     * {@code ai_conversations} does not hand out the ability to continue somebody's conversation.
     */
    @Test
    @DisplayName("the session token is never stored, only its hash")
    void the_token_itself_is_not_persisted() {
        String token = tokenFrom(startSession());

        String stored = jdbc.queryForObject("select session_token_hash from ai_conversations", String.class);
        assertThat(stored).isNotEqualTo(token).hasSize(64);
    }

    @Test
    @DisplayName("a turn comes back with the reply and nothing the panel did not ask for")
    void a_turn_returns_a_reply() {
        model.willSay("We're open nine to five.");
        String token = tokenFrom(startSession());

        ResponseEntity<String> response = sendMessage(token, "when are you open?");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) JsonPath.read(response.getBody(), "$.reply")).isEqualTo("We're open nine to five.");
        assertThat((String) JsonPath.read(response.getBody(), "$.conversationStatus")).isEqualTo("ACTIVE");
        // Counted by the server, because the ceiling counts tool rows the panel never sees.
        assertThat((Integer) JsonPath.read(response.getBody(), "$.messagesRemaining")).isPositive();
    }

    /**
     * The confirmation card's shape. In this package's vocabulary, not the tool surface's — the
     * provenance is what makes it a hallucination control, and the key casing is what makes it a
     * public response.
     */
    @Test
    @DisplayName("a booking comes back as a camelCase card, sourced from the tool result")
    void a_booking_is_projected_into_the_public_vocabulary() {
        model.willCall("create_appointment", bookingArguments()).willSay("You're booked.");
        String token = tokenFrom(startSession());

        ResponseEntity<String> response = sendMessage(token, "book monday at ten");

        assertThat((String) JsonPath.read(response.getBody(), "$.appointmentCreated.confirmationCode"))
                .isNotBlank();
        assertThat((String) JsonPath.read(response.getBody(), "$.appointmentCreated.service"))
                .isEqualTo("Haircut");
        assertThat((String) JsonPath.read(response.getBody(), "$.appointmentCreated.price"))
                .isEqualTo("60.00");
        // No address was given, so nothing was enqueued — and the panel is told, not left to guess
        // (ADR-0007).
        assertThat((Boolean) JsonPath.read(response.getBody(), "$.appointmentCreated.confirmationSent"))
                .isFalse();
        // The snake_case the tool speaks must not have reached the wire.
        assertThat(response.getBody()).doesNotContain("confirmation_code").doesNotContain("service_name");
    }

    @Test
    @DisplayName("a conversation resumes across requests from the token alone")
    void a_conversation_is_resumed_by_its_token() {
        model.willSay("Hello.").willSay("Still here.");
        String token = tokenFrom(startSession());

        sendMessage(token, "hi");
        ResponseEntity<String> second = sendMessage(token, "you there?");

        assertThat((String) JsonPath.read(second.getBody(), "$.reply")).isEqualTo("Still here.");
        assertThat(jdbc.queryForObject("select count(*) from ai_conversations", Long.class)).isEqualTo(1L);
    }

    /**
     * A customer who arrived on a Manage Link starts the conversation already able to change that
     * one appointment — which is the difference between "move my appointment" working and the
     * Receptionist asking for a Confirmation Code the customer would have to go and find.
     */
    @Test
    @DisplayName("a Manage Link seeds the conversation's authority with the appointment it authorises")
    void a_manage_link_seeds_the_authority_set() {
        String appointmentId = aria.bookedAt(aria.at(aria.monday, 10, 0));
        Instant endsAt = aria.at(aria.monday, 11, 0).toInstant();
        String manageToken = manageTokens.issue(java.util.UUID.fromString(appointmentId), endsAt);

        Map<String, Object> body = new HashMap<>();
        body.put("manageToken", manageToken);
        stranger.post("/public/businesses/" + slug + "/chat/session", body);

        assertThat(jdbc.queryForList(
                        "select unnest(authorized_appointment_ids)::text from ai_conversations", String.class))
                .containsExactly(appointmentId);
    }

    /**
     * <strong>The isolation probe for the seed.</strong> A Manage Link is valid for the appointment
     * it names in whatever business owns it — presented on the wrong slug, it must seed nothing, or
     * one business's chat panel becomes a way to act on another's appointment.
     */
    @Test
    @DisplayName("a Manage Link for another business's appointment seeds nothing")
    void a_foreign_manage_link_grants_no_authority() {
        AuthTestClient other = new AuthTestClient(rest, port);
        other.register("owner@other.test", BookingScenario.PASSWORD, "Other Salon");
        other.patch("/business", Map.of("timezone", BookingScenario.TBILISI.getId()));
        String service = BookingScenario.createService(other, "Shave", 30, "20.00", 0, 0);
        String employee = BookingScenario.createEmployee(other, "Someone Else");
        other.put("/employees/" + employee + "/services", Map.of("serviceIds", List.of(service)));
        BookingScenario.setSchedule(other, employee, "09:00", "17:00");

        Map<String, Object> booking = new HashMap<>();
        booking.put("serviceId", service);
        booking.put("employeeId", employee);
        booking.put("startsAt", aria.at(aria.monday, 10, 0).toString());
        booking.put("customerName", "Foreign Customer");
        booking.put("customerPhone", "+995555222333");
        String foreignId = JsonPath.read(other.post("/appointments", booking).getBody(), "$.appointment.id");

        String foreignToken = manageTokens.issue(
                java.util.UUID.fromString(foreignId), aria.at(aria.monday, 10, 30).toInstant());

        Map<String, Object> body = new HashMap<>();
        body.put("manageToken", foreignToken);
        ResponseEntity<String> response =
                stranger.post("/public/businesses/" + slug + "/chat/session", body);

        // The conversation opens — a stale or foreign link is not a reason to refuse a chat — but it
        // opens with nothing authorised.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForList(
                        "select unnest(authorized_appointment_ids)::text from ai_conversations", String.class))
                .isEmpty();
    }

    /**
     * <strong>The isolation probe for the turn endpoint.</strong> A session token is issued under
     * one slug; presented under another, it must resolve to nothing.
     */
    @Test
    @DisplayName("a session token from one business cannot be used on another's chat endpoint")
    void a_session_token_does_not_cross_tenants() {
        model.willSay("Hello.");
        String token = tokenFrom(startSession());

        AuthTestClient other = new AuthTestClient(rest, port);
        other.register("owner2@other.test", BookingScenario.PASSWORD, "Third Salon");
        String otherSlug = JsonPath.read(other.get("/business").getBody(), "$.slug");

        Map<String, Object> body = new HashMap<>();
        body.put("sessionToken", token);
        body.put("message", "hello");
        ResponseEntity<String> response =
                stranger.post("/public/businesses/" + otherSlug + "/chat", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(model.callCount()).isZero();
    }

    @Test
    @DisplayName("an unknown slug is a 404 before any conversation is created")
    void an_unknown_slug_creates_nothing() {
        ResponseEntity<String> response =
                stranger.post("/public/businesses/no-such-salon/chat/session", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from ai_conversations", Long.class)).isZero();
    }

    @Test
    @DisplayName("a message longer than the cap is refused by validation, before any model call")
    void an_oversized_message_is_refused() {
        String token = tokenFrom(startSession());

        Map<String, Object> body = new HashMap<>();
        body.put("sessionToken", token);
        body.put("message", "x".repeat(2001));
        ResponseEntity<String> response =
                stranger.post("/public/businesses/" + slug + "/chat", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(model.callCount()).isZero();
    }

    /** The owner's transcripts are behind authentication; a stranger cannot read them. */
    @Test
    @DisplayName("a stranger cannot read transcripts")
    void transcripts_are_not_public() {
        assertThat(stranger.get("/conversations").getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<String> startSession() {
        return stranger.post("/public/businesses/" + slug + "/chat/session", Map.of());
    }

    private ResponseEntity<String> sendMessage(String token, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("sessionToken", token);
        body.put("message", message);
        return stranger.post("/public/businesses/" + slug + "/chat", body);
    }

    private static String tokenFrom(ResponseEntity<String> response) {
        return JsonPath.read(response.getBody(), "$.sessionToken");
    }

    private String bookingArguments() {
        return """
                {"service_id":"%s","employee_id":"%s","starts_at":"%s",\
                "customer_name":"Ana Tsereteli","customer_phone":"%s",\
                "customer_email":null,"note":null}"""
                .formatted(
                        aria.serviceId,
                        aria.employeeId,
                        aria.at(aria.monday, 10, 0),
                        BookingScenario.CUSTOMER_PHONE);
    }
}
