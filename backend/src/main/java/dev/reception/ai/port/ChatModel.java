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
}
