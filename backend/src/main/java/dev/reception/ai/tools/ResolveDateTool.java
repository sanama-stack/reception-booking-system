package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.business.BusinessService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import org.springframework.stereotype.Component;

/**
 * Turns a weekday the Customer named into the calendar date it falls on.
 *
 * <p><strong>Why a tool and not a bigger list.</strong> The system prompt carries the next seven
 * days, each spelled with its weekday, and the model is told to look a named day up there rather
 * than work one out. That list fixed a real defect and it is still the right shape for the range it
 * covers — but it covers seven days, and a Customer who says "the Monday after next" is naming a
 * date outside it. For that class of date the model has nothing to look up and falls back to
 * arithmetic it is measurably bad at: over fifty live conversations it aimed {@code date_from} at
 * such a target 10 times, against 42 when the Customer read out an ISO date (#17).
 *
 * <p>Making the list longer was tried and is <strong>worse</strong>: at fourteen days the model
 * began citing a date one day off the end of the list, and never-wrote went 16% to 42%. A list works
 * because it is short enough to scan. So the counting moves here, where it is
 * {@link TemporalAdjusters#next} and a multiplication, and the model keeps only the part it does
 * well — hearing which weekday was said, and whether the Customer meant the coming one or a later
 * one.
 *
 * <p><strong>What is deliberately still the model's.</strong> Mapping "after next" to
 * {@code weeks_ahead = 1} is an interpretation, and this tool does not remove it. It could not:
 * "the Monday after next" is ambiguous in English and a resolver that picked a reading in code would
 * be guessing with more confidence than the model, not less. What moves into code is the
 * <em>arithmetic</em>, which has one right answer.
 *
 * <p><strong>Measured, and NOT yet accepted.</strong> Fifty live conversations on 2026-09-15, the arm the
 * 2026-09-11 sitting could not finish because the API ran out of credits. The resolver was called
 * in <strong>50 of 50</strong> trials, against a same-question control taken on 2026-09-11:
 *
 * <pre>
 *                                   control      with resolver     Fisher, one-sided
 *   window covered the named date   9/50  18.0%   19/50  38.0%     p = 0.022
 *   strict landing                  18/44 40.9%   35/50  70.0%     p = 0.0041
 *   never wrote                     6             0
 *   nearer reading of the phrase    24            0
 * </pre>
 *
 * <p><strong>Two cautions attach to that table.</strong> It did not reproduce the primary the
 * 2026-09-11 arm recorded — 58% then, 38% now, p = 0.036 that the earlier number was genuinely
 * better — so it clears its pre-registered 38% threshold by landing exactly on it, and
 * <strong>81.6% must not be quoted again</strong>. And the arm below it is not comparable at all:
 * measured the same day with {@code PROBE_DATE_STYLE=ISO}, the resolver is called <em>zero</em>
 * times in fifty, because an explicit date needs no arithmetic. An ISO run cannot judge this tool.
 *
 * <p><strong>It is not what broke the ISO path, and that was tested rather than assumed.</strong>
 * The same day's ISO arm fell to 12/40 against 42/47 recorded four days earlier, and the obvious
 * suspect was this tool sitting in the schema the constrained decoder reads even though it is
 * never called there. Removing it entirely — bean and both prompt passages, verified by dumping
 * the eight-tool schema and a prompt naming it zero times — moved nothing: 8/32, p = 0.77 that
 * removal helped. Distance-to-horizon was tested next and also exonerated (§13). The cause is
 * elsewhere: on a reschedule the model sets {@code date_from} to the day after the appointment's
 * <em>current</em> date, ignoring the one the Customer named — §14 of the experiment log.
 *
 * <p><strong>The second veto fires, on two trials of fifty.</strong> The pre-registration's rule
 * is <em>accept at 19/50 or better, if neither veto fires</em>, and the primary came in at exactly
 * 19/50 — the rule's minimum, met rather than cleared. But the veto reads "no landing may appear
 * one step off the resolver's own output", and trials 31 and 44 are exactly that: the resolver was
 * asked {@code MONDAY+1}, answered {@code 2026-09-28}, and the model wrote {@code 2026-10-05}.
 * Whether a 2-in-50 overshoot should sink a candidate that doubled the primary is a judgement the
 * pre-registration deliberately did not delegate to whoever reads the numbers. <strong>Until that
 * call is made this tool stays what it was: shipped on {@code dev}, under test.</strong>
 *
 * <p><strong>The rest of the residual is interpretation, exactly where the paragraph above said it
 * would be.</strong> Of the fifteen wrong writes, ten landed on a date the resolver <em>gave</em> and
 * five on one it never gave. The resolver's arguments say why: the model asked for
 * {@code MONDAY+1} forty times and {@code MONDAY+2} ten times, and was served the right answer to
 * both questions every time. So the arithmetic this tool took over is not the thing still failing
 * — choosing the week is, and that was left here deliberately.
 *
 * <p><strong>No horizon check here.</strong> This tool answers "what date is that?" and nothing
 * else; whether the business will take a booking that far out belongs to
 * {@code find_available_slots}, which already enforces it and already explains itself when it
 * refuses. Two places deciding the same thing is how they drift apart.
 */
@Component
public class ResolveDateTool implements Tool {

    /** Two months of Mondays. Past this, a Customer is naming a date rather than a day. */
    private static final int MAX_WEEKS_AHEAD = 8;

    private final BusinessService businesses;

    public ResolveDateTool(BusinessService businesses) {
        this.businesses = businesses;
    }

    @Override
    public String name() {
        return "resolve_date";
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                name(),
                "Work out the calendar date of a weekday the customer named. Use this whenever the "
                        + "day they mean is NOT in the seven-day list in your instructions — "
                        + "\"the Monday after next\", \"a week on Thursday\", anything more than a "
                        + "week away. Never count the days yourself.",
                ToolSchemas.object()
                        .required(
                                "weekday",
                                "string",
                                "The weekday the customer named, in capitals: MONDAY, TUESDAY, "
                                        + "WEDNESDAY, THURSDAY, FRIDAY, SATURDAY or SUNDAY.")
                        .required(
                                "weeks_ahead",
                                "integer",
                                "0 for the next one of that weekday. 1 for the one after that — "
                                        + "\"the Monday after next\", \"a week on Monday\". 2 for a "
                                        + "fortnight after the next one, and so on.")
                        .build());
    }

    @Override
    public ObjectNode execute(JsonNode arguments, ToolContext context) {
        DayOfWeek weekday = ToolArguments.dayOfWeek(arguments, "weekday");
        int weeksAhead = ToolArguments.integer(arguments, "weeks_ahead", 0, MAX_WEEKS_AHEAD);

        ZoneId zone = businesses.read().timezone();
        LocalDate today = LocalDate.now(context.clock().withZone(zone));

        // next() is strictly after today, so "next Monday" said on a Monday means the one coming,
        // not the one the customer is standing in.
        LocalDate resolved = today.with(TemporalAdjusters.next(weekday)).plusWeeks(weeksAhead);

        ObjectNode result = ToolResults.object();
        result.put("date", resolved.toString());
        // Echoed so a wrong weekday is visible in the result rather than only in the argument, and
        // so the model can read the answer back to the customer without re-deriving anything.
        result.put("day_of_week", resolved.getDayOfWeek().name());
        result.put("days_from_today", ChronoUnit.DAYS.between(today, resolved));
        return result;
    }
}
