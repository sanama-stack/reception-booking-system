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

    /** Every {@code date_from} the model searched, in order. The endpoint both harnesses read. */
    static final String SEARCHED_DATES = "select tool_arguments->>'date_from' from ai_messages "
            + "where role = 'TOOL' and tool_name = 'find_available_slots' "
            + "and conversation_id = ? order by created_at";
}
