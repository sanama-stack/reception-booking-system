package dev.reception.ai.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a turn cost, and whether the business may spend any more today.
 *
 * <p><strong>The cap is checked before the model call, never after.</strong> Checking afterwards
 * would mean the call that exceeds the cap is the call that gets made — a cap that permits exactly
 * one unbounded overrun is not a cap. The consequence is that the last permitted turn may cost more
 * than the remaining budget, which is the right way round: bounded overshoot by one turn, rather
 * than unbounded by one runaway loop.
 *
 * <p>"Today" is the Business's own day, not UTC's. A cap that reset at four in the afternoon local
 * time would be a daily budget the owner could not reason about.
 */
@Component
public class CostTracker {

    private final AiConversationRepository conversations;
    private final AiProperties properties;
    private final Clock clock;

    public CostTracker(AiConversationRepository conversations, AiProperties properties, Clock clock) {
        this.conversations = conversations;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The estimate this turn adds to the running total.
     *
     * <p>Integer cents, rounded up. Rounding down would let a long series of cheap turns accumulate
     * to nothing and never reach a cap; rounding up costs at most a cent a turn and always
     * terminates.
     */
    public int costCentsFor(int promptTokens, int completionTokens) {
        long prompt = (long) promptTokens * properties.getPromptCentsPerMillionTokens();
        long completion = (long) completionTokens * properties.getCompletionCentsPerMillionTokens();
        long millionths = prompt + completion;
        return (int) Math.min(Integer.MAX_VALUE, (millionths + 999_999L) / 1_000_000L);
    }

    /** What this Business has spent since midnight in its own timezone. */
    public long spentTodayCents(UUID businessId, ZoneId businessZone) {
        return conversations.sumByBusinessIdSince(
                businessId, LocalDate.now(clock.withZone(businessZone)).atStartOfDay(businessZone).toInstant());
    }

    /**
     * Whether another model call is permitted.
     *
     * <p>A cap of zero disables the Receptionist outright, which is a legitimate way for an owner to
     * turn it off and is why the column's check constraint allows it.
     */
    public boolean withinCap(UUID businessId, ZoneId businessZone, int dailyCapCents) {
        return spentTodayCents(businessId, businessZone) < dailyCapCents;
    }
}
