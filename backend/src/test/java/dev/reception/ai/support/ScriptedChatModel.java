package dev.reception.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reception.ai.port.ChatMessage;
import dev.reception.ai.port.ChatModel;
import dev.reception.ai.port.ChatModelException;
import dev.reception.ai.port.ChatResponse;
import dev.reception.ai.port.TokenUsage;
import dev.reception.ai.port.ToolCall;
import dev.reception.ai.port.ToolSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * A model that says exactly what a test told it to say.
 *
 * <p><strong>{@code @Primary}, so every test in the suite gets this and never
 * {@code OpenAiChatModel}.</strong> That is the point rather than a convenience: a test that reached
 * the real provider would cost money, would be non-deterministic, and would fail in CI where there
 * is no key. Making the double the default means a new test cannot opt into the network by
 * forgetting something.
 *
 * <p>This is the payoff the {@link ChatModel} port was introduced for. The entire orchestration loop
 * — ceilings, cost accounting, tool dispatch, authority growth, persistence, degradation — is
 * exercised against scripted transcripts with no network at all.
 *
 * <p>Responses are queued and consumed in order. A test scripts a tool call, then the answer that
 * follows the tool result, and the loop runs as it would against a real model.
 *
 * <p><strong>{@code app.ai.scripted=false} takes this bean out of the context entirely</strong>,
 * leaving {@code OpenAiChatModel} as the only {@code ChatModel}. Exactly one test class does that —
 * {@code LiveReceptionistTest}, the level-3 corpus — and it is a property rather than a profile so
 * that reaching the real provider is a visible, greppable opt-in on the one class that wants it,
 * rather than a profile another test could join by accident.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.ai.scripted", havingValue = "true", matchIfMissing = true)
public class ScriptedChatModel implements ChatModel {

    private final ObjectMapper json;
    private final Deque<Supplier> scripted = new ArrayDeque<>();
    private final List<List<ChatMessage>> received = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    /** What the model will do on one call. Separate from {@link ChatResponse} so it can also throw. */
    @FunctionalInterface
    private interface Supplier {
        ChatResponse get();
    }

    public ScriptedChatModel(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public ChatResponse complete(List<ChatMessage> messages, List<ToolSpec> tools) {
        calls.incrementAndGet();
        // Copied, so a test can assert what the loop sent on turn one after turn two has mutated
        // its own list.
        received.add(List.copyOf(messages));

        if (scripted.isEmpty()) {
            // A loop that asked for one more response than the test scripted is a loop that did not
            // stop when it should have. Failing loudly beats returning a default that makes the
            // symptom disappear.
            throw new IllegalStateException(
                    "ScriptedChatModel ran out of responses after " + calls.get() + " calls");
        }
        return scripted.removeFirst().get();
    }

    /** Queue a plain answer with no tool calls, which ends the turn. */
    public ScriptedChatModel willSay(String text) {
        scripted.addLast(() -> ChatResponse.text(text, new TokenUsage(100, 20)));
        return this;
    }

    /** Queue an answer with token counts a cost test can predict. */
    public ScriptedChatModel willSay(String text, int promptTokens, int completionTokens) {
        scripted.addLast(() -> ChatResponse.text(text, new TokenUsage(promptTokens, completionTokens)));
        return this;
    }

    /** Queue one tool call. {@code arguments} is JSON, written as the model would produce it. */
    public ScriptedChatModel willCall(String tool, String argumentsJson) {
        return willCallAll(new Call(tool, argumentsJson));
    }

    /** Queue several tool calls in one response — what the loop's per-turn ceiling counts. */
    public ScriptedChatModel willCallAll(Call... requested) {
        List<Call> calls = List.of(requested);
        scripted.addLast(() -> {
            List<ToolCall> toolCalls = new ArrayList<>(calls.size());
            for (int i = 0; i < calls.size(); i++) {
                // Unique within the response, which is all a tool_call_id has to be.
                toolCalls.add(new ToolCall("call_" + i, calls.get(i).tool(), parse(calls.get(i).argumentsJson())));
            }
            return ChatResponse.toolCalls(toolCalls, new TokenUsage(100, 20));
        });
        return this;
    }

    /** Queue a provider failure — a timeout, a 5xx, a missing key. */
    public ScriptedChatModel willFail() {
        scripted.addLast(() -> {
            throw new ChatModelException("scripted provider failure");
        });
        return this;
    }

    public record Call(String tool, String argumentsJson) {}

    /** How many times the loop called a model. The assertion for "no model call was made at all". */
    public int callCount() {
        return calls.get();
    }

    /** The message list as the loop handed it over, for asserting the window and the system prompt. */
    public List<ChatMessage> messagesOnCall(int index) {
        return received.get(index);
    }

    public List<List<ChatMessage>> allCalls() {
        return List.copyOf(received);
    }

    /** Between tests. The bean is shared by the whole context, so a test that forgets leaks. */
    public void reset() {
        scripted.clear();
        received.clear();
        calls.set(0);
    }

    private JsonNode parse(String argumentsJson) {
        try {
            return json.readTree(argumentsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Test scripted arguments that are not JSON: " + argumentsJson, e);
        }
    }
}
