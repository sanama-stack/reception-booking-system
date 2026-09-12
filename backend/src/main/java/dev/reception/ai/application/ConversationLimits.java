package dev.reception.ai.application;

/**
 * The ceilings, as constants rather than configuration.
 *
 * <p>Every number here changes what a customer experiences, so each one is a decision to be
 * reviewed in a diff rather than a value to be tuned in an environment. The same rule
 * {@code RetryBackoff} and {@code RateLimitProperties} follow, and the reason both of those keep
 * their policy in code while exposing only their switch.
 */
public final class ConversationLimits {

    private ConversationLimits() {}

    /**
     * Tool calls per turn.
     *
     * <p>Five is enough for the longest honest sequence — services, then details, then slots, then
     * book, with one spare for a retry after a slot was taken. A sixth is a model going in circles,
     * and going in circles is what a cost cap is for; better to hand off politely.
     */
    public static final int MAX_TOOL_CALLS_PER_TURN = 5;

    /**
     * Messages per conversation, counting user, assistant and tool rows alike.
     *
     * <p>Forty is generous for booking an appointment and short of anything that could run up a
     * bill. Reaching it closes the conversation rather than truncating it, so the customer is told.
     */
    public static final int MAX_MESSAGES_PER_CONVERSATION = 40;

    /**
     * How much of the past goes to the model.
     *
     * <p>Twenty messages plus the system prompt. No summarisation: it would cost a second model
     * call and add a fresh hallucination surface, to compress a conversation that is capped at forty
     * messages anyway (docs/05-ai-architecture.md §4).
     */
    public static final int CONTEXT_WINDOW_MESSAGES = 20;

    /**
     * How long a transcript is kept, measured from a conversation's last activity.
     *
     * <p>Ninety days, which is not a number chosen here: docs/05-ai-architecture.md §11 has carried
     * it since phase 09, and deferred only the job that enforces it. This constant is that sentence
     * made executable.
     *
     * <p>It lives with the ceilings rather than in {@code AiProperties} for the reason that governs
     * every number in this file, and here it is at its strongest: a retention window is a promise
     * about other people's data. Configurable, it could be quietly widened to a year on one host by
     * an environment variable nobody reviews. As a constant, lengthening it is a diff.
     *
     * <p>The <em>timer</em> is configuration and lives in {@code application.yml} — how often the
     * purge wakes up changes nothing about what is kept, exactly as the notification poller's
     * interval is configuration while its retry schedule is not.
     */
    public static final int TRANSCRIPT_RETENTION_DAYS = 90;
}
