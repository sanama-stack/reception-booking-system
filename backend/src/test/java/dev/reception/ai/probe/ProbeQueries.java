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
 */
final class ProbeQueries {

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

    /** Every {@code date_from} the model searched, in order. The endpoint both harnesses read. */
    static final String SEARCHED_DATES = "select tool_arguments->>'date_from' from ai_messages "
            + "where role = 'TOOL' and tool_name = 'find_available_slots' "
            + "and conversation_id = ? order by created_at";
}
