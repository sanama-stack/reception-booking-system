package dev.reception.ai.probe;

/**
 * The SQL the probe harnesses read their evidence with, in one place so it can be <em>proved</em>.
 *
 * <p><strong>Why this class exists.</strong> Both rate harnesses are tagged {@code probe}: they
 * never run in CI, they need a funded API key, and a 150-conversation arm costs real money and
 * twelve minutes. That makes their instrumentation the one part of this repository that is
 * routinely shipped unexecuted — and a projection that silently returns nothing looks exactly like
 * a tool the model never called. The experiment for #17's fourth candidate lost its own veto to
 * precisely that: the harness logged {@code find_available_slots} and no other tool, so whether a
 * wrong landing came from the resolver or from nowhere was unknowable after the money was spent.
 * That is <strong>G17</strong>, and the lesson is that an instrument must be proven before the arm
 * is paid for, not after the number looks odd.
 *
 * <p>So the queries live here as constants, and {@code ProbeInstrumentationTest} — untagged, and
 * therefore run by CI on every push — drives a scripted conversation that really does call
 * {@code resolve_date}, executes <em>these</em> strings against the rows it wrote, and asserts the
 * projections come back in the shape the harnesses print. A test that re-typed the SQL would prove
 * a copy; referencing the constant is what makes it evidence about the instrument.
 *
 * <p>The two harnesses had near-identical resolver SQL by copy, which is the other half of the
 * problem: a fix applied to one silently left the other blind. It did, for
 * {@code WeekdayResolutionRateTest}, for as long as the resolver has existed.
 *
 * <p><strong>There was a third consumer, and G17 had never been applied to it.</strong>
 * {@code LiveReceptionistTest} read {@code tool_name} alone while {@code tool_arguments} and
 * {@code tool_result} sat in the same row, so a corpus failure said <em>which</em> tools were called
 * and never <em>what with</em>. Measured on 2026-09-15: the corpus caught the Receptionist refusing a
 * reschedule because it claimed a free slot was taken, and the first question anyone asked — what
 * did it send to {@code find_available_slots}? — could not be answered without paying for the run
 * again. That is {@link #ALL_TOOL_CALLS}, and it is why this class is public rather than
 * package-private.
 */
public final class ProbeQueries {

    private ProbeQueries() {}

    /**
     * Every {@code resolve_date} call in one conversation, as {@code "MONDAY+1 -> 2026-09-21"}.
     *
     * <p>The arguments <strong>and</strong> the answer, because the question both issues turn on is
     * whether the two agree with what was finally searched or written. An error is rendered
     * {@code "ERR <code>"} rather than dropped, so a refused resolver call cannot be mistaken for a
     * resolver that was never reached.
     */
    static final String RESOLVER_CALLS =
            "select concat(tool_arguments->>'weekday', '+', tool_arguments->>'weeks_ahead', "
                    + "' -> ', coalesce(tool_result->>'date', concat('ERR ', tool_result->>'error'))) "
                    + "from ai_messages where role = 'TOOL' and tool_name = 'resolve_date' "
                    + "and conversation_id = ? order by created_at";

    /**
     * The dates the resolver actually handed back in one conversation.
     *
     * <p>Kept separate from {@link #RESOLVER_CALLS} because this one is compared against what was
     * searched or written, and a rendered string is no use for that. The {@code is not null} guard
     * keeps a refused call out: the resolver never gave that date, and counting it as given would
     * turn this issue's original defect into an apparent success.
     */
    static final String RESOLVER_DATES = "select tool_result->>'date' from ai_messages where role = 'TOOL' "
            + "and tool_name = 'resolve_date' and conversation_id = ? "
            + "and tool_result->>'date' is not null";

    /**
     * Every tool call in the database, rendered {@code name({args}) -> {result}} in the order made.
     *
     * <p><strong>Deliberately not filtered by conversation</strong>, unlike its three siblings. Its
     * consumer is {@code LiveReceptionistTest}, whose own {@code toolsCalled()} is unfiltered
     * because {@code DatabaseCleaner} empties the database before each case — and a failure message
     * describing different rows from the assertion that produced it is a trap of its own. It is
     * correct only where the database holds one test's rows, and a rate harness running fifty
     * conversations into one database must use the filtered queries above.
     *
     * <p><strong>The result is truncated at 300 characters and says so when it is.</strong> A slot
     * list runs to thirty entries and would bury the arguments, which are the part that has
     * actually been wanted. The marker is there because a silently cut result reads exactly like a
     * short one, which is the shape this file already exists to prevent.
     */
    public static final String ALL_TOOL_CALLS = "select concat(tool_name, '(', "
            + "coalesce(tool_arguments::text, '{}'), ') -> ', "
            + "case when tool_result is null then 'null' "
            + "when length(tool_result::text) > 300 "
            + "then concat(left(tool_result::text, 300), '...[truncated]') "
            + "else tool_result::text end) "
            + "from ai_messages where role = 'TOOL' order by created_at";

