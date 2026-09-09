package dev.reception.ai.application;

/**
 * Where a conversation is in its life.
 *
 * <p>Three states and only one of them accepts another turn. The two terminal ones are kept apart
 * because they are different sentences to the customer: one of them is the system's fault.
 */
public enum ConversationStatus {

    /** Accepting turns. */
    ACTIVE,

    /** Ended normally — the customer left, or the message ceiling was reached. */
    CLOSED,

    /**
     * Stopped by a limit rather than by the conversation finishing: the business's daily cost cap.
     * The Receptionist is unavailable, the Classic Flow is not, and the customer is told exactly
     * that.
     */
    LIMIT_REACHED
}
