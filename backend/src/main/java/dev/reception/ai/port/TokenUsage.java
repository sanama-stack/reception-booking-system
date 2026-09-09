package dev.reception.ai.port;

/**
 * What one model call consumed.
 *
 * <p>Reported by the provider rather than counted locally. A local tokeniser would be a second
 * implementation of somebody else's algorithm, and the number that matters for a cost cap is the
 * one the provider will bill against.
 *
 * <p>{@link #NONE} is what a stubbed or cached response reports — zero is honest there, and it
 * keeps {@code CostTracker} from having to handle a null.
 */
public record TokenUsage(int promptTokens, int completionTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0);

    public TokenUsage {
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("Token counts cannot be negative");
        }
    }

    public int total() {
        return promptTokens + completionTokens;
    }
}
