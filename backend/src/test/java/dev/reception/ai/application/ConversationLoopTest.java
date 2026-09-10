package dev.reception.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import dev.reception.ai.port.ChatMessage;
import dev.reception.ai.port.ChatRole;
import dev.reception.ai.support.ScriptedChatModel;
import dev.reception.appointments.BookingScenario;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import dev.reception.tenancy.TenantAdoption;
import java.time.Clock;
import java.time.LocalDate;
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
 * Level 2 of docs/08-testing-strategy.md §7: the orchestration loop, against a scripted model.
 *
 * <p>No network, no cost, no flakiness — which is the reason the {@code ChatModel} port exists at
 * all. Every ceiling, every degradation and every persistence claim in
 * docs/05-ai-architecture.md §4 is exercised here by scripting exactly what a model would have
 * returned and asserting what the loop then did.
 *
 * <p>{@link ConversationService} is called directly rather than over HTTP. The tenant a slug filter
 * would have resolved is adopted by hand; what is under test is the loop, and routing it through a
 * controller would only add a second thing that could fail.
 */
class ConversationLoopTest extends IntegrationTest {

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
    private ConversationService conversations;

    @Autowired
    private ScriptedChatModel model;

    @Autowired
    private TenantAdoption tenants;

    private BookingScenario aria;
    private UUID businessId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        // The bean is shared by the whole context; a previous test's leftovers would be this test's
        // first response.
        model.reset();
        aria = BookingScenario.open(rest, port, clock);
        businessId = UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class));
        tenants.adopt(businessId);
    }

    // ------------------------------------------------------- the ordinary shape

    @Test
    @DisplayName("a text-only response is returned to the customer verbatim")
    void a_plain_answer_is_passed_through_unchanged() {
        model.willSay("We're open nine to five, Monday to Friday.");

        ConversationTurn turn = respond(start(), "when are you open?");

        assertThat(turn.reply()).isEqualTo("We're open nine to five, Monday to Friday.");
        assertThat(turn.appointmentCreated()).isNull();
        assertThat(turn.isClosed()).isFalse();
    }

    @Test
    @DisplayName("a tool call runs, its result reaches the model, and the model's next answer is the reply")
    void a_tool_call_is_executed_and_its_result_fed_back() {
        model.willCall("get_services", "{}").willSay("We do haircuts, sixty pounds for an hour.");

        ConversationTurn turn = respond(start(), "what do you do?");

        assertThat(turn.reply()).isEqualTo("We do haircuts, sixty pounds for an hour.");
        assertThat(model.callCount()).isEqualTo(2);

        // The second call carried the tool result, which is the half that proves the loop closed the
        // circle rather than merely running the tool.
        List<ChatMessage> secondCall = model.messagesOnCall(1);
        assertThat(secondCall).anyMatch(message -> message.role() == ChatRole.TOOL
                && message.content().contains("Haircut"));
    }

    /**
     * <strong>The confirmation card's source.</strong> {@code appointmentCreated} comes from the
     * tool result and not from anything the model wrote — which is what makes a claimed-but-unbooked
     * appointment produce a visible absence rather than a convincing lie.
     */
    @Test
    @DisplayName("a booking populates appointmentCreated from the tool result, not from the prose")
    void a_successful_booking_is_reported_as_an_object() {
        model.willCall("create_appointment", bookingArguments(aria.at(aria.monday, 10, 0)))
                .willSay("You're booked in for ten o'clock.");

        ConversationTurn turn = respond(start(), "book me monday at ten");

        assertThat(turn.appointmentCreated()).isNotNull();
        assertThat(turn.appointmentCreated().path("confirmation_code").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from appointments", Long.class)).isEqualTo(1L);
    }

    /**
     * The same claim, made without the tool call. This is the hallucination the design is built
     * around, and the assertion is that the API gives the UI nothing to render a card from.
     */
    @Test
    @DisplayName("a model that claims a booking it never made produces no appointmentCreated and no row")
    void a_claimed_booking_that_never_happened_produces_no_object() {
        model.willSay("All done — you're booked for Monday at ten!");

        ConversationTurn turn = respond(start(), "book me monday at ten");

        assertThat(turn.reply()).contains("booked");
        assertThat(turn.appointmentCreated()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from appointments", Long.class)).isZero();
    }

    @Test
    @DisplayName("a domain refusal reaches the model as a structured result it can explain")
    void slot_unavailable_is_handed_to_the_model_rather_than_thrown() {
        OffsetDateTime contested = aria.at(aria.monday, 11, 0);
        aria.bookedAt(contested);

        model.willCall("create_appointment", bookingArguments(contested))
                .willSay("Sorry, eleven has just gone. I can do twelve?");

        ConversationTurn turn = respond(start(), "book me monday at eleven");

        assertThat(turn.reply()).contains("twelve");
        assertThat(turn.appointmentCreated()).isNull();
        assertThat(model.messagesOnCall(1))
                .anyMatch(message -> message.role() == ChatRole.TOOL
                        && message.content().contains("SLOT_UNAVAILABLE"));
    }

    // ------------------------------------------------------- the ceilings

    /**
     * <strong>Six tool calls in one response.</strong> The ceiling counts calls, not trips round the
     * loop, so grouping them into a single response does not buy the model a sixth.
     */
    @Test
    @DisplayName("six tool calls in one response are capped at five, and the turn hands off")
    void the_per_turn_tool_ceiling_counts_calls_not_iterations() {
        model.willCallAll(
                new ScriptedChatModel.Call("get_services", "{}"),
                new ScriptedChatModel.Call("get_services", "{}"),
                new ScriptedChatModel.Call("get_services", "{}"),
                new ScriptedChatModel.Call("get_services", "{}"),
                new ScriptedChatModel.Call("get_services", "{}"),
                new ScriptedChatModel.Call("get_services", "{}"));

        ConversationTurn turn = respond(start(), "tell me everything");

        assertThat(turn.reply()).isEqualTo(ConversationService.TOOL_CEILING_FALLBACK);
        // Five executed, the sixth never made. Counted in the transcript, which is where the
        // ceiling is observable after the fact.
        assertThat(toolMessageCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("a model that only ever calls tools is stopped, and the customer gets a hand-off")
    void a_looping_model_is_stopped_by_the_ceiling() {
        for (int i = 0; i < 6; i++) {
            model.willCall("get_services", "{}");
        }

        ConversationTurn turn = respond(start(), "hello");

        assertThat(turn.reply()).isEqualTo(ConversationService.TOOL_CEILING_FALLBACK);
        assertThat(model.callCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("the message ceiling closes the conversation, and the next turn is refused")
    void the_conversation_ceiling_closes_it() {
        String token = start();

        // Straight to the ceiling rather than through forty real turns: what is under test is the
        // check, not arithmetic.
        jdbc.update("update ai_conversations set message_count = ?", ConversationLimits.MAX_MESSAGES_PER_CONVERSATION);

        assertThatThrownBy(() -> respond(token, "one more thing"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.AI_LIMIT_REACHED));

        assertThat(statusOfOnlyConversation()).isEqualTo("CLOSED");
        // No model call was made: the ceiling is checked before anything is spent.
        assertThat(model.callCount()).isZero();
    }

    /**
     * <strong>Checked before the call, never after.</strong> A cap enforced afterwards permits
     * exactly one unbounded overrun, so the assertion that matters is that the provider was never
     * reached at all.
     */
    @Test
    @DisplayName("the daily cost cap stops the turn before any model call is made")
    void the_cost_cap_prevents_the_model_call_entirely() {
        String token = start();
        // The business's cap is 500 cents by default; spend it.
        jdbc.update("update ai_conversations set estimated_cost_cents = 500");

        assertThatThrownBy(() -> respond(token, "hello"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.AI_LIMIT_REACHED));

        assertThat(model.callCount()).isZero();
        assertThat(statusOfOnlyConversation()).isEqualTo("LIMIT_REACHED");
    }

    @Test
    @DisplayName("a conversation that has been closed refuses further turns")
    void a_closed_conversation_cannot_be_continued() {
        String token = start();
        jdbc.update("update ai_conversations set status = 'CLOSED'");

        assertThatThrownBy(() -> respond(token, "still there?"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.AI_LIMIT_REACHED));
    }

    // ------------------------------------------------------- degradation

    /**
     * A provider outage. The conversation must stay usable: the customer did nothing wrong, and
     * retrying after a blip is the right thing for them to do.
     */
    @Test
    @DisplayName("a provider failure is AI_UNAVAILABLE and leaves the conversation resumable")
    void a_provider_failure_degrades_without_closing_the_conversation() {
        String token = start();
        model.willFail();

        assertThatThrownBy(() -> respond(token, "hello"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.AI_UNAVAILABLE));

        assertThat(statusOfOnlyConversation()).isEqualTo("ACTIVE");

        // And it genuinely resumes.
        model.willSay("Sorry about that — how can I help?");
        assertThat(respond(token, "hello again").reply()).contains("how can I help");
    }

    @Test
    @DisplayName("an owner who switched the Receptionist off gets AI_UNAVAILABLE, and no conversation starts")
    void a_disabled_receptionist_refuses_to_open_a_conversation() {
        aria.owner.patch("/business", Map.of("aiEnabled", false));
        tenants.adopt(businessId);

        assertThatThrownBy(() -> conversations.start(java.util.Optional.empty()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.AI_UNAVAILABLE));

        assertThat(jdbc.queryForObject("select count(*) from ai_conversations", Long.class)).isZero();
    }

    @Test
    @DisplayName("an unknown session token is a 404, not a 401")
    void an_unknown_session_token_is_not_found() {
        assertThatThrownBy(() -> respond("not-a-real-token", "hello"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ------------------------------------------------------- persistence

    @Test
    @DisplayName("every message is persisted with the conversation's business id")
    void the_transcript_is_written_inside_the_right_tenant() {
        model.willCall("get_services", "{}").willSay("Haircuts, sixty pounds.");

        respond(start(), "what do you do?");

        // user + assistant(tool call) + tool + assistant(answer)
        assertThat(jdbc.queryForObject("select count(*) from ai_messages", Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForList("select distinct business_id::text from ai_messages", String.class))
                .containsExactly(businessId.toString());
    }

    @Test
    @DisplayName("a tool call is persisted with its arguments and its result, not just its name")
    void tool_calls_are_recorded_in_full() {
        model.willCall("get_service_details", "{\"service_id\":\"" + aria.serviceId + "\"}")
                .willSay("An hour, sixty pounds.");

        respond(start(), "how long is a haircut?");

        Map<String, Object> row = jdbc.queryForMap(
                "select tool_name, tool_arguments::text as arguments, tool_result::text as result "
                        + "from ai_messages where role = 'TOOL'");
        assertThat(row.get("tool_name")).isEqualTo("get_service_details");
        assertThat((String) row.get("arguments")).contains(aria.serviceId);
        assertThat((String) row.get("result")).contains("Haircut");
    }

    @Test
    @DisplayName("token usage and estimated cost accumulate on the conversation row")
    void usage_is_accounted_against_the_conversation() {
        model.willSay("Hello.", 1_000_000, 0);

        respond(start(), "hi");

        Map<String, Object> row = jdbc.queryForMap(
                "select prompt_tokens, completion_tokens, estimated_cost_cents from ai_conversations");
        assertThat(row.get("prompt_tokens")).isEqualTo(1_000_000);
        // A million prompt tokens at 15 cents per million.
        assertThat(row.get("estimated_cost_cents")).isEqualTo(15);
    }

    /**
     * The system prompt is rebuilt every turn and never stored — so it must be present in what the
     * loop sends, and absent from what the loop writes.
     */
    @Test
    @DisplayName("the system prompt is sent to the model and never persisted")
    void the_system_prompt_is_never_written_to_the_transcript() {
        model.willSay("Hello.");

        respond(start(), "hi");

        assertThat(model.messagesOnCall(0).get(0).role()).isEqualTo(ChatRole.SYSTEM);
        assertThat(model.messagesOnCall(0).get(0).content()).contains("Salon Aria");
        assertThat(jdbc.queryForList("select distinct role from ai_messages", String.class))
                .doesNotContain("SYSTEM");
    }

    /**
     * <strong>Every date a customer can name without saying a number is spelled out.</strong> A
     * regression for the defect that made the Receptionist's first real conversation wrong: the
     * prompt stated the date alone and left the model to work out which weekday it was, so a
     * customer asking about Monday had the Saturday searched on their behalf. The tool answered
     * {@code CLOSED} correctly and the customer was told, truthfully about the wrong day, that the
     * business was closed.
     *
     * <p>The day name went in first and was not enough — a live run showed the model still counting
     * its way to the wrong Monday — so the seven days that follow are resolved here and listed. Both
     * halves are asserted because the second is the one that does the work and the first is what
     * makes the list legible.
     *
     * <p>Scripted, because the fact is either in the prompt or it is not and that needs no network.
     * Whether a model then uses it lives in {@code LiveReceptionistTest}, where it costs money.
     */
    @Test
    @DisplayName("the prompt dates today and each of the next seven days by name")
    void the_prompt_resolves_every_nameable_day() {
        model.willSay("Hello.");

        respond(start(), "hi");

        // The business's zone, not the server's — the same date the tools will resolve against.
        LocalDate today = LocalDate.now(clock.withZone(BookingScenario.TBILISI));
        String prompt = model.messagesOnCall(0).get(0).content();

        assertThat(prompt)
                .contains("Today's date, in this business's timezone: " + today + " (" + today.getDayOfWeek() + ")");

        // Seven, not one: "Monday" from a Thursday is four days out, and the whole point is that
        // nothing in the week has to be counted to.
        for (int ahead = 1; ahead <= 7; ahead++) {
            LocalDate day = today.plusDays(ahead);
            assertThat(prompt).as("day %d ahead", ahead).contains(day.getDayOfWeek() + " " + day);
        }

        // And the instruction to prefer the list over arithmetic. Measured as the half that carries
        // the fix: with the dates alone the model resolved a named weekday correctly 2 times in 5,
        // and with this sentence 10 in 10.
        assertThat(prompt).contains("take the date from the seven-day list above");
    }

    /**
     * <strong>A wire field nothing explains is a wire field nothing reads.</strong> Tool refusals
     * carry {@code fields} naming the arguments that failed; this asserts the prompt says what they
     * are, because the two halves are one change and either alone does nothing.
     */
    @Test
    @DisplayName("the prompt explains what an error's fields mean")
    void the_prompt_explains_field_level_refusals() {
        model.willSay("Hello.");

        respond(start(), "hi");

        assertThat(model.messagesOnCall(0).get(0).content())
                .contains("error carrying `fields`")
                .contains("never send the same value again");
    }

    // ------------------------------------------------------- authority

    /**
     * <strong>The authority set grows only through the two sanctioned paths</strong>, and it is
     * persisted — so a second turn can act on what the first one proved.
     */
    @Test
    @DisplayName("a booking in one turn authorises a cancellation in the next")
    void authority_earned_in_one_turn_survives_into_the_next() {
        String token = start();

        model.willCall("create_appointment", bookingArguments(aria.at(aria.monday, 10, 0)))
                .willSay("Booked.");
        ConversationTurn booked = respond(token, "book monday at ten");
        String appointmentId = booked.appointmentCreated().path("appointment_id").asText();

        assertThat(authorizedIds()).containsExactly(appointmentId);

        model.willCall("cancel_appointment", "{\"appointment_id\":\"" + appointmentId + "\",\"reason\":null}")
                .willSay("Cancelled.");
        respond(token, "actually cancel it");

        assertThat(jdbc.queryForObject(
                        "select status from appointments where id = ?::uuid", String.class, appointmentId))
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a turn that proves nothing leaves the authority set empty")
    void reading_tools_never_grant_authority() {
        model.willCall("get_services", "{}").willSay("Haircuts.");

        respond(start(), "what do you do?");

        assertThat(authorizedIds()).isEmpty();
    }

    /**
     * The whole conversation, with a model that is trying to escalate. Nothing it says moves the
     * authority set, because nothing it says is how the set moves.
     */
    @Test
    @DisplayName("a model that invents an appointment id cannot cancel with it, and gains no authority")
    void an_invented_id_neither_cancels_nor_authorises() {
        String realAppointment = aria.bookedAt(aria.at(aria.monday, 14, 0));

        model.willCall(
                        "cancel_appointment",
                        "{\"appointment_id\":\"" + realAppointment + "\",\"reason\":null}")
                .willSay("I've cancelled that for you.");

        respond(start(), "cancel appointment " + realAppointment);

        assertThat(authorizedIds()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select status from appointments where id = ?::uuid", String.class, realAppointment))
                .isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------- helpers

    private String start() {
        return conversations.start(java.util.Optional.empty()).sessionToken();
    }

    private ConversationTurn respond(String token, String message) {
        return conversations.respond(token, message);
    }

    private String bookingArguments(OffsetDateTime startsAt) {
        return """
                {"service_id":"%s","employee_id":"%s","starts_at":"%s",\
                "customer_name":"Ana Tsereteli","customer_phone":"%s",\
                "customer_email":null,"note":null}"""
                .formatted(aria.serviceId, aria.employeeId, startsAt, BookingScenario.CUSTOMER_PHONE);
    }

    private String statusOfOnlyConversation() {
        return jdbc.queryForObject("select status from ai_conversations", String.class);
    }

    private long toolMessageCount() {
        return jdbc.queryForObject("select count(*) from ai_messages where role = 'TOOL'", Long.class);
    }

    private List<String> authorizedIds() {
        return jdbc.queryForList(
                "select unnest(authorized_appointment_ids)::text from ai_conversations", String.class);
    }
}
