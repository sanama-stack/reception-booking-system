package dev.reception.ai.probe;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.ai.application.ConversationService;
import dev.reception.ai.support.ScriptedChatModel;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import dev.reception.tenancy.TenantAdoption;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <strong>Proves the probe harnesses' instrumentation without spending a penny.</strong>
 *
 * <p>Deliberately <strong>not</strong> tagged {@code probe}, which is the whole point: the two rate
 * harnesses never run in CI and only run at all when someone has a funded key and twelve minutes,
 * so their SQL is the code in this repository most likely to be wrong and least likely to be
 * caught. This test drives a scripted conversation that really calls {@code resolve_date} and
 * {@code find_available_slots}, then executes {@link ProbeQueries}' own constants against the rows
 * that conversation wrote.
 *
 * <p><strong>G17 is why.</strong> The experiment for #17's fourth candidate could not decide its own
 * veto, because the harness read {@code find_available_slots} and no other tool: whether a wrong
 * landing was a date the resolver gave or a date from nowhere was unanswerable once the money was
 * gone. The instrumented re-run that would have settled it got fifteen trials in before the account
 * ran out of credits. An instrument has to be proven <em>before</em> the arm is paid for.
 *
 * <p>It asserts the <em>shape</em> the harnesses print and rely on — a rendered
 * {@code "MONDAY+1 -> 2026-09-21"}, a bare date list, ordering, and the empty case — rather than
 * the rate, which is the model's business and not this test's.
 */
