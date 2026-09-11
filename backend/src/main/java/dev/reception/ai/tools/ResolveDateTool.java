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
