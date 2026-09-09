package dev.reception.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The whole phase, end to end, against a real SMTP server.
 *
 * <p>Everything else in this package replaces {@link EmailSender} with something that cannot fail in
 * an interesting way. This one does not: a booking goes in over HTTP, the dispatcher runs, and the
 * message is read back out of Mailpit's own API — through {@code JavaMailSender}, a real socket, a
 * real MIME encoder and a real mailbox. It is the only test here that would notice a malformed
 * multipart, a broken {@code From} address or a subject that fails to encode.
 *
 * <p>This is the Definition of Done line from {@code docs/phases/phase-07-notifications.md}: <em>a
 * booking produces a confirmation email in Mailpit containing the code and a valid Manage Link</em>.
 */
class NotificationDeliveryTest extends IntegrationTest {

    private static final String CUSTOMER_EMAIL = "ana@example.test";

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private ManageTokenService manageTokens;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private BookingScenario aria;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.clean();
        // Mailpit is static and shared by the whole suite, so the mailbox is emptied rather than
        // assumed empty. Without this the assertions below would read somebody else's message.
        mailpit("DELETE", "/api/v1/messages");
        aria = BookingScenario.open(rest, port, clock);
    }

    @Test
    @DisplayName("a booking produces a real email carrying the code and a working Manage Link")
    void the_confirmation_arrives_in_mailpit() throws Exception {
        OffsetDateTime startsAt = aria.at(aria.monday, 10, 0);
        ResponseEntity<String> booked = aria.book(
                startsAt, aria.employeeId, "Ana Tsereteli", BookingScenario.CUSTOMER_PHONE, CUSTOMER_EMAIL);
        String appointmentId = JsonPath.read(booked.getBody(), "$.appointment.id");
        String code = JsonPath.read(booked.getBody(), "$.appointment.confirmationCode");

        // Only the confirmation is due; the reminder is a week out and must stay behind.
        assertThat(dispatcher.dispatchDueBatch()).isEqualTo(1);

        String inbox = mailpit("GET", "/api/v1/messages");
        assertThat((List<?>) JsonPath.read(inbox, "$.messages")).hasSize(1);
        assertThat((String) JsonPath.read(inbox, "$.messages[0].To[0].Address")).isEqualTo(CUSTOMER_EMAIL);
        assertThat((String) JsonPath.read(inbox, "$.messages[0].Subject"))
                .isEqualTo("Your appointment at Salon Aria is confirmed");

        String message = mailpit("GET", "/api/v1/message/" + JsonPath.read(inbox, "$.messages[0].ID"));
        String text = JsonPath.read(message, "$.Text");
        String html = JsonPath.read(message, "$.HTML");

        // Both alternatives are present and both carry the code — a client that renders only one of
        // them still shows the customer what they need.
        assertThat(text).contains(code).contains("Salon Aria").contains("Nino Beridze");
        assertThat(html).contains(code).contains("<!doctype html>");

        // The link is not merely present; it opens this appointment and no other.
        assertThat(manageTokens.verify(tokenFrom(text))).contains(UUID.fromString(appointmentId));
    }

    @Test
    @DisplayName("cancelling sends the cancellation and never the superseded reminder")
    void a_superseded_row_is_not_delivered() throws Exception {
        String id = JsonPath.read(
                aria.book(
                                aria.at(aria.monday, 11, 0),
                                aria.employeeId,
                                "Ana Tsereteli",
                                BookingScenario.CUSTOMER_PHONE,
                                CUSTOMER_EMAIL)
                        .getBody(),
                "$.appointment.id");
        dispatcher.dispatchDueBatch();
        mailpit("DELETE", "/api/v1/messages");

        aria.owner.post("/appointments/" + id + "/cancel", Map.of("reason", "Closed for the day"));
        // The reminder is now CANCELLED. Bringing its due time forward proves the poller ignores it
        // because of its status and not merely because it was not yet due.
        jdbc.update("update notifications set scheduled_for = now() - interval '1 hour' where appointment_id = ?::uuid", id);

        assertThat(dispatcher.dispatchDueBatch()).isEqualTo(1);

        String inbox = mailpit("GET", "/api/v1/messages");
        assertThat((List<?>) JsonPath.read(inbox, "$.messages")).hasSize(1);
        assertThat((String) JsonPath.read(inbox, "$.messages[0].Subject"))
                .isEqualTo("Your appointment at Salon Aria has been cancelled");
    }

    @Test
    @DisplayName("a customer with no email causes no message and no failure")
    void nothing_is_sent_when_there_is_nobody_to_send_to() throws Exception {
        aria.book(aria.at(aria.monday, 12, 0), aria.employeeId, "Giorgi", "+995555999888", null);

        assertThat(dispatcher.dispatchDueBatch()).isZero();
        assertThat((List<?>) JsonPath.read(mailpit("GET", "/api/v1/messages"), "$.messages"))
                .isEmpty();
    }

    /** The URL sits on its own line in the text part, directly after the "Change or cancel:" label. */
    private static String tokenFrom(String text) {
        String marker = "/manage/";
        int start = text.indexOf(marker) + marker.length();
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end);
    }

    private String mailpit(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(mailpitApiUrl() + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return response.body();
    }
}