class ProbeInstrumentationTest extends IntegrationTest {

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

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        model.reset();
        aria = BookingScenario.open(rest, port, clock);
        tenants.adopt(UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class)));
    }

    @Test
    @DisplayName("a resolve_date call is visible to the harnesses, with its arguments and its answer")
    void the_resolver_query_renders_the_call_the_way_the_harnesses_print_it() {
        model.willCall("resolve_date", "{\"weekday\":\"MONDAY\",\"weeks_ahead\":1}")
                .willSay("That would be the Monday after next.");

        UUID conversation = ask("the Monday after next, please");

        // The date the tool really computed, derived the same way the tool derives it, so this
        // assertion cannot pass by agreeing with a hardcoded date that has drifted.
        LocalDate today = LocalDate.now(clock.withZone(BookingScenario.TBILISI));
        LocalDate expected = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);

        List<String> calls = jdbc.queryForList(ProbeQueries.RESOLVER_CALLS, String.class, conversation);
        assertThat(calls).containsExactly("MONDAY+1 -> " + expected);

        List<String> dates = jdbc.queryForList(ProbeQueries.RESOLVER_DATES, String.class, conversation);
        assertThat(dates).containsExactly(expected.toString());
    }

    /**
     * The case that makes the instrument load-bearing: a conversation where the model searched a
     * date and the resolver had nothing to do with it. Both harnesses classify a wrong result by
     * asking whether the searched date is one the resolver gave, so "gave nothing" must come back
     * as an empty list and never as a row.
     *
     * <p><strong>This test cannot fail for a broken query, and that is not a defect in it.</strong>
     * Pointing {@link ProbeQueries#RESOLVER_CALLS} at a tool name that does not exist turns the
     * other three red and leaves this one green, because an empty result is exactly what it
     * asserts. It is safe only because those three prove the same constant finds rows when rows
     * exist. An emptiness assertion standing on its own is the shape this repository has now been
     * caught by three times; it is kept because the classifier's "resolver not called" branch has
     * to be exercised, not because it is evidence by itself.
     */
    @Test
    @DisplayName("a conversation that never calls the resolver returns no rows, not a null row")
    void a_conversation_without_the_resolver_is_empty_rather_than_a_phantom() {
        model.willCall("find_available_slots", "{\"date_from\":\"%s\"}".formatted(aria.monday))
                .willSay("Here is what I have.");

        UUID conversation = ask("what have you got free next Monday?");

        assertThat(jdbc.queryForList(ProbeQueries.RESOLVER_CALLS, String.class, conversation))
                .isEmpty();
        assertThat(jdbc.queryForList(ProbeQueries.RESOLVER_DATES, String.class, conversation))
                .isEmpty();
        // And the endpoint the rate is actually computed from still sees the search.
        assertThat(jdbc.queryForList(ProbeQueries.SEARCHED_DATES, String.class, conversation))
                .containsExactly(aria.monday.toString());
    }

    /**
     * A refused resolver call must not read as a date the resolver gave. {@code RESOLVER_DATES}
     * drops it; {@code RESOLVER_CALLS} keeps it, rendered as an error — because a call that was
     * made and rejected is a different finding from a call that never happened, and the harnesses'
     * "resolver not called" branch would otherwise claim the second.
     */
    @Test
    @DisplayName("a refused resolve_date is rendered as an error and gives no date")
    void a_refused_resolver_call_is_not_a_date() {
        model.willCall("resolve_date", "{\"weekday\":\"MONDAY\",\"weeks_ahead\":52}")
                .willSay("Sorry, I cannot look that far ahead.");

        UUID conversation = ask("a year on Monday");

        assertThat(jdbc.queryForList(ProbeQueries.RESOLVER_CALLS, String.class, conversation))
                .containsExactly("MONDAY+52 -> ERR VALIDATION_FAILED");
        assertThat(jdbc.queryForList(ProbeQueries.RESOLVER_DATES, String.class, conversation))
                .isEmpty();
    }

    /**
     * Both harnesses print the resolver's calls in the order they were made and read the searches
     * the same way. A projection that came back unordered would make a two-step conversation
     * unreadable in exactly the runs that matter — the ones where the model asked twice.
     */
    @Test
    @DisplayName("several calls come back in the order they were made")
    void the_projections_preserve_order() {
        model.willCall("resolve_date", "{\"weekday\":\"MONDAY\",\"weeks_ahead\":0}")
                .willCall("resolve_date", "{\"weekday\":\"MONDAY\",\"weeks_ahead\":1}")
                .willSay("Either of those.");

        UUID conversation = ask("Monday, or the one after");

        LocalDate today = LocalDate.now(clock.withZone(BookingScenario.TBILISI));
        LocalDate first = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        assertThat(jdbc.queryForList(ProbeQueries.RESOLVER_CALLS, String.class, conversation))
                .containsExactly("MONDAY+0 -> " + first, "MONDAY+1 -> " + first.plusWeeks(1));
        assertThat(jdbc.queryForList(ProbeQueries.RESOLVER_DATES, String.class, conversation))
                .containsExactlyInAnyOrder(first.toString(), first.plusWeeks(1).toString());
    }

    /**
     * {@link ProbeQueries#ALL_TOOL_CALLS} is what a corpus failure prints, and a corpus failure is
     * rare, expensive and read once. It has to carry the arguments: on 2026-09-15 the live corpus
     * caught the Receptionist refusing a reschedule on the claim that a free slot was taken, and
     * what it had sent to {@code find_available_slots} was unrecoverable, because the only thing
     * recorded was the tool's name.
     */
    @Test
    @DisplayName("the corpus dump carries each call's arguments and its result, not just the name")
    void the_tool_call_dump_renders_arguments_and_result() {
        model.willCall("resolve_date", "{\"weekday\":\"MONDAY\",\"weeks_ahead\":1}")
                .willCall("find_available_slots", "{\"date_from\":\"%s\"}".formatted(aria.monday))
                .willSay("Here is what I have.");

        ask("the Monday after next, please");

        List<String> calls = jdbc.queryForList(ProbeQueries.ALL_TOOL_CALLS, String.class);

        // Order made, name, and -- the entire point -- the arguments the model actually sent.
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).startsWith("resolve_date(").contains("\"weekday\": \"MONDAY\"");
        assertThat(calls.get(1))
                .startsWith("find_available_slots(")
                .contains("\"date_from\": \"" + aria.monday + "\"")
                .contains(") -> ");
    }

    /**
     * A truncated result must announce itself. A slot list is long enough to hit the cap, and a
     * result silently cut at 300 characters reads exactly like a short one -- which is the reading
     * error this whole class exists to stop.
     */
    @Test
    @DisplayName("a result past the cap is cut and says that it was")
    void a_long_result_is_marked_as_truncated() {
        // A VALID call, with the service_id the tool requires. Without it the tool answers
        // "service_id is required" in about a hundred characters, the cap never fires, and the
        // assertion below passes against a truncation that never happened -- which is what the
        // first draft of this test did, behind an if/else that accepted either outcome.
        model.willCall(
                        "find_available_slots",
                        "{\"date_from\":\"%s\",\"service_id\":\"%s\"}".formatted(aria.monday, aria.serviceId))
                .willSay("Here is what I have.");

        ask("what have you got free next Monday?");

        String rendered = jdbc.queryForList(ProbeQueries.ALL_TOOL_CALLS, String.class)
                .getFirst();

        // Asserted unconditionally rather than behind an if. A branch that accepts either outcome
        // would pass against a cap that never fires, which is a test of nothing: the fixture's open
        // day returns enough slots to blow 300 characters, and if that ever stops being true this
        // should go red and be re-read rather than quietly stop exercising the cap.
        assertThat(rendered).endsWith("...[truncated]");
        // Either way the arguments survive, because they are rendered before the cap applies.
        assertThat(rendered).contains("\"date_from\": \"" + aria.monday + "\"");
    }

    /**
     * The two projections #40's harness reads. A valid search really does return slots, and the
     * offered set really does contain the time the fixture leaves free — because the whole question
     * that harness answers is whether a refusal happened with the slot on the table or without it,
     * and a projection that quietly returned nothing would answer "without it" every time.
     */
    @Test
    @DisplayName("the offered slots come back, and truncation is reported as the tool set it")
    void the_offered_slots_and_the_truncation_flag_are_readable() {
        UUID conversation = searchTheFixturesOpenDay();

        List<String> offered = jdbc.queryForList(ProbeQueries.OFFERED_SLOT_STARTS, String.class, conversation);
        assertThat(offered).isNotEmpty();

        // 15:00 on the fixture's open day is free by construction -- nothing is booked in this test
        // -- so it MUST be among the offered starts. This is the assertion #40's harness stands on.
        OffsetDateTime wanted = aria.at(aria.monday, 15, 0);
        assertThat(offered.stream().map(OffsetDateTime::parse).map(OffsetDateTime::toInstant))
                .describedAs("offered starts: %s", offered)
                .contains(wanted.toInstant());

        // The tool computes this; the harness must not re-derive it from where the list stops.
        assertThat(jdbc.queryForObject(ProbeQueries.ANY_SEARCH_TRUNCATED, Boolean.class, conversation))
                .isNotNull();
    }

    /**
     * A refused search must contribute <strong>no</strong> offered slots. #40's harness asks whether
     * a refusal happened with the wanted slot on the table, so a rejected call leaking in as
     * "offered nothing" is fine and a rejected call raising would lose the trial entirely — and
     * measured on 2026-09-15, the model's first search is rejected in most conversations.
     *
     * <p><strong>This passes with the {@code jsonb_typeof} guard removed, which was checked.</strong>
     * {@code tool_result->'slots'} is SQL NULL for an error object and
     * {@code jsonb_array_elements} is strict, so emptiness here comes from NULL-strictness rather
     * than from the guard. The assertion is kept because the harness depends on the property; the
     * claim that the guard produces it was wrong and is corrected in {@link ProbeQueries}.
     */
    @Test
    @DisplayName("a refused search contributes no offered slots")
    void a_refused_search_does_not_break_the_offered_slots_projection() {
        // No service_id: the tool answers VALIDATION_FAILED, and tool_result has no slots array.
        model.willCall("find_available_slots", "{\"date_from\":\"%s\"}".formatted(aria.monday))
                .willSay("Let me check.");

        UUID conversation = ask("what have you got free next Monday?");

        assertThat(jdbc.queryForList(ProbeQueries.OFFERED_SLOT_STARTS, String.class, conversation))
                .isEmpty();
        assertThat(jdbc.queryForObject(ProbeQueries.ANY_SEARCH_TRUNCATED, Boolean.class, conversation))
                .isFalse();
    }

    /** A valid search of the fixture's open day, which is what both projections above read. */
    private UUID searchTheFixturesOpenDay() {
        model.willCall(
                        "find_available_slots",
                        "{\"date_from\":\"%s\",\"service_id\":\"%s\"}".formatted(aria.monday, aria.serviceId))
                .willSay("Here is what I have.");
        return ask("what have you got free next Monday?");
    }

    private UUID ask(String utterance) {
        ConversationService.StartedConversation started = conversations.start(Optional.empty());
        conversations.respond(started.sessionToken(), utterance);
        return started.conversationId();
    }

    /**
     * <strong>A fifty-trial arm outlives its own login, and the harness has to survive that.</strong>
     *
     * <p>The access token lives fifteen minutes. Measured 2026-09-15, an ISO arm ran 16m 32s
     * because the provider was slow that hour and died at trial 36 with {@code 401 TOKEN_EXPIRED}
     * while creating the next appointment — thirty-six trials of paid-for model calls thrown away
     * for a reason that had nothing to do with the model. {@code BookingScenario.bookedAt} now
     * refreshes once and retries.
     *
     * <p>Free to prove, and proven here rather than by running another arm and hoping: dropping
     * the access cookie is the exact state of a real session fifteen minutes in, which is what
     * {@code AuthTestClient.expireCookie} exists for. Reverting the retry turns this red.
     */
    @Test
    @DisplayName("the fixture books through an expired access token, because a long arm outlives one")
    void the_fixture_survives_its_own_session_expiring() {
        databaseCleaner.clean();
        BookingScenario aria = BookingScenario.open(rest, port, clock);

        // Fifteen minutes in, as a browser would have it: the access cookie is gone and the
        // refresh cookie is not.
        aria.owner.expireCookie("access_token");

        String id = aria.bookedAt(aria.at(aria.monday, 12, 0));

        assertThat(id).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from appointments", Integer.class))
                .isEqualTo(1);
    }
}
