package dev.reception.ai.port;

/**
 * The provider did not answer: a timeout, a 5xx, a transport failure, or a body that is not the
 * shape the adapter expects.
 *
 * <p>One exception for all of them, because the orchestration loop's response to every one is the
 * same — {@code AI_UNAVAILABLE} and a link to the Classic Flow. A caller that distinguished them
 * would have nothing different to do (docs/05-ai-architecture.md §8).
 *
 * <p>Never carries a provider body. An upstream error message can quote the request, and the
 * request contains the conversation.
 */
public class ChatModelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChatModelException(String message) {
        super(message);
    }

    public ChatModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
