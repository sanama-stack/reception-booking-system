package dev.reception.ai.port;

import java.util.List;

/**
 * The one thing the application knows about language models.
 *
 * <p>Everything above this interface speaks {@link ChatMessage}, {@link ToolSpec} and
 * {@link ChatResponse}; nothing above it imports a provider. Two payoffs, and the first is the
 * reason it exists today: tests inject a scripted implementation and the whole orchestration loop
 * runs with no network, no cost and no flakiness. Swapping providers later is one class — a real
 * benefit, but not one that would have justified the abstraction on its own
 * (docs/05-ai-architecture.md §10).
 *
 * <p>Synchronous and one-shot. Streaming is V1.1, and adding it later is a second method rather
 * than a change to this one.
 */
public interface ChatModel {

    /**
     * One completion.
     *
     * @param messages the system prompt, the window of recent turns, and the new user message — in
     *     order, because order is meaning here
     * @param tools every tool the model may call this turn. The registry publishes all eight on
     *     every call; narrowing the list per turn would be a routing decision, which is the
     *     intent-classification design ADR-0004 rejected
     * @throws ChatModelException if the provider did not answer usefully
     */
    ChatResponse complete(List<ChatMessage> messages, List<ToolSpec> tools);

    /**
     * Whether this model can run at all, asked before a customer is offered one.
     *
     * <p>On the port rather than on {@code AiProperties} because the application layer must not
     * know that "configured" means an OpenAI key — the scripted double in the tests needs no key
     * and is perfectly available, and the E2E's fake provider (ADR-0011) is the real adapter
     * pointed somewhere else. An implementation that cannot answer is the one that says so.
     *
     * <p>This is a question about configuration, not about health. A provider that is configured
     * and momentarily unreachable answers {@code true} here and fails in
     * {@link #complete} — the customer's two messages differ, and so must the two paths
     * (G42, issue #37).
     */
    boolean isAvailable();
}