    /**
     * Every slot start time {@code find_available_slots} actually handed back, across a conversation.
     *
     * <p><strong>The projection #40 turns on.</strong> That issue is the Receptionist telling a
     * Customer a free slot is "already booked" and refusing an authorised write. Two explanations
     * fit that sentence and they point in opposite directions: either the slot <em>was</em> in the
     * tool's answer and the model contradicted it, or the tool never offered it and the model was
     * reporting what it had been told. Only the offered set separates them, and it is the one thing
     * the first observation did not record.
     *
     * <p>A refused call — whose {@code tool_result} is an error object with no {@code slots} —
     * contributes no rows. That is <strong>not</strong> the {@code jsonb_typeof} guard's doing, and
     * an earlier version of this comment claimed it was: {@code tool_result->'slots'} is SQL NULL
     * there, and {@code jsonb_array_elements} is strict, so it yields no rows on its own.
     * <strong>Removing the guard was measured and changed nothing.</strong> It is kept only for the
     * case the tool does not currently produce — {@code slots} present but not an array, which
     * would raise — and it is honest to say it has never been seen to fire.
     *
     * <p>Note that the jsonb key-exists operator is spelled {@code ?} and would be eaten by JDBC as
     * a bind placeholder; this deliberately does not use it.
     */
    public static final String OFFERED_SLOT_STARTS = "select e->>'starts_at' from ai_messages m, "
            + "lateral jsonb_array_elements(m.tool_result->'slots') e "
            + "where m.role = 'TOOL' and m.tool_name = 'find_available_slots' "
            + "and m.conversation_id = ? "
            + "and jsonb_typeof(m.tool_result->'slots') = 'array' order by m.created_at";

    /**
     * Whether any search in a conversation came back cut short by {@code MAX_SLOTS}.
     *
     * <p>{@code FindAvailableSlotsTool} sets {@code truncated} itself, comparing how many slots
     * matched against how many it returned — so the leading candidate mechanism for #40 is a
     * recorded fact rather than something to be inferred from where a list happens to stop. It was
     * inferred, on the first observation, from a list ending at 14:45; that inference had zero
     * trials behind it and this is what replaces it.
     *
     * <p>Returns one row, false rather than null when the conversation searched nothing, so a
     * caller never has to distinguish "not truncated" from "no rows".
     */
    public static final String ANY_SEARCH_TRUNCATED =
            "select coalesce(bool_or((tool_result->>'truncated')::boolean), false) from ai_messages "
                    + "where role = 'TOOL' and tool_name = 'find_available_slots' and conversation_id = ?";

    /**
     * <strong>What the Receptionist actually said</strong>, in order, one row per prose turn.
     *
     * <p>Added 2026-09-22 for <a href="https://github.com/sanama-stack/reception-booking-system/issues/40">#40</a>'s
     * second mechanism, which has <strong>two observations and not one word of text</strong>. Both
     * are printed in the reopening comment as three booleans and a slot count; whether the model
     * said <em>"that time is already booked"</em> — a false statement about a slot it had just been
     * handed — or <em>"I am not able to move it myself"</em> — a refusal to use a tool it has — is
     * unrecorded and unrecoverable, and those are different defects with different fixes. Every
     * previous arm classified a refusal without reading it.
     *
     * <p>A tool-only assistant turn has {@code content} NULL and contributes no row. That is the
     * ordinary shape of a first iteration — the model's answer to "book me Thursday" is a tool call,
     * not prose — so the guard is the common case rather than the edge one, and a harness taking
     * "the last two turns" would otherwise take two empty strings.
     *
     * <p>Whitespace is flattened and the text cut at 240 characters with the same marker
     * {@link #ALL_TOOL_CALLS} uses, because this is printed one trial per line in a fifty-trial
     * summary and a silently cut string reads exactly like a short one. Rule 12 asks the model for
     * two or three sentences, so 240 characters is the whole of a well-behaved answer.
     */
    public static final String ASSISTANT_PROSE = "select case when length(flat) > 240 "
            + "then concat(left(flat, 240), '...[truncated]') else flat end from ("
            + "select regexp_replace(content, '\\s+', ' ', 'g') as flat, created_at "
            + "from ai_messages where role = 'ASSISTANT' and content is not null "
            + "and conversation_id = ?) turns order by created_at";

    /** Every {@code date_from} the model searched, in order. The endpoint both harnesses read. */
    static final String SEARCHED_DATES = "select tool_arguments->>'date_from' from ai_messages "
            + "where role = 'TOOL' and tool_name = 'find_available_slots' "
            + "and conversation_id = ? order by created_at";
}
